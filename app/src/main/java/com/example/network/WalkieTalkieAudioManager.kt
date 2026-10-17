package com.example.network

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.example.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedOutputStream
import kotlin.math.sqrt

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.os.Build

object WalkieTalkieAudioManager {
    private const val TAG = "WalkieTalkieAudio"
    
    // Audio configuration
    const val SAMPLE_RATE = 16000
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    // Track playback state
    private var audioTrack: AudioTrack? = null
    
    // Active recording job
    private var recordingJob: Job? = null
    private var isRecording = false

    // System internal audio capture state
    private var systemAudioJob: Job? = null
    private var isSystemAudioCapturing = false
    private var activeMediaProjection: MediaProjection? = null
    @Volatile
    private var latestSystemPcmChunk: ByteArray? = null

    // Audio Effects
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var autoGainControl: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun startSystemAudioCapture(
        context: android.content.Context,
        mediaProjection: MediaProjection,
        scope: CoroutineScope
    ) {
        if (isSystemAudioCapturing) return
        activeMediaProjection = mediaProjection
        isSystemAudioCapturing = true

        systemAudioJob = scope.launch(Dispatchers.IO) {
            var systemAudioRecord: AudioRecord? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
                    val bufferSize = maxOf(minBufSize, 4096)

                    systemAudioRecord = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(ENCODING)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_IN)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(config)
                        .build()

                    if (systemAudioRecord.state == AudioRecord.STATE_INITIALIZED) {
                        systemAudioRecord.startRecording()
                        AppLogger.info(TAG, "AudioPlaybackCapture system audio recording started successfully")

                        val sysBuffer = ByteArray(2048)
                        while (isSystemAudioCapturing) {
                            val read = systemAudioRecord.read(sysBuffer, 0, sysBuffer.size)
                            if (read > 0) {
                                val chunk = if (read == sysBuffer.size) sysBuffer.clone() else sysBuffer.copyOf(read)
                                latestSystemPcmChunk = chunk

                                // If microphone PTT is NOT pressed but system audio sharing is ON, stream system audio directly
                                if (!isRecording && TransferStateManager.isAudioSharingEnabled.value) {
                                    val sockets = TransferStateManager.walkieTalkieSockets
                                    if (sockets.isNotEmpty()) {
                                        val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                                        val msg = JSONObject().apply {
                                            put("type", "WALKIE_TALKIE_AUDIO")
                                            put("data", base64Data)
                                        }
                                        sockets.forEach { (_, socket) ->
                                            if (!socket.isClosed) {
                                                try {
                                                    val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                                                    runBlocking {
                                                        TcpTransferProtocol.sendJsonString(outStream, msg)
                                                    }
                                                } catch (e: Throwable) {}
                                            }
                                        }
                                    }
                                }
                            } else if (read < 0) {
                                AppLogger.error(TAG, "System audio read error: $read")
                                break
                            }
                        }
                    } else {
                        AppLogger.error(TAG, "AudioPlaybackCapture systemAudioRecord uninitialized")
                        TransferStateManager.isAudioSharingEnabled.value = false
                    }
                } else {
                    AppLogger.warn(TAG, "System audio capture requires Android 10+ (API 29+)")
                    TransferStateManager.isAudioSharingEnabled.value = false
                }
            } catch (e: SecurityException) {
                AppLogger.error(TAG, "SecurityException in system audio capture", e)
                TransferStateManager.isAudioSharingEnabled.value = false
            } catch (e: Throwable) {
                AppLogger.error(TAG, "Error in system audio capture loop", e)
                TransferStateManager.isAudioSharingEnabled.value = false
            } finally {
                isSystemAudioCapturing = false
                latestSystemPcmChunk = null
                try {
                    systemAudioRecord?.apply {
                        stop()
                        release()
                    }
                } catch (e: Throwable) {}
                MediaProjectionForegroundService.stopService(context)
                AppLogger.info(TAG, "System audio capture stopped")
            }
        }
    }

    fun stopSystemAudioCapture(context: android.content.Context? = null) {
        isSystemAudioCapturing = false
        systemAudioJob?.cancel()
        systemAudioJob = null
        latestSystemPcmChunk = null
        try {
            activeMediaProjection?.stop()
        } catch (e: Throwable) {}
        activeMediaProjection = null
        TransferStateManager.isAudioSharingEnabled.value = false
        context?.let { MediaProjectionForegroundService.stopService(it) }
    }

    private fun mixPcm16Bit(micChunk: ByteArray, sysChunk: ByteArray): ByteArray {
        val minLen = minOf(micChunk.size, sysChunk.size)
        val out = micChunk.copyOf(micChunk.size)
        val samplesCount = minLen / 2
        for (i in 0 until samplesCount) {
            val idx = i * 2
            val micSample = (micChunk[idx].toInt() and 0xFF) or (micChunk[idx + 1].toInt() shl 8)
            val sysSample = (sysChunk[idx].toInt() and 0xFF) or (sysChunk[idx + 1].toInt() shl 8)
            val mixed = (micSample.toShort() + sysSample.toShort()).coerceIn(-32768, 32767)
            out[idx] = (mixed and 0xFF).toByte()
            out[idx + 1] = ((mixed shr 8) and 0xFF).toByte()
        }
        return out
    }

    // Initialize AudioTrack for playing peer voice
    @SuppressLint("Range")
    @Synchronized
    fun startPlayback() {
        if (audioTrack != null) return
        try {
            val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING,
                maxOf(minBufSize, 8192),
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            AppLogger.info(TAG, "AudioTrack started successfully")
        } catch (e: Throwable) {
            AppLogger.error(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    @Synchronized
    fun stopPlayback() {
        try {
            audioTrack?.apply {
                stop()
                release()
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
            TransferStateManager.peerAudioLevel.value = 0f
        }
    }

    // Play received audio data (PCM 16-bit)
    @Synchronized
    fun playAudioData(base64Data: String) {
        try {
            val data = Base64.decode(base64Data, Base64.NO_WRAP)
            if (audioTrack == null) {
                startPlayback()
            }
            audioTrack?.write(data, 0, data.size)

            // Compute RMS audio level for peer visualizer (0.0 to 1.0)
            if (data.size >= 2) {
                var sum = 0.0
                val shortCount = data.size / 2
                for (i in 0 until shortCount) {
                    val sample = (data[i * 2].toInt() and 0xFF) or (data[i * 2 + 1].toInt() shl 8)
                    val shortVal = sample.toShort()
                    sum += shortVal * shortVal
                }
                val rms = sqrt(sum / shortCount)
                val normalizedLevel = (rms / 8000.0).toFloat().coerceIn(0f, 1f)
                TransferStateManager.peerAudioLevel.value = normalizedLevel
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "Error playing audio chunk", e)
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        releaseAudioEffects()
        if (!TransferStateManager.isNoiseSuppressionEnabled.value) return

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                AppLogger.info(TAG, "NoiseSuppressor enabled")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "NoiseSuppressor initialization failed, continuing without NS", e)
        }

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                AppLogger.info(TAG, "AcousticEchoCanceler enabled")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "AcousticEchoCanceler failed", e)
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                autoGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
                AppLogger.info(TAG, "AutomaticGainControl enabled")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "AutomaticGainControl failed", e)
        }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (e: Throwable) {}
        try { echoCanceler?.release() } catch (e: Throwable) {}
        try { autoGainControl?.release() } catch (e: Throwable) {}
        noiseSuppressor = null
        echoCanceler = null
        autoGainControl = null
    }

    // Start recording from microphone and streaming over socket
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(scope: CoroutineScope) {
        if (isRecording) return
        isRecording = true
        
        recordingJob = scope.launch(Dispatchers.IO) {
            var audioRecord: AudioRecord? = null
            try {
                val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
                val bufferSize = maxOf(minBufSize, 4096)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    bufferSize
                )
                
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    AppLogger.error(TAG, "AudioRecord initialization failed, retrying MIC source...")
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        ENCODING,
                        bufferSize
                    )
                }
                
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    AppLogger.error(TAG, "AudioRecord state still uninitialized")
                    return@launch
                }

                // Attach noise suppressor / audio FX if supported
                attachAudioEffects(audioRecord.audioSessionId)
                
                audioRecord.startRecording()
                AppLogger.info(TAG, "AudioRecord started successfully")
                
                // Read chunks of 2048 bytes (1024 samples, ~64ms)
                val buffer = ByteArray(2048)
                while (isRecording) {
                    val readBytes = audioRecord.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        // Calculate local microphone volume for UI visualizer
                        var sum = 0.0
                        val shortCount = readBytes / 2
                        for (i in 0 until shortCount) {
                            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
                            val shortVal = sample.toShort()
                            sum += shortVal * shortVal
                        }
                        val rms = sqrt(sum / maxOf(1, shortCount))
                        val normalizedLevel = (rms / 8000.0).toFloat().coerceIn(0f, 1f)
                        TransferStateManager.localAudioLevel.value = normalizedLevel

                        // Broadcast audio chunk to ALL connected peer sockets
                        val sockets = TransferStateManager.walkieTalkieSockets
                        if (sockets.isNotEmpty()) {
                            var chunk = if (readBytes < buffer.size) {
                                buffer.copyOf(readBytes)
                            } else {
                                buffer
                            }

                            // Mix system audio into audio stream if enabled
                            if (TransferStateManager.isAudioSharingEnabled.value) {
                                val sysPcm = latestSystemPcmChunk
                                if (sysPcm != null && sysPcm.isNotEmpty()) {
                                    chunk = mixPcm16Bit(chunk, sysPcm)
                                }
                            }

                            val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                            val msg = JSONObject().apply {
                                put("type", "WALKIE_TALKIE_AUDIO")
                                put("data", base64Data)
                            }

                            sockets.forEach { (peerId, socket) ->
                                if (!socket.isClosed) {
                                    try {
                                        val outStream = BufferedOutputStream(socket.getOutputStream(), 262144)
                                        runBlocking {
                                            TcpTransferProtocol.sendJsonString(outStream, msg)
                                        }
                                    } catch (e: Throwable) {
                                        AppLogger.error(TAG, "Failed to send audio packet to $peerId", e)
                                    }
                                }
                            }
                        }
                    } else if (readBytes < 0) {
                        AppLogger.error(TAG, "AudioRecord read error: $readBytes")
                        break
                    }
                }
            } catch (e: Throwable) {
                AppLogger.error(TAG, "Exception in recording loop", e)
            } finally {
                isRecording = false
                releaseAudioEffects()
                try {
                    audioRecord?.apply {
                        stop()
                        release()
                    }
                } catch (e: Throwable) {}
                TransferStateManager.localAudioLevel.value = 0f
                AppLogger.info(TAG, "AudioRecord stopped")
            }
        }
    }

    @Synchronized
    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioEffects()
        TransferStateManager.localAudioLevel.value = 0f
    }

    fun broadcastSpeakingState(isSpeaking: Boolean) {
        val msg = JSONObject().apply {
            put("type", "WALKIE_TALKIE_STATE")
            put("isSpeaking", isSpeaking)
        }
        TransferStateManager.walkieTalkieSockets.forEach { (peerId, socket) ->
            if (!socket.isClosed) {
                try {
                    val outStream = BufferedOutputStream(socket.getOutputStream(), 262144)
                    runBlocking {
                        TcpTransferProtocol.sendJsonString(outStream, msg)
                    }
                } catch (e: Throwable) {
                    AppLogger.error(TAG, "Failed to send state packet to $peerId", e)
                }
            }
        }
    }

    fun broadcastAudioSharingState(isSharing: Boolean) {
        val msg = JSONObject().apply {
            put("type", "WALKIE_TALKIE_AUDIO_SHARING_STATE")
            put("isAudioSharing", isSharing)
        }
        TransferStateManager.walkieTalkieSockets.forEach { (peerId, socket) ->
            if (!socket.isClosed) {
                try {
                    val outStream = BufferedOutputStream(socket.getOutputStream(), 262144)
                    runBlocking {
                        TcpTransferProtocol.sendJsonString(outStream, msg)
                    }
                } catch (e: Throwable) {
                    AppLogger.error(TAG, "Failed to send audio sharing state packet to $peerId", e)
                }
            }
        }
    }
}
