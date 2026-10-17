package com.example.network

import android.content.Context
import android.net.Uri
import com.example.data.TransmissionRepository
import com.example.model.TransmissionStatus
import com.example.model.TransmissionTask
import com.example.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TcpServerManager(
    private val context: Context,
    private val repository: TransmissionRepository,
    private val scope: CoroutineScope,
    private val onPairingRequested: (JSONObject, (Boolean) -> Unit) -> Unit
) {
    companion object {
        @Volatile
        private var lastAcceptedDeviceId: String? = null
        @Volatile
        private var lastAcceptedTime: Long = 0L
    }

    private var serverSocket: ServerSocket? = null
    var isRunning = false

    suspend fun startServer() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        isRunning = true
        try {
            serverSocket = ServerSocket(TcpTransferProtocol.PORT)
            while (isRunning) {
                val socket = serverSocket?.accept() ?: break
                scope.launch { handleClient(socket) }
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_SERVER", "ServerSocket lifecycle failed", e)
        }
        isRunning = false
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.sendBufferSize = 1024 * 1024
            socket.receiveBufferSize = 1024 * 1024
            socket.tcpNoDelay = true

            val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)
            val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
            
            if (com.example.network.TransferStateManager.activeTaskIds.size >= com.example.network.TransferStateManager.maxConcurrencyLimit.value ||
                com.example.network.TransferStateManager.activePairingRequest.value != null) {
                val reply = JSONObject().apply { put("type", "BUSY_REJECT") }
                TcpTransferProtocol.sendJsonString(outStream, reply)
                socket.close()
                return@withContext
            }

            val request = TcpTransferProtocol.receiveJsonString(inStream) ?: return@withContext
            
            val type = request.optString("type")
            if (type == "PAIRING_REQUEST") {
                val dId = request.optString("deviceId")
                val gId = request.optString("groupId")
                val now = System.currentTimeMillis()
                
                val isGroupAccepted = gId.isNotEmpty() && com.example.network.TransferStateManager.acceptedGroups.contains(gId)
                val isDeviceAccepted = dId.isNotEmpty() && dId == lastAcceptedDeviceId && now - lastAcceptedTime < 300_000
                
                if (isGroupAccepted || isDeviceAccepted) {
                    if (dId.isNotEmpty()) {
                        lastAcceptedDeviceId = dId
                        lastAcceptedTime = now
                    }
                    if (gId.isNotEmpty()) {
                        com.example.network.TransferStateManager.acceptedGroups.add(gId)
                    }
                    val reply = JSONObject().apply { put("type", "PAIRING_ACCEPT") }
                    TcpTransferProtocol.sendJsonString(outStream, reply)
                    receiveFile(socket, request)
                    return@withContext
                }

                 withContext(Dispatchers.Main) {
                    onPairingRequested(request) { accepted ->
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (accepted) {
                                    if (dId.isNotEmpty()) {
                                        lastAcceptedDeviceId = dId
                                        lastAcceptedTime = System.currentTimeMillis()
                                    }
                                    if (gId.isNotEmpty()) {
                                        com.example.network.TransferStateManager.acceptedGroups.add(gId)
                                    }
                                    val reply = JSONObject().apply { put("type", "PAIRING_ACCEPT") }
                                    TcpTransferProtocol.sendJsonString(outStream, reply)
                                    receiveFile(socket, request)
                                } else {
                                    val reply = JSONObject().apply { put("type", "PAIRING_REJECT") }
                                    TcpTransferProtocol.sendJsonString(outStream, reply)
                                    socket.close()
                                }
                            } catch (e: Throwable) {
                                com.example.utils.AppLogger.error("TCP_SERVER", "Error in main thread pairing accept callback", e)
                                try { socket.close() } catch (ex: Throwable) {}
                            }
                        }
                    }
                }
            } else if (type == "RESUME_REQUEST") {
                val connectionId = request.optString("connectionId")
                val deviceId = request.optString("deviceId")
                val offset = request.optLong("offset")
                val task = repository.getTaskByConnection(deviceId, connectionId)
                if (task != null && !task.isSender) {
                    val reply = JSONObject().apply { 
                        put("type", "RESUME_ACCEPT") 
                        put("offset", task.offset)
                    }
                    TcpTransferProtocol.sendJsonString(outStream, reply)
                    resumeFile(socket, task)
                } else {
                    val reply = JSONObject().apply { put("type", "RESUME_REJECT") }
                    TcpTransferProtocol.sendJsonString(outStream, reply)
                    socket.close()
                }
            } else if (type == "WALKIE_TALKIE_ROOM_JOIN_REQUEST") {
                val applicantId = request.optString("deviceId")
                val rId = request.optString("roomId")
                val rName = request.optString("roomName")
                val peerIp = socket.inetAddress?.hostAddress ?: request.optString("ipAddress")

                withContext(Dispatchers.Main) {
                    TransferStateManager.activeRoomJoinRequest.value =
                        TransferStateManager.RoomJoinRequest(
                            applicantDeviceId = applicantId,
                            applicantIp = peerIp,
                            roomId = rId,
                            roomName = rName,
                            callback = { accepted ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        if (accepted) {
                                            val myId = TransferStateManager.getOrCreateDeviceId(context)
                                            val curRoom = TransferStateManager.currentRoom.value
                                            val reply = JSONObject().apply {
                                                put("type", "WALKIE_TALKIE_ACCEPT")
                                                put("deviceId", myId)
                                                if (curRoom != null) {
                                                    put("room", JSONObject().apply {
                                                        put("roomId", curRoom.roomId)
                                                        put("roomName", curRoom.roomName)
                                                        put("creatorId", curRoom.creatorId)
                                                    })
                                                }
                                            }
                                            TcpTransferProtocol.sendJsonString(outStream, reply)

                                            val peerDevice = DeviceInfo(applicantId, peerIp, lastSeenTimestamp = System.currentTimeMillis())
                                            withContext(Dispatchers.Main) {
                                                TransferStateManager.addIntercomDevice(peerDevice, socket)
                                                android.widget.Toast.makeText(context, "设备 $applicantId 已成功加入房间 '${rName}'", android.widget.Toast.LENGTH_SHORT).show()
                                            }

                                            WalkieTalkieAudioManager.startPlayback()
                                            TransferStateManager.broadcastPeerSync(myId)
                                            handleWalkieTalkieSession(socket, inStream, applicantId, myId)
                                        } else {
                                            val reply = JSONObject().apply {
                                                put("type", "WALKIE_TALKIE_REJECT")
                                            }
                                            TcpTransferProtocol.sendJsonString(outStream, reply)
                                            socket.close()
                                        }
                                    } catch (e: Throwable) {
                                        com.example.utils.AppLogger.error("TCP_SERVER_ROOM", "Error in room join callback", e)
                                        try { socket.close() } catch (ex: Throwable) {}
                                    }
                                }
                            }
                        )
                }
            } else if (type == "WALKIE_TALKIE_REQUEST") {
                val peerDeviceId = request.optString("deviceId")
                val peerIp = socket.inetAddress?.hostAddress ?: request.optString("ipAddress")
                val isMeshAuto = request.optBoolean("isMeshAuto", false)
                val hasActiveIntercom = com.example.network.TransferStateManager.walkieTalkieConnectedDevices.value.isNotEmpty()

                if (isMeshAuto || hasActiveIntercom) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val myId = com.example.network.TransferStateManager.getOrCreateDeviceId(context)
                            val reply = JSONObject().apply {
                                put("type", "WALKIE_TALKIE_ACCEPT")
                                put("deviceId", myId)
                            }
                            TcpTransferProtocol.sendJsonString(outStream, reply)

                            val peerDevice = DeviceInfo(peerDeviceId, peerIp, lastSeenTimestamp = System.currentTimeMillis())
                            withContext(Dispatchers.Main) {
                                com.example.network.TransferStateManager.addIntercomDevice(peerDevice, socket)
                                android.widget.Toast.makeText(context, "设备 $peerDeviceId 已加入对讲网络", android.widget.Toast.LENGTH_SHORT).show()
                            }

                            com.example.network.WalkieTalkieAudioManager.startPlayback()
                            com.example.network.TransferStateManager.broadcastPeerSync(myId)
                            handleWalkieTalkieSession(socket, inStream, peerDeviceId, myId)
                        } catch (e: Throwable) {
                            com.example.utils.AppLogger.error("TCP_SERVER_WALKIE", "Error in auto-accept mesh for $peerDeviceId", e)
                            try { socket.close() } catch (ex: Throwable) {}
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        com.example.network.TransferStateManager.activeWalkieTalkieRequest.value = 
                            com.example.network.WalkieTalkieRequest(
                                deviceId = peerDeviceId,
                                ipAddress = peerIp,
                                callback = { accepted ->
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            if (accepted) {
                                                val myId = com.example.network.TransferStateManager.getOrCreateDeviceId(context)
                                                val reply = JSONObject().apply {
                                                    put("type", "WALKIE_TALKIE_ACCEPT")
                                                    put("deviceId", myId)
                                                }
                                                TcpTransferProtocol.sendJsonString(outStream, reply)
                                                
                                                val peerDevice = DeviceInfo(peerDeviceId, peerIp, lastSeenTimestamp = System.currentTimeMillis())
                                                withContext(Dispatchers.Main) {
                                                    com.example.network.TransferStateManager.addIntercomDevice(peerDevice, socket)
                                                    android.widget.Toast.makeText(context, "设备 $peerDeviceId 已加入对讲网络", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                
                                                com.example.network.WalkieTalkieAudioManager.startPlayback()
                                                com.example.network.TransferStateManager.broadcastPeerSync(myId)
                                                handleWalkieTalkieSession(socket, inStream, peerDeviceId, myId)
                                            } else {
                                                val reply = JSONObject().apply {
                                                    put("type", "WALKIE_TALKIE_REJECT")
                                                }
                                                TcpTransferProtocol.sendJsonString(outStream, reply)
                                                socket.close()
                                            }
                                        } catch (e: Throwable) {
                                            com.example.utils.AppLogger.error("TCP_SERVER_WALKIE", "Error in walkie talkie accept callback", e)
                                            try { socket.close() } catch (ex: Throwable) {}
                                        }
                                    }
                                }
                            )
                    }
                }
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_SERVER", "Failed in handleClient", e)
            try { socket.close() } catch (ex: Throwable) {}
        }
    }

    private suspend fun handleWalkieTalkieSession(
        socket: Socket,
        inStream: java.io.InputStream,
        peerDeviceId: String,
        myId: String
    ) = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val msg = TcpTransferProtocol.receiveJsonString(inStream) ?: break
                val msgType = msg.optString("type")
                if (msgType == "WALKIE_TALKIE_STATE") {
                    val isSpeaking = msg.optBoolean("isSpeaking", false)
                    com.example.network.TransferStateManager.setPeerSpeaking(peerDeviceId, isSpeaking)
                } else if (msgType == "WALKIE_TALKIE_AUDIO_SHARING_STATE") {
                    val isSharing = msg.optBoolean("isAudioSharing", false)
                    com.example.network.TransferStateManager.setPeerAudioSharing(peerDeviceId, isSharing)
                } else if (msgType == "WALKIE_TALKIE_AUDIO") {
                    val audioData = msg.optString("data", "")
                    if (audioData.isNotEmpty()) {
                        com.example.network.WalkieTalkieAudioManager.playAudioData(audioData)
                    }
                } else if (msgType == "WALKIE_TALKIE_SYNC_PEERS") {
                    val peersArr = msg.optJSONArray("peers")
                    if (peersArr != null) {
                        for (i in 0 until peersArr.length()) {
                            val pObj = peersArr.optJSONObject(i) ?: continue
                            val pId = pObj.optString("deviceId")
                            val pIp = pObj.optString("ipAddress")
                            if (pId.isNotEmpty() && pId != myId && !com.example.network.TransferStateManager.walkieTalkieSockets.containsKey(pId) && pIp.isNotEmpty()) {
                                com.example.network.TransferStateManager.autoConnectMeshPeer(context, DeviceInfo(pId, pIp, System.currentTimeMillis()), myId, scope)
                            }
                        }
                    }
                } else if (msgType == "WALKIE_TALKIE_ROOM_DESTROYED") {
                    withContext(Dispatchers.Main) {
                        if (com.example.network.TransferStateManager.currentRoom.value != null) {
                            com.example.network.TransferStateManager.currentRoom.value = null
                            com.example.network.TransferStateManager.disconnectAllIntercomDevices()
                            android.widget.Toast.makeText(context, "管理员已离线，房间已解散", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    break
                } else if (msgType == "WALKIE_TALKIE_ROOM_HEARTBEAT") {
                    // Room owner heartbeat
                } else if (msgType == "WALKIE_TALKIE_DISCONNECT") {
                    break
                }
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_SERVER_WALKIE", "Error in active walkie talkie loop for $peerDeviceId", e)
        } finally {
            withContext(Dispatchers.Main) {
                com.example.network.TransferStateManager.removeIntercomDevice(peerDeviceId)
                try { socket.close() } catch (ex: Throwable) {}
                val curRoom = com.example.network.TransferStateManager.currentRoom.value
                if (curRoom != null && curRoom.creatorId == peerDeviceId) {
                    com.example.network.TransferStateManager.currentRoom.value = null
                    com.example.network.TransferStateManager.disconnectAllIntercomDevices()
                    android.widget.Toast.makeText(context, "管理员已离线，房间已解散", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun receiveFile(socket: Socket, request: JSONObject) = withContext(Dispatchers.IO) {
        val fileName = request.getString("fileName")
        val fileSize = request.getLong("fileSize")
        // const fileHash = request.optString("fileHash", null)
        val deviceId = request.getString("deviceId")
        val connectionId = request.getString("connectionId")
        
        val prefs = context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        val customPath = prefs.getString("storage_path", "/storage/emulated/0/Download/") ?: "/storage/emulated/0/Download/"
        var saveDir = File(customPath)
        try {
            if (!com.example.utils.FileUtil.canWriteToDirectory(saveDir)) {
                com.example.utils.AppLogger.info("TCP_SERVER", "Custom path $customPath is not fully writable, falling back to external files dir")
                saveDir = context.getExternalFilesDir(null) ?: context.filesDir
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.warn("TCP_SERVER", "Cannot write custom storage path, fallback to external files dir: ${e.message}")
            saveDir = context.getExternalFilesDir(null) ?: context.filesDir
        }
        val file = File(saveDir, "$fileName.tmp")
        
        val task = TransmissionTask(
            fileUri = file.absolutePath,
            fileName = fileName,
            fileSize = fileSize,
            offset = 0L,
            deviceId = deviceId,
            connectionId = connectionId,
            status = TransmissionStatus.CONNECTING,
            isSender = false,
            fileHash = request.optString("fileHash", null)
        )
        val taskId = repository.insert(task).toInt()
        repository.updateTaskStatus(taskId, TransmissionStatus.SENDING)
        
        TransferStateManager.updateTaskProgress(taskId, fileName, fileSize, 0L, false)

        try {
            val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)
            val out = java.io.BufferedOutputStream(FileOutputStream(file, false), 262144)
            val buffer = ByteArray(131072)
            var totalRead = 0L
            var read: Int
            var lastDbUpdateTime = 0L
            
            while (inStream.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                totalRead += read
                
                TransferStateManager.updateTaskProgress(taskId, fileName, fileSize, totalRead, false)
                
                TransferStateManager.throttle(read)
                
                val now = System.currentTimeMillis()
                if (now - lastDbUpdateTime > 2000) {
                    repository.updateTaskOffset(taskId, totalRead)
                    lastDbUpdateTime = now
                }
            }
            out.flush()
            out.close()
            repository.updateTaskOffset(taskId, totalRead)
            
            if (totalRead == fileSize) {
                val finalFile = File(saveDir, fileName)
                val renameResult = file.renameTo(finalFile)
                val targetPath = if (renameResult) finalFile.absolutePath else file.absolutePath
                repository.updateTaskFileUri(taskId, targetPath)
                
                val reqHash = request.optString("fileHash", null)
                if (!reqHash.isNullOrEmpty()) {
                    repository.updateTaskStatus(taskId, TransmissionStatus.VERIFYING)
                    FileUtil.scanFileToSystemMedia(context, File(targetPath))
                    scope.launch(Dispatchers.IO) {
                        try {
                            val actualHash = FileUtil.getFileHash(context, Uri.fromFile(File(targetPath)))
                            if (actualHash == reqHash) {
                                repository.updateTaskStatus(taskId, TransmissionStatus.SUCCESS)
                            } else {
                                repository.updateTaskStatus(taskId, TransmissionStatus.FAILED)
                            }
                        } catch (e: Throwable) {
                            repository.updateTaskStatus(taskId, TransmissionStatus.FAILED)
                        }
                    }
                } else {
                    repository.updateTaskStatus(taskId, TransmissionStatus.SUCCESS)
                    FileUtil.scanFileToSystemMedia(context, File(targetPath))
                }
            } else {
                repository.updateTaskStatus(taskId, TransmissionStatus.WAITING_RECONNECT)
            }
            
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_SERVER", "Exception in receiveFile stream block for task ID: $taskId", e)
            repository.updateTaskStatus(taskId, TransmissionStatus.WAITING_RECONNECT)
        } finally {
            TransferStateManager.removeActiveTask(taskId)
        }
    }

    private suspend fun resumeFile(socket: Socket, task: TransmissionTask) = withContext(Dispatchers.IO) {
        val taskId = task.id
        TransferStateManager.updateTaskProgress(taskId, task.fileName, task.fileSize, task.offset, false)

        try {
            repository.updateTaskStatus(task.id, TransmissionStatus.RESUMING)
            val file = File(task.fileUri)
            val out = java.io.BufferedOutputStream(FileOutputStream(file, true), 262144) // Append
            val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)
            val buffer = ByteArray(131072)
            var totalRead = task.offset
            var read: Int
            var lastDbUpdateTime = 0L
            
            repository.updateTaskStatus(task.id, TransmissionStatus.SENDING)
            
            while (inStream.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                totalRead += read
                
                TransferStateManager.updateTaskProgress(taskId, task.fileName, task.fileSize, totalRead, false)
                
                TransferStateManager.throttle(read)
                
                val now = System.currentTimeMillis()
                if (now - lastDbUpdateTime > 2000) {
                    repository.updateTaskOffset(task.id, totalRead)
                    lastDbUpdateTime = now
                }
            }
            out.flush()
            out.close()
            repository.updateTaskOffset(task.id, totalRead)
            
            if (totalRead == task.fileSize) {
                val finalFile = File(file.parentFile, task.fileName)
                val renameResult = file.renameTo(finalFile)
                val targetPath = if (renameResult) finalFile.absolutePath else file.absolutePath
                repository.updateTaskFileUri(task.id, targetPath)
                
                if (task.fileHash != null) {
                    repository.updateTaskStatus(task.id, TransmissionStatus.VERIFYING)
                    FileUtil.scanFileToSystemMedia(context, File(targetPath))
                    scope.launch(Dispatchers.IO) {
                        try {
                            val actualHash = FileUtil.getFileHash(context, Uri.fromFile(File(targetPath)))
                            if (actualHash == task.fileHash) {
                                repository.updateTaskStatus(task.id, TransmissionStatus.SUCCESS)
                            } else {
                                repository.updateTaskStatus(task.id, TransmissionStatus.FAILED)
                            }
                        } catch (e: Throwable) {
                            repository.updateTaskStatus(task.id, TransmissionStatus.FAILED)
                        }
                    }
                } else {
                    repository.updateTaskStatus(task.id, TransmissionStatus.SUCCESS)
                    FileUtil.scanFileToSystemMedia(context, File(targetPath))
                }
            } else {
                repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_SERVER", "Exception in resumeFile stream block for task ID: ${task.id}", e)
            repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
        } finally {
            TransferStateManager.removeActiveTask(taskId)
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
    }
}
