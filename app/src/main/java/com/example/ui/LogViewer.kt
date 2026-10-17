package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.utils.AppLogger
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("正在加载日志...") }
    val scrollState = rememberScrollState()

    // Load logs on enter
    LaunchedEffect(Unit) {
        logText = AppLogger.readLogs()
        // Wait minor delay and scroll to bottom of logs to show the latest entries
        kotlinx.coroutines.delay(100)
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Current Log Path (Clickable to open folder)
                    Card(
                        onClick = {
                            openLogsLocation(context)
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "Open Folder",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "日志路径 (点击在系统文件中打开):",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = AppLogger.getLogFilePath(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Log Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val success = exportLogsToDownload(context)
                                if (success) {
                                    logText = AppLogger.readLogs()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("导出日志", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                shareLogFile(context)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("分享日志", fontSize = 13.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("PTF App Logs", logText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "日志内容已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("复制全部", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                AppLogger.clearLogs()
                                logText = AppLogger.readLogs()
                                Toast.makeText(context, "日志已清理！", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("清空日志", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1E1E1E)) // Professional dark background for terminal log output
                .padding(16.dp)
        ) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = logText,
                        color = Color(0xFFE0E0E0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Handles exporting log file to the public /Downloads folder for simple user access.
 */
private fun exportLogsToDownload(context: Context): Boolean {
    try {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        val targetFile = File(downloadDir, "ptf_app_logs.txt")
        val currentLogs = AppLogger.readLogs()
        targetFile.writeText(currentLogs)
        
        Toast.makeText(context, "日志成功导出至: /Download/ptf_app_logs.txt", Toast.LENGTH_LONG).show()
        AppLogger.info("SYSTEM", "Logs exported to public Download directory: ${targetFile.absolutePath}")
        return true
    } catch (e: Exception) {
        val errorMsg = "导出日志失败: ${e.localizedMessage}"
        AppLogger.error("SYSTEM", errorMsg, e)
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
        return false
    }
}

/**
 * Share log file dynamically via ACTION_SEND.
 */
private fun shareLogFile(context: Context) {
    try {
        val logFile = AppLogger.getLogFile()
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(context, "日志文件尚未创建或为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Dynamically copy log file to external cache so standard file provider can access it
        val externalCacheFile = File(context.externalCacheDir, "ptf_logs_shared.txt")
        logFile.copyTo(externalCacheFile, overwrite = true)

        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                externalCacheFile
            )
        } else {
            Uri.fromFile(externalCacheFile)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "发送 PTF 运行日志"))
        AppLogger.info("SYSTEM", "Logs shared successfully from path: ${externalCacheFile.absolutePath}")
    } catch (e: Exception) {
        val errorMsg = "分享日志失败: ${e.localizedMessage}"
        AppLogger.error("SYSTEM", errorMsg, e)
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Attempts to launch the native Files application or Storage explorer matching Download/Log folders.
 */
private fun openLogsLocation(context: Context) {
    try {
        val logFile = AppLogger.getLogFile()
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(context, "日志文件尚未创建或为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )
        } else {
            Uri.fromFile(logFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "选择打开日志文件的应用"))
        AppLogger.info("SYSTEM", "Opened actual log file in system viewer: ${logFile.absolutePath}")
    } catch (e: Exception) {
        try {
            // Fallback: ACTION_GET_CONTENT
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(fallbackIntent, "选择文件管理器"))
            AppLogger.info("SYSTEM", "Fallback to standard ACTION_GET_CONTENT chooser due to: ${e.message}")
        } catch (ex: Exception) {
            Toast.makeText(context, "无法启动系统文件管理器: ${ex.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
