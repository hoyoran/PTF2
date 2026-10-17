package com.example.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

enum class BubbleState {
    IDLE,              // 未连接 / 常态
    DISCOVERED,        // 发现设备
    TRANSFERRING,      // 正在传输 (上传 or 下载)
    PAIRING_REQUEST    // 配对/传输请求
}

enum class DockSide {
    NONE,
    LEFT,
    RIGHT
}

data class PairingRequest(
    val deviceId: String,
    val fileName: String,
    val fileSize: Long,
    val groupId: String? = null,
    val groupFileCount: Int = 1,
    val callback: (Boolean) -> Unit
)

data class WalkieTalkieRequest(
    val deviceId: String,
    val ipAddress: String,
    val callback: (Boolean) -> Unit
)

enum class IntercomState {
    IDLE,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    TALKING,
    MIC_LOCKED,
    DISCONNECTING,
    DISCONNECTED,
    ERROR
}

data class IntercomRoom(
    val roomId: String,
    val roomName: String,
    val creatorId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val members: List<DeviceInfo> = emptyList()
)

data class RoomInvitation(
    val roomId: String,
    val roomName: String,
    val inviterId: String,
    val inviterIp: String,
    val callback: (Boolean) -> Unit
)

object TransferStateManager {
    // Audio Sharing Mode (System Internal Audio Sharing)
    val isAudioSharingEnabled = MutableStateFlow(false)
    val peerAudioSharingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // Room System State
    data class DiscoveredRoomInfo(
        val room: IntercomRoom,
        val hostIp: String,
        val lastSeenTimestamp: Long = System.currentTimeMillis()
    )

    val intercomRooms = MutableStateFlow<List<IntercomRoom>>(emptyList())
    val currentRoom = MutableStateFlow<IntercomRoom?>(null)
    val activeRoomInvitation = MutableStateFlow<RoomInvitation?>(null)
    val discoveredRooms = MutableStateFlow<Map<String, DiscoveredRoomInfo>>(emptyMap())

    data class RoomJoinRequest(
        val applicantDeviceId: String,
        val applicantIp: String,
        val roomId: String,
        val roomName: String,
        val callback: (Boolean) -> Unit
    )
    val activeRoomJoinRequest = MutableStateFlow<RoomJoinRequest?>(null)

    fun addDiscoveredRoom(room: IntercomRoom, hostIp: String) {
        val current = discoveredRooms.value.toMutableMap()
        current[room.roomId] = DiscoveredRoomInfo(room, hostIp, System.currentTimeMillis())
        discoveredRooms.value = current
    }

    fun cleanStaleRooms() {
        val now = System.currentTimeMillis()
        val filtered = discoveredRooms.value.filter { (_, info) ->
            now - info.lastSeenTimestamp <= 10000
        }
        if (filtered.size != discoveredRooms.value.size) {
            discoveredRooms.value = filtered
        }
    }

    fun createRoom(roomName: String, creatorId: String, myDevice: DeviceInfo): IntercomRoom {
        val newRoomId = "ROOM-" + (1000..9999).random()
        val room = IntercomRoom(
            roomId = newRoomId,
            roomName = roomName,
            creatorId = creatorId,
            members = listOf(myDevice)
        )
        val list = intercomRooms.value.toMutableList()
        list.add(room)
        intercomRooms.value = list
        currentRoom.value = room
        return room
    }

    fun leaveRoom(myDeviceId: String? = null) {
        val room = currentRoom.value
        if (room != null && myDeviceId != null && room.creatorId == myDeviceId) {
            broadcastRoomDestroyed()
        }
        currentRoom.value = null
        intercomRooms.value = emptyList()
        disconnectAllIntercomDevices()
    }

