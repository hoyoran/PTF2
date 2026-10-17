package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppFilePickerDialog(
    onDismissRequest: () -> Unit,
    onConfirmSelection: (List<Uri>) -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                // Below Android 11, we rely on normal storage permission checks
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!hasPermission) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "需要文件访问权限",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                "为了不用依赖系统文件选择，能够更安全、便捷、快速地批量发送各类文件，PTF 需要获取『所有文件访问权限』。请点击下方按钮前往设置页面授权。"
                            } else {
                                "请授予读取存储权限以便进行文件浏览和选取传输。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("开启完全访问权限")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = onDismissRequest) {
                            Text("取消", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    FileExplorerContent(
                        onBack = onDismissRequest,
                        onSend = { uris ->
                            onConfirmSelection(uris)
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerContent(
    onBack: () -> Unit,
    onSend: (List<Uri>) -> Unit
) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(rootDir) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }

    BackHandler(enabled = true) {
        if (currentDir != rootDir && currentDir.parentFile != null) {
            currentDir = currentDir.parentFile!!
        } else {
            onBack()
        }
    }

    val filesAndFolders = remember(currentDir) {
        try {
            val list = currentDir.listFiles()?.toList() ?: emptyList()
            list.filter { !it.name.startsWith(".") }.sortedWith { a, b ->
                if (a.isDirectory && !b.isDirectory) -1
                else if (!a.isDirectory && b.isDirectory) 1
                else a.name.lowercase().compareTo(b.name.lowercase())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    val totalSizeText = remember(selectedFiles) {
        var sizeBytes = 0L
        selectedFiles.forEach { sizeBytes += it.length() }
        formatBytes(sizeBytes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("本地文件管理器", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = getRelativePath(rootDir, currentDir),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentDir != rootDir && currentDir.parentFile != null) {
                            currentDir = currentDir.parentFile!!
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Select All / Deselect All
                    val visibleFiles = filesAndFolders.filter { !it.isDirectory }
                    val allSelected = visibleFiles.isNotEmpty() && visibleFiles.all { selectedFiles.contains(it) }
                    
                    if (visibleFiles.isNotEmpty()) {
                        TextButton(onClick = {
                            val newSet = selectedFiles.toMutableSet()
                            if (allSelected) {
                                newSet.removeAll(visibleFiles.toSet())
                            } else {
                                newSet.addAll(visibleFiles)
                            }
                            selectedFiles = newSet
                        }) {
                            Text(if (allSelected) "取消全选" else "全选")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "已选: ${selectedFiles.size} 个文件",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = totalSizeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onBack) {
                                Text("返回")
                            }
                            Button(
                                onClick = {
                                    if (selectedFiles.isNotEmpty()) {
                                        val uris = selectedFiles.map { Uri.fromFile(it) }
                                        onSend(uris)
                                    }
                                },
                                enabled = selectedFiles.isNotEmpty(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("立即传输")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentDir,
            transitionSpec = {
                val isParentOfTarget = initialState.parentFile?.absolutePath == targetState.absolutePath
                if (isParentOfTarget) {
                    (slideInHorizontally { width -> -width / 2 } + fadeIn(animationSpec = tween(220)))
                        .togetherWith(slideOutHorizontally { width -> width / 2 } + fadeOut(animationSpec = tween(220)))
                } else {
                    (slideInHorizontally { width -> width / 2 } + fadeIn(animationSpec = tween(220)))
                        .togetherWith(slideOutHorizontally { width -> -width / 2 } + fadeOut(animationSpec = tween(220)))
                }
            },
            label = "folder_navigation_transition",
            modifier = Modifier.fillMaxSize()
        ) { dir ->
            val filesAndFoldersForDir = remember(dir) {
                try {
                    val list = dir.listFiles()?.toList() ?: emptyList()
                    list.filter { !it.name.startsWith(".") }.sortedWith { a, b ->
                        if (a.isDirectory && !b.isDirectory) -1
                        else if (!a.isDirectory && b.isDirectory) 1
                        else a.name.lowercase().compareTo(b.name.lowercase())
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Parent Folder level entry if not root
                if (dir != rootDir && dir.parentFile != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir.parentFile!! }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "返回上一级 (..)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (filesAndFoldersForDir.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "当前目录为空哦 ~",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    items(filesAndFoldersForDir, key = { file -> file.absolutePath }) { file ->
                        val isChecked = selectedFiles.contains(file)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .clickable {
                                    if (file.isDirectory) {
                                        currentDir = file
                                    } else {
                                        val newSet = selectedFiles.toMutableSet()
                                        if (isChecked) newSet.remove(file) else newSet.add(file)
                                        selectedFiles = newSet
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (file.isDirectory) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                val isImage = !file.isDirectory && file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
                                if (isImage) {
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                            .data(file)
                                            .crossfade(true)
                                            .size(120) // Limit thumbnail dimensions for optimal performance
                                            .build(),
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    val icon = if (file.isDirectory) Icons.Default.Folder else run {
                                        val extension = file.extension.lowercase()
                                        when {
                                            extension in listOf("mp3", "wav", "ogg", "flac", "m4a", "aac") -> Icons.Default.MusicNote
                                            extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "3gp") -> Icons.Default.Movie
                                            extension in listOf("txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx") -> Icons.Default.Description
                                            extension in listOf("zip", "rar", "7z", "tar", "gz") -> Icons.Default.FolderZip
                                            extension == "apk" -> Icons.Default.Android
                                            else -> Icons.Default.InsertDriveFile
                                        }
                                    }
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                                val descStr = if (file.isDirectory) {
                                    val itemsCount = file.listFiles()?.filter { !it.name.startsWith(".") }?.size ?: 0
                                    "$dateStr · $itemsCount 个项目"
                                } else {
                                    "$dateStr · ${formatBytes(file.length())}"
                                }
                                Text(
                                    text = descStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!file.isDirectory) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        val newSet = selectedFiles.toMutableSet()
                                        if (checked) newSet.add(file) else newSet.remove(file)
                                        selectedFiles = newSet
                                    }
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getRelativePath(rootDir: File, currentDir: File): String {
    val rootPath = rootDir.absolutePath
    val currentPath = currentDir.absolutePath
    return if (currentPath.startsWith(rootPath)) {
        val rel = currentPath.substring(rootPath.length)
        if (rel.isEmpty()) "/" else rel
    } else {
        currentPath
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppFolderPickerDialog(
    initialPath: String = "",
    onDismissRequest: () -> Unit,
    onConfirmFolder: (String) -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "需要文件访问权限",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "浏览与设置存储文件夹需要获取文件存储权限。请点击下方按钮前往授权。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("开启访问权限")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onDismissRequest) {
                        Text("取消", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                FolderExplorerContent(
                    initialPath = initialPath,
                    onBack = onDismissRequest,
                    onSelectFolder = { selectedFolderPath ->
                        onConfirmFolder(selectedFolderPath)
                        onDismissRequest()
                    }
                )
            }
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderExplorerContent(
    initialPath: String,
    onBack: () -> Unit,
    onSelectFolder: (String) -> Unit
) {
    val rootDir = Environment.getExternalStorageDirectory()
    val startDir = remember(initialPath) {
        val initialFile = if (initialPath.isNotEmpty()) File(initialPath) else null
        if (initialFile != null && initialFile.exists() && initialFile.isDirectory) {
            initialFile
        } else {
            rootDir
        }
    }

    var currentDir by remember { mutableStateOf(startDir) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (currentDir != rootDir && currentDir.parentFile != null) {
            currentDir = currentDir.parentFile!!
        } else {
            onBack()
        }
    }

    val subFolders = remember(currentDir) {
        try {
            val list = currentDir.listFiles()?.toList() ?: emptyList()
            list.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedWith { a, b -> a.name.lowercase().compareTo(b.name.lowercase()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("选择存储文件夹", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = getRelativePath(rootDir, currentDir),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentDir != rootDir && currentDir.parentFile != null) {
                            currentDir = currentDir.parentFile!!
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "当前选定路径:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentDir.absolutePath,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onBack) {
                                Text("取消")
                            }
                            Button(
                                onClick = {
                                    var formattedPath = currentDir.absolutePath
                                    if (!formattedPath.endsWith("/")) {
                                        formattedPath += "/"
                                    }
                                    onSelectFolder(formattedPath)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("选择此文件夹")
                            }
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (currentDir != rootDir && currentDir.parentFile != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = currentDir.parentFile!! }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "返回上一级 (..)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (subFolders.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "无子文件夹，可直接选择当前目录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    items(subFolders, key = { folder -> folder.absolutePath }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = folder }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.name,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val itemsCount = folder.listFiles()?.filter { !it.name.startsWith(".") }?.size ?: 0
                                Text(
                                    text = "$itemsCount 个项目",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newFolderName.trim().isNotEmpty()) {
                        val newDir = File(currentDir, newFolderName.trim())
                        if (!newDir.exists()) {
                            newDir.mkdirs()
                        }
                        currentDir = newDir
                        showNewFolderDialog = false
                    }
                }) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
