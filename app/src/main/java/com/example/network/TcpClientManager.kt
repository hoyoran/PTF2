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
import java.io.InputStream
import java.net.Socket

class TcpClientManager(
    private val context: Context,
    private val repository: TransmissionRepository
) {
    private data class SocketHolder(
        val socket: Socket,
        val inStream: java.io.InputStream,
        val outStream: java.io.OutputStream
    )

    private fun connectAndInitSocket(deviceIp: String): SocketHolder {
        val socket = Socket().apply {
            sendBufferSize = 1024 * 1024
            receiveBufferSize = 1024 * 1024
            tcpNoDelay = true
        }
        socket.connect(java.net.InetSocketAddress(deviceIp, TcpTransferProtocol.PORT), 10000)
        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
        val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)
        return SocketHolder(socket, inStream, outStream)
    }

    suspend fun sendFile(
        deviceIp: String,
        deviceId: String,
        myDeviceId: String,
        uri: Uri,
        connectionId: String
    ) = withContext(Dispatchers.IO) {
        var taskId = -1
        var socket: Socket? = null
        try {
            val (name, size) = FileUtil.getFileInfo(context, uri)
            // Compute hash only for small files synchronously to prevent main thread/socket setup stall on large files
            val hash = if (size < 5 * 1024 * 1024) FileUtil.getFileHash(context, uri) else null
            
            val task = TransmissionTask(
                fileUri = uri.toString(),
                fileName = name,
                fileSize = size,
                offset = 0L,
                deviceId = deviceId,
                connectionId = connectionId,
                status = TransmissionStatus.CONNECTING,
                isSender = true,
                fileHash = hash
            )
            taskId = repository.insert(task).toInt()
            
            val holder = connectAndInitSocket(deviceIp)
            socket = holder.socket
            
            val request = JSONObject().apply {
                put("type", "PAIRING_REQUEST")
                put("deviceId", myDeviceId)
                put("connectionId", connectionId)
                put("fileName", name)
                put("fileSize", size)
                hash?.let { put("fileHash", it) }
            }
            
            TcpTransferProtocol.sendJsonString(holder.outStream, request)
            
            val response = TcpTransferProtocol.receiveJsonString(holder.inStream)
            if (response?.optString("type") == "PAIRING_ACCEPT") {
                repository.updateTaskStatus(taskId, TransmissionStatus.SENDING)
                streamFile(socket, uri, taskId, 0L, size)
            } else {
                repository.updateTaskStatus(taskId, TransmissionStatus.FAILED)
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_CLIENT", "Socket connection or transmission setup failed", e)
            if (taskId != -1) {
                repository.updateTaskStatus(taskId, TransmissionStatus.WAITING_RECONNECT)
            }
        } finally {
            try { socket?.close() } catch (e: Throwable) {}
        }
    }

    suspend fun startOutgoingTask(deviceIp: String, task: TransmissionTask, myDeviceId: String) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            repository.updateTaskStatus(task.id, TransmissionStatus.CONNECTING)
            val holder = connectAndInitSocket(deviceIp)
            socket = holder.socket
            
            val request = org.json.JSONObject().apply {
                put("type", "PAIRING_REQUEST")
                put("deviceId", myDeviceId)
                put("connectionId", task.connectionId)
                put("fileName", task.fileName)
                put("fileSize", task.fileSize)
                task.fileHash?.let { put("fileHash", it) }
                task.groupId?.let { put("groupId", it) }
                put("groupFileCount", task.groupFileCount)
            }
            
            TcpTransferProtocol.sendJsonString(holder.outStream, request)
            
            val response = TcpTransferProtocol.receiveJsonString(holder.inStream)
            val respType = response?.optString("type")
            if (respType == "PAIRING_ACCEPT") {
                repository.updateTaskStatus(task.id, TransmissionStatus.SENDING)
                streamFile(socket, Uri.parse(task.fileUri), task.id, 0L, task.fileSize)
            } else if (respType == "BUSY_REJECT") {
                repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
            } else {
                repository.updateTaskStatus(task.id, TransmissionStatus.FAILED)
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_CLIENT", "Socket connection or transmission setup failed for existing task ${task.id}", e)
            repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
        } finally {
            try { socket?.close() } catch (e: Throwable) {}
        }
    }

    suspend fun resumeTask(deviceIp: String, task: TransmissionTask, myDeviceId: String) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            repository.updateTaskStatus(task.id, TransmissionStatus.CONNECTING)
            val holder = connectAndInitSocket(deviceIp)
            socket = holder.socket
            
            val request = JSONObject().apply {
                put("type", "RESUME_REQUEST")
                put("deviceId", myDeviceId)
                put("connectionId", task.connectionId)
                put("offset", task.offset)
            }
            TcpTransferProtocol.sendJsonString(holder.outStream, request)
            
            val response = TcpTransferProtocol.receiveJsonString(holder.inStream)
            if (response?.optString("type") == "RESUME_ACCEPT") {
                val serverOffset = response.optLong("offset")
                repository.updateTaskStatus(task.id, TransmissionStatus.RESUMING)
                repository.updateTaskOffset(task.id, serverOffset)
                
                repository.updateTaskStatus(task.id, TransmissionStatus.SENDING)
                streamFile(socket, Uri.parse(task.fileUri), task.id, serverOffset, task.fileSize)
            } else if (response?.optString("type") == "BUSY_REJECT") {
                repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
            } else {
                repository.updateTaskStatus(task.id, TransmissionStatus.FAILED)
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_CLIENT", "General failure in resumeTask", e)
            repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
        } finally {
            try { socket?.close() } catch (e: Throwable) {}
        }
    }

    private suspend fun streamFile(socket: Socket, uri: Uri, taskId: Int, offset: Long, totalSize: Long) {
        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
        var inputStream: InputStream? = null
        try {
            val currentTaskInfo = TransferStateManager.allQueueTasks.value.find { it.id == taskId }
                ?: repository.getTaskById(taskId)
            val name = currentTaskInfo?.fileName ?: "unknown_file"
            
            TransferStateManager.updateTaskProgress(taskId, name, totalSize, offset, true)

            val stream: InputStream? = if (uri.scheme == "file") {
                val file = java.io.File(uri.path ?: "")
                if (file.exists()) java.io.FileInputStream(file) else null
            } else {
                context.contentResolver.openInputStream(uri)
            }

            if (stream == null) {
                repository.updateTaskStatus(taskId, TransmissionStatus.FAILED)
                TransferStateManager.removeActiveTask(taskId)
                return
            }

            java.io.BufferedInputStream(stream, 262144).use { streamInstance ->
                inputStream = streamInstance
                streamInstance.skip(offset)
                val buffer = ByteArray(131072)
                var read: Int
                var currentOffset = offset
                var lastCheckTime = System.currentTimeMillis()
                var lastDbUpdateTime = 0L
                
                while (streamInstance.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                    currentOffset += read
                    
                    TransferStateManager.updateTaskProgress(taskId, name, totalSize, currentOffset, true)
                    
                    TransferStateManager.throttle(read)
                    
                    val now = System.currentTimeMillis()
                    if (now - lastDbUpdateTime > 2000) {
                        repository.updateTaskOffset(taskId, currentOffset)
                        lastDbUpdateTime = now
                    }
                    
                    if (now - lastCheckTime > 500) {
                        lastCheckTime = now
                        if (!TransferStateManager.activeTaskIds.contains(taskId)) {
                            return
                        }
                        val inMemTask = TransferStateManager.allQueueTasks.value.find { it.id == taskId }
                        if (inMemTask != null && (inMemTask.status == TransmissionStatus.PAUSED || inMemTask.status == TransmissionStatus.FAILED)) {
                            return
                        }
                    }
                }
                outStream.flush()
                repository.updateTaskOffset(taskId, currentOffset)
                if (TransferStateManager.activeTaskIds.contains(taskId)) {
                    repository.updateTaskStatus(taskId, TransmissionStatus.SUCCESS)
                }
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("TCP_CLIENT", "General failure in streamFile", e)
            val inMemTask = TransferStateManager.allQueueTasks.value.find { it.id == taskId }
            if (inMemTask == null || (inMemTask.status != TransmissionStatus.PAUSED && inMemTask.status != TransmissionStatus.FAILED)) {
                repository.updateTaskStatus(taskId, TransmissionStatus.WAITING_RECONNECT)
            }
        } finally {
            try { inputStream?.close() } catch (e: Throwable) {}
            TransferStateManager.removeActiveTask(taskId)
        }
    }
}