    fun broadcastRoomDestroyed() {
        val destroyMsg = org.json.JSONObject().apply {
            put("type", "WALKIE_TALKIE_ROOM_DESTROYED")
        }
        walkieTalkieSockets.forEach { (_, socket) ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (!socket.isClosed) {
                        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                        TcpTransferProtocol.sendJsonString(outStream, destroyMsg)
                    }
                } catch (e: Throwable) {}
            }
        }
    }

    fun sendRoomHeartbeat(myDeviceId: String) {
        val room = currentRoom.value ?: return
        if (room.creatorId != myDeviceId) return
        val heartbeatMsg = org.json.JSONObject().apply {
            put("type", "WALKIE_TALKIE_ROOM_HEARTBEAT")
            put("roomId", room.roomId)
            put("roomName", room.roomName)
        }
        walkieTalkieSockets.forEach { (_, socket) ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (!socket.isClosed) {
                        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                        TcpTransferProtocol.sendJsonString(outStream, heartbeatMsg)
                    }
                } catch (e: Throwable) {}
            }
        }
    }

    // Walkie-Talkie Multi-Device Session State
    val activeWalkieTalkieRequest = MutableStateFlow<WalkieTalkieRequest?>(null)
    val walkieTalkieConnectedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val walkieTalkieSockets = java.util.concurrent.ConcurrentHashMap<String, java.net.Socket>()
    val peerSpeakingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // Backwards compatibility single device accessors
    val walkieTalkieConnectedDevice = MutableStateFlow<DeviceInfo?>(null)
    val isPeerSpeaking = MutableStateFlow(false)
    val isSpeakingState = MutableStateFlow(false)
    val isMicLockedState = MutableStateFlow(false)
    val intercomState = MutableStateFlow(IntercomState.IDLE)
    val peerAudioLevel = MutableStateFlow(0f)
    val localAudioLevel = MutableStateFlow(0f)
    val isNoiseSuppressionEnabled = MutableStateFlow(true)
    val networkQualityRating = MutableStateFlow(5) // 1 to 5 stars

    fun isAnyPeerSpeaking(): Boolean = isPeerSpeaking.value || peerSpeakingStates.value.values.any { it }
    
    @Volatile
    var walkieTalkieSocket: java.net.Socket? = null

    @Synchronized
    fun addIntercomDevice(device: DeviceInfo, socket: java.net.Socket) {
        val current = walkieTalkieConnectedDevices.value.toMutableList()
        current.removeAll { it.deviceId == device.deviceId || it.ipAddress == device.ipAddress }
        current.add(device)
        walkieTalkieConnectedDevices.value = current
        walkieTalkieSockets[device.deviceId] = socket
        walkieTalkieSocket = socket
        walkieTalkieConnectedDevice.value = current.firstOrNull()
        intercomState.value = IntercomState.CONNECTED
    }

    @Synchronized
    fun removeIntercomDevice(deviceId: String) {
        val current = walkieTalkieConnectedDevices.value.toMutableList()
        current.removeAll { it.deviceId == deviceId }
        walkieTalkieConnectedDevices.value = current

        val socket = walkieTalkieSockets.remove(deviceId)
        try { socket?.close() } catch (e: Throwable) {}

        val speakingMap = peerSpeakingStates.value.toMutableMap()
        speakingMap.remove(deviceId)
        peerSpeakingStates.value = speakingMap

        val sharingMap = peerAudioSharingStates.value.toMutableMap()
        sharingMap.remove(deviceId)
        peerAudioSharingStates.value = sharingMap

        walkieTalkieConnectedDevice.value = current.firstOrNull()
        walkieTalkieSocket = walkieTalkieSockets.values.firstOrNull()
        isPeerSpeaking.value = speakingMap.values.any { it }

        if (current.isEmpty()) {
            intercomState.value = IntercomState.IDLE
            WalkieTalkieAudioManager.stopPlayback()
            WalkieTalkieAudioManager.stopRecording()
        }
    }

    @Synchronized
    fun setPeerSpeaking(deviceId: String, isSpeaking: Boolean) {
        val speakingMap = peerSpeakingStates.value.toMutableMap()
        speakingMap[deviceId] = isSpeaking
        peerSpeakingStates.value = speakingMap
        isPeerSpeaking.value = speakingMap.values.any { it }
    }

    @Synchronized
    fun setPeerAudioSharing(deviceId: String, isSharing: Boolean) {
        val sharingMap = peerAudioSharingStates.value.toMutableMap()
        sharingMap[deviceId] = isSharing
        peerAudioSharingStates.value = sharingMap
    }

    fun broadcastPeerSync(myDeviceId: String) {
        val peersArray = org.json.JSONArray()
        val connected = walkieTalkieConnectedDevices.value
        connected.forEach { dev ->
            val obj = org.json.JSONObject().apply {
                put("deviceId", dev.deviceId)
                put("ipAddress", dev.ipAddress)
            }
            peersArray.put(obj)
        }
        val myObj = org.json.JSONObject().apply {
            put("deviceId", myDeviceId)
            put("ipAddress", "")
        }
        peersArray.put(myObj)

        val syncMsg = org.json.JSONObject().apply {
            put("type", "WALKIE_TALKIE_SYNC_PEERS")
            put("fromDeviceId", myDeviceId)
            put("peers", peersArray)
        }

        walkieTalkieSockets.forEach { (id, socket) ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (!socket.isClosed) {
                        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                        TcpTransferProtocol.sendJsonString(outStream, syncMsg)
                    }
                } catch (e: Throwable) {
                    com.example.utils.AppLogger.error("MESH_SYNC", "Broadcast peer sync error for $id", e)
                }
            }
        }
    }

    fun autoConnectMeshPeer(
        context: android.content.Context,
        peerDevice: DeviceInfo,
        myDeviceId: String,
        scope: CoroutineScope
    ) {
        if (peerDevice.deviceId == myDeviceId) return
        if (walkieTalkieSockets.containsKey(peerDevice.deviceId)) return
        if (peerDevice.ipAddress.isBlank()) return

        scope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(peerDevice.ipAddress, TcpTransferProtocol.PORT), 6000)
                val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)

                val req = org.json.JSONObject().apply {
                    put("type", "WALKIE_TALKIE_REQUEST")
                    put("deviceId", myDeviceId)
                    put("isMeshAuto", true)
                }
                TcpTransferProtocol.sendJsonString(outStream, req)

                val resp = TcpTransferProtocol.receiveJsonString(inStream)
                if (resp?.optString("type") == "WALKIE_TALKIE_ACCEPT") {
                    val acceptedId = resp.optString("deviceId", peerDevice.deviceId)
                    val devInfo = DeviceInfo(acceptedId, peerDevice.ipAddress, lastSeenTimestamp = System.currentTimeMillis())

                    withContext(Dispatchers.Main) {
                        addIntercomDevice(devInfo, socket)
                        android.widget.Toast.makeText(context, "设备 $acceptedId 已加入对讲网络", android.widget.Toast.LENGTH_SHORT).show()
                    }

                    WalkieTalkieAudioManager.startPlayback()
                    broadcastPeerSync(myDeviceId)
                    if (isAudioSharingEnabled.value) {
                        WalkieTalkieAudioManager.broadcastAudioSharingState(true)
                    }

                    try {
                        while (true) {
                            val msg = TcpTransferProtocol.receiveJsonString(inStream) ?: break
                            val msgType = msg.optString("type")
                            if (msgType == "WALKIE_TALKIE_STATE") {
                                val isSpeaking = msg.optBoolean("isSpeaking", false)
                                setPeerSpeaking(acceptedId, isSpeaking)
                            } else if (msgType == "WALKIE_TALKIE_AUDIO_SHARING_STATE") {
                                val isSharing = msg.optBoolean("isAudioSharing", false)
                                setPeerAudioSharing(acceptedId, isSharing)
                            } else if (msgType == "WALKIE_TALKIE_AUDIO") {
                                val audioData = msg.optString("data", "")
                                if (audioData.isNotEmpty()) {
                                    WalkieTalkieAudioManager.playAudioData(audioData)
                                }
                            } else if (msgType == "WALKIE_TALKIE_SYNC_PEERS") {
                                val peersArr = msg.optJSONArray("peers")
                                if (peersArr != null) {
                                    for (i in 0 until peersArr.length()) {
                                        val pObj = peersArr.optJSONObject(i) ?: continue
                                        val pId = pObj.optString("deviceId")
                                        val pIp = pObj.optString("ipAddress")
                                        if (pId.isNotEmpty() && pId != myDeviceId && !walkieTalkieSockets.containsKey(pId) && pIp.isNotEmpty()) {
                                            autoConnectMeshPeer(context, DeviceInfo(pId, pIp, System.currentTimeMillis()), myDeviceId, scope)
                                        }
                                    }
                                }
                            } else if (msgType == "WALKIE_TALKIE_ROOM_DESTROYED") {
                                withContext(Dispatchers.Main) {
                                    if (currentRoom.value != null) {
                                        currentRoom.value = null
                                        disconnectAllIntercomDevices()
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
                        com.example.utils.AppLogger.error("MESH_LOOP", "Loop error for $acceptedId", e)
                    } finally {
                        withContext(Dispatchers.Main) {
                            removeIntercomDevice(acceptedId)
                            try { socket.close() } catch (ex: Throwable) {}
                            val curRoom = currentRoom.value
                            if (curRoom != null && curRoom.creatorId == acceptedId) {
                                currentRoom.value = null
                                disconnectAllIntercomDevices()
                                android.widget.Toast.makeText(context, "管理员已离线，房间已解散", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    socket.close()
                }
            } catch (e: Throwable) {
                com.example.utils.AppLogger.error("AUTO_MESH", "Auto connect failed to ${peerDevice.deviceId}", e)
            }
        }
    }

    @Synchronized
    fun disconnectAllIntercomDevices() {
        walkieTalkieSockets.forEach { (id, socket) ->
            try {
                if (!socket.isClosed) {
                    val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                    val msg = org.json.JSONObject().apply { put("type", "WALKIE_TALKIE_DISCONNECT") }
                    kotlinx.coroutines.runBlocking {
                        TcpTransferProtocol.sendJsonString(outStream, msg)
                    }
                    socket.close()
                }
            } catch (e: Throwable) {}
        }
        walkieTalkieSockets.clear()
        walkieTalkieConnectedDevices.value = emptyList()
        peerSpeakingStates.value = emptyMap()
        walkieTalkieConnectedDevice.value = null
        walkieTalkieSocket = null
        isPeerSpeaking.value = false
        intercomState.value = IntercomState.IDLE
        WalkieTalkieAudioManager.stopPlayback()
        WalkieTalkieAudioManager.stopRecording()
    }

    // Thread-safe set of accepted group IDs on receiver side
    val acceptedGroups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Active pairing request for incoming transfers
    val activePairingRequest = MutableStateFlow<PairingRequest?>(null)

    // Globally shared list of active/all tasks in the queue
    val allQueueTasks = MutableStateFlow<List<com.example.model.TransmissionTask>>(emptyList())

    // Globally shared list of discovered devices
    val discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())

    // Transparency control (0.2f to 1.0f)
    val floatingAlpha = MutableStateFlow(1.0f)

    // Mini-Window diameter (40dp to 120dp, default 60dp)
    val floatingSize = MutableStateFlow(60f)
    
    // Speed limit (-1L or <= 0 means unlimited, positive values specify max bytes/sec)
    val speedLimitBytesPerSec = MutableStateFlow(-1L) // -1L = unlimited
    private var throttleStartTime = 0L
    private var throttleBytesSent = 0L

    // Raw speed estimation in bytes per sec
    val currentSpeedBytesPerSec = MutableStateFlow(0L)
    
    // Max concurrency limit (user configurable from Settings)
    val maxConcurrencyLimit = MutableStateFlow(3)
    
    // Discovered devices count
    val discoveredDevicesCount = MutableStateFlow(0)
    
    // Thread-safe set of active task IDs
    val activeTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    data class ActiveTaskProgress(
        val fileName: String,
        val totalSize: Long,
        val bytesTransferred: Long,
        val isSending: Boolean
    )

    val activeTasksMap = java.util.concurrent.ConcurrentHashMap<Int, ActiveTaskProgress>()

    private var lastStateFlowUpdateTime = 0L

    fun updateTaskProgress(taskId: Int, fileName: String, totalSize: Long, bytesTransferred: Long, isSending: Boolean) {
        activeTasksMap[taskId] = ActiveTaskProgress(fileName, totalSize, bytesTransferred, isSending)
        activeTaskIds.add(taskId)
        isUploadingOrDownloading.value = true
        
        val now = System.currentTimeMillis()
        if (now - lastStateFlowUpdateTime < 250 && bytesTransferred < totalSize) {
            return
        }
        lastStateFlowUpdateTime = now
        
        val activeList = activeTasksMap.values.toList()
        if (activeList.isEmpty()) {
            activeFileName.value = ""
            activeFileSize.value = 0L
            activeBytesTransferred.value = 0L
            activeProgress.value = 0f
            this.isSending.value = isSending
        } else if (activeList.size == 1) {
            val single = activeList.first()
            activeFileName.value = single.fileName
            activeFileSize.value = single.totalSize
            activeBytesTransferred.value = single.bytesTransferred
            activeProgress.value = if (single.totalSize > 0) single.bytesTransferred.toFloat() / single.totalSize.toFloat() else 0f
            this.isSending.value = single.isSending
        } else {
            val sendingCount = activeList.count { it.isSending }
            val receivingCount = activeList.size - sendingCount
            val totalTasks = activeList.size
            activeFileName.value = if (sendingCount > 0 && receivingCount > 0) {
                "传输中: ${sendingCount}发 / ${receivingCount}收"
            } else if (sendingCount > 0) {
                "正在发送 ${totalTasks} 个文件"
            } else {
                "正在接收 ${totalTasks} 个文件"
            }
            
            val sumTotalBytes = activeList.sumOf { it.totalSize }
            val sumTransferred = activeList.sumOf { it.bytesTransferred }
            activeFileSize.value = sumTotalBytes
            activeBytesTransferred.value = sumTransferred
            activeProgress.value = if (sumTotalBytes > 0) sumTransferred.toFloat() / sumTotalBytes.toFloat() else 0f
            this.isSending.value = sendingCount >= receivingCount
        }
    }

    fun removeActiveTask(taskId: Int) {
        activeTasksMap.remove(taskId)
        activeTaskIds.remove(taskId)
        isUploadingOrDownloading.value = activeTaskIds.isNotEmpty()
        
        val activeList = activeTasksMap.values.toList()
        if (activeList.isEmpty()) {
            activeFileName.value = ""
            activeFileSize.value = 0L
            activeBytesTransferred.value = 0L
            activeProgress.value = 0f
        } else if (activeList.size == 1) {
            val single = activeList.first()
            activeFileName.value = single.fileName
            activeFileSize.value = single.totalSize
            activeBytesTransferred.value = single.bytesTransferred
            activeProgress.value = if (single.totalSize > 0) single.bytesTransferred.toFloat() / single.totalSize.toFloat() else 0f
            this.isSending.value = single.isSending
        } else {
            val sendingCount = activeList.count { it.isSending }
            val receivingCount = activeList.size - sendingCount
            val totalTasks = activeList.size
            activeFileName.value = if (sendingCount > 0 && receivingCount > 0) {
                "传输中: ${sendingCount}发 / ${receivingCount}收"
            } else if (sendingCount > 0) {
                "正在发送 ${totalTasks} 个文件"
            } else {
                "正在接收 ${totalTasks} 个文件"
            }
            
            val sumTotalBytes = activeList.sumOf { it.totalSize }
            val sumTransferred = activeList.sumOf { it.bytesTransferred }
            activeFileSize.value = sumTotalBytes
            activeBytesTransferred.value = sumTransferred
            activeProgress.value = if (sumTotalBytes > 0) sumTransferred.toFloat() / sumTotalBytes.toFloat() else 0f
            this.isSending.value = sendingCount >= receivingCount
        }
    }

    // Active tasks details
    val activeProgress = MutableStateFlow(0f)         // 0.0f to 1.0f
    val activeFileName = MutableStateFlow("")
    val activeFileSize = MutableStateFlow(0L)
    val activeBytesTransferred = MutableStateFlow(0L)
    val speedText = MutableStateFlow("0 KB/s")
    val remainingTimeText = MutableStateFlow("计算中...")
    val isUploadingOrDownloading = MutableStateFlow(false)
    val isSending = MutableStateFlow(true) // true for uploading, false for downloading
    
    // Total remaining filescount including active + waiting queue
    val remainingFilesCount = MutableStateFlow(0)
    
    // State of the floating window
    val collapsedState = MutableStateFlow(true) // true = simple bubble, false = detailed menu
    val isDockedState = MutableStateFlow(false)  // true = docked to edge as a thin strip bar
    val dockSideState = MutableStateFlow(DockSide.LEFT)

    /**
     * Self-correcting rate limiting to throttle chunk-based socket writes or reads.
     * To be called inside background reading/writing loops (such as streamFile and receiveFile).
     */
    suspend fun throttle(chunkSize: Int) {
        val limit = speedLimitBytesPerSec.value
        if (limit <= 0) return // -1L or <=0 means unlimited speed, return immediately

        val now = System.currentTimeMillis()
        if (throttleStartTime == 0L || now - throttleStartTime > 1000) {
            throttleStartTime = now
            throttleBytesSent = 0L
        }

        throttleBytesSent += chunkSize
        val expectedTimeMs = (throttleBytesSent.toDouble() / limit.toDouble() * 1000.0).toLong()
        val actualTimeMs = System.currentTimeMillis() - throttleStartTime
        val delayMs = expectedTimeMs - actualTimeMs

        if (delayMs > 0) {
            kotlinx.coroutines.delay(delayMs.coerceAtMost(1000L))
        }
    }

    fun getOrCreateDeviceId(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("ptf_prefs", android.content.Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = buildString {
                append((1..5).map { 
                    if(kotlin.random.Random.nextBoolean()) kotlin.random.Random.nextInt(0, 10).toString() 
                    else ('A'..'Z').random() 
                }.joinToString(""))
            }
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
}
