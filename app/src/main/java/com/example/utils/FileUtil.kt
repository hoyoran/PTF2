package com.example.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object FileUtil {
    suspend fun getFileHash(context: Context, uri: Uri): String {
        try {
            val inputStream: InputStream? = if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                if (file.exists()) FileInputStream(file) else null
            } else {
                context.contentResolver.openInputStream(uri)
            }

            inputStream?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    digest.update(buffer, 0, bytesRead)
                }
                val bytes = digest.digest()
                return bytes.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    fun getFileInfo(context: Context, uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.exists()) {
                return Pair(file.name, file.length())
            }
        }
        var name = "unknown_file"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback for file paths
        if (name == "unknown_file" || size == 0L) {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    return Pair(file.name, file.length())
                }
            }
        }
        return Pair(name, size)
    }

    fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/x-ms-bmp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/x-wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/x-flac"
            "m4a" -> "audio/x-m4a"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> {
                val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                mimeType ?: "application/octet-stream"
            }
        }
    }

    fun scanFileToSystemMedia(context: Context, file: File) {
        val path = file.absolutePath
        val mimeType = getMimeType(file)
        try {
            android.media.MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(path),
                arrayOf(mimeType)
            ) { scannedPath, uri ->
                com.example.utils.AppLogger.info("MEDIA_SCANNER", "Scanned $scannedPath with mime $mimeType to MediaStore. Uri: $uri")
            }
        } catch (e: Exception) {
            com.example.utils.AppLogger.error("MEDIA_SCANNER", "Failed to scan file with MediaScannerConnection", e)
        }

        try {
            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val contentUri = Uri.fromFile(file)
            mediaScanIntent.data = contentUri
            context.sendBroadcast(mediaScanIntent)
        } catch (e: Exception) {
             // ignore fallback issues
        }
    }

    fun canWriteToDirectory(dir: File): Boolean {
        try {
            if (!dir.exists()) {
                if (!dir.mkdirs()) return false
            }
            val testFile = File(dir, ".ptf_write_test_${System.currentTimeMillis()}")
            testFile.outputStream().use { 
                it.write(1)
            }
            testFile.delete()
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
