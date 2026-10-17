package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

object TcpTransferProtocol {
    val PORT = 19433
    const val MAX_JSON_SIZE = 5 * 1024 * 1024 // 5 MB max payload for control JSONs

    suspend fun sendJsonString(outStream: OutputStream, json: JSONObject) = withContext(Dispatchers.IO) {
        val data = json.toString().toByteArray(StandardCharsets.UTF_8)
        val sizeString = String.format("%08d", data.size).toByteArray(StandardCharsets.UTF_8)
        outStream.write(sizeString)
        outStream.write(data)
        outStream.flush()
    }

    suspend fun receiveJsonString(inStream: InputStream): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val sizeBytes = ByteArray(8)
            var readSize = 0
            while (readSize < 8) {
                currentCoroutineContext().ensureActive()
                val res = inStream.read(sizeBytes, readSize, 8 - readSize)
                if (res == -1) return@withContext null
                readSize += res
            }
            val sizeStr = String(sizeBytes, StandardCharsets.UTF_8).trim()
            val dataSize = sizeStr.toIntOrNull() ?: return@withContext null
            if (dataSize <= 0 || dataSize > MAX_JSON_SIZE) {
                return@withContext null // Protect against invalid/malicious packet sizes
            }

            val dataBytes = try {
                ByteArray(dataSize)
            } catch (oome: OutOfMemoryError) {
                return@withContext null
            }
            var readData = 0
            while (readData < dataSize) {
                currentCoroutineContext().ensureActive()
                val res = inStream.read(dataBytes, readData, dataSize - readData)
                if (res == -1) return@withContext null
                readData += res
            }
            val jsonStr = String(dataBytes, StandardCharsets.UTF_8)
            return@withContext try {
                JSONObject(jsonStr)
            } catch (e: Throwable) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
