package com.example.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "PTF_Logger"
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            // Store logs in public external files directory under /logs subdirectory
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            logFile = File(logDir, "ptf_app_logs.txt")
            
            // Log initial environment variables and device info
            info("SYSTEM", "Logger initialized. Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            
            // Intercept uncaught exceptions across all threads
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                error("CRASH", "FATAL CRASH in thread: ${thread.name} - Exception: ${throwable.message}", throwable)
                // Fallback to default system crash handler (will show the standard crashed dialog / exit app)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AppLogger", e)
        }
    }

    fun getLogFile(): File? = logFile

    fun getLogFilePath(): String {
        return logFile?.absolutePath ?: "未知路径"
    }

    @Synchronized
    private fun write(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timeString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "[$timeString] [$level/$tag]: $message\n$sw\n"
        } else {
            "[$timeString] [$level/$tag]: $message\n"
        }

        // Print to Android Logcat
        when (level) {
            "ERROR" -> Log.e(TAG, "[$tag]: $message", throwable)
            "WARN" -> Log.w(TAG, "[$tag]: $message", throwable)
            else -> Log.i(TAG, "[$tag]: $message", throwable)
        }

        // Write to log file asynchronously or synchronously
        try {
            logFile?.let { file ->
                val writer = FileWriter(file, true)
                writer.write(logLine)
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    fun info(tag: String, message: String) {
        write("INFO", tag, message)
    }

    fun warn(tag: String, message: String) {
        write("WARN", tag, message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        write("ERROR", tag, message, throwable)
    }

    fun readLogs(): String {
        return try {
            logFile?.let { file ->
                if (file.exists()) {
                    val logs = file.readText()
                    // Cap log size if super massive to avoid Compose OOM
                    if (logs.length > 500_000) {
                        "[日志文件过长，截取后半部分显示...]\n\n" + logs.substring(logs.length - 300_000)
                    } else {
                        logs
                    }
                } else {
                    "日志文件为空或尚未创建。"
                }
            } ?: "日志文件未初始化。"
        } catch (e: Exception) {
            "读取日志失败: ${e.localizedMessage}"
        }
    }

    fun clearLogs() {
        try {
            logFile?.let { file ->
                if (file.exists()) {
                    file.writeText("")
                }
            }
            info("SYSTEM", "Logs cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
