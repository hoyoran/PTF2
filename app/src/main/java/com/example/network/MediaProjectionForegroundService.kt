package com.example.network

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.example.MainActivity
import com.example.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MediaProjectionForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isForegroundStarted = false

    companion object {
        private const val TAG = "MediaProjectionService"
        const val CHANNEL_ID = "ptf_media_projection_channel"
        const val NOTIFICATION_ID = 8897

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val ACTION_START_PROJECTION = "com.example.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.example.action.STOP_PROJECTION"

        fun startService(context: Context, resultCode: Int, data: Intent) {
            try {
                val intent = Intent(context, MediaProjectionForegroundService::class.java).apply {
                    action = ACTION_START_PROJECTION
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_DATA_INTENT, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to start MediaProjectionForegroundService", e)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, MediaProjectionForegroundService::class.java).apply {
                    action = ACTION_STOP_PROJECTION
                }
                context.stopService(intent)
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to stop MediaProjectionForegroundService", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 1. Service启动后第一时间创建 Notification Channel 并调用 startForeground()
        createNotificationChannel()
        ensureForegroundStarted()
    }

    private fun ensureForegroundStarted() {
        if (isForegroundStarted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("系统音频共享中", "正在采集手机内部播放的声音"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("系统音频共享中", "正在采集手机内部播放的声音"))
            }
            isForegroundStarted = true
            AppLogger.info(TAG, "MediaProjection Foreground Service started successfully")
        } catch (e: Exception) {
            AppLogger.error(TAG, "startForeground failed in MediaProjectionForegroundService", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第一时间确保 startForeground() 已调用
        ensureForegroundStarted()

        if (intent == null || intent.action == ACTION_STOP_PROJECTION) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            initMediaProjectionAndAudioCapture(resultCode, dataIntent)
        } else {
            AppLogger.error(TAG, "Invalid resultCode ($resultCode) or dataIntent ($dataIntent)")
            TransferStateManager.isAudioSharingEnabled.value = false
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initMediaProjectionAndAudioCapture(resultCode: Int, dataIntent: Intent) {
        try {
            // 顺序：
            // 1. Service启动 -> 创建Notification Channel -> startForeground() (在 onCreate/ensureForegroundStarted 完成)
            // 2. 初始化 MediaProjection
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mediaProjectionManager == null) {
                AppLogger.error(TAG, "MediaProjectionManager is null")
                TransferStateManager.isAudioSharingEnabled.value = false
                stopSelf()
                return
            }

            val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, dataIntent)
            if (mediaProjection == null) {
                AppLogger.error(TAG, "getMediaProjection returned null")
                TransferStateManager.isAudioSharingEnabled.value = false
                stopSelf()
                return
            }

            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLogger.info(TAG, "MediaProjection onStop callback triggered by system")
                    TransferStateManager.isAudioSharingEnabled.value = false
                    stopSelf()
                }
            }, null)

            // 3. 初始化 AudioPlaybackCapture (在 startForeground 和 MediaProjection 成功初始化之后)
            TransferStateManager.isAudioSharingEnabled.value = true
            WalkieTalkieAudioManager.broadcastAudioSharingState(true)
            WalkieTalkieAudioManager.startSystemAudioCapture(
                applicationContext,
                mediaProjection,
                serviceScope
            )
            AppLogger.info(TAG, "MediaProjection and AudioPlaybackCapture initialized successfully")
        } catch (e: Throwable) {
            AppLogger.error(TAG, "Failed to initialize MediaProjection / AudioPlaybackCapture", e)
            TransferStateManager.isAudioSharingEnabled.value = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        WalkieTalkieAudioManager.stopSystemAudioCapture()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForegroundStarted = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "系统音频捕获服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持系统内部音频采集和混音运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
