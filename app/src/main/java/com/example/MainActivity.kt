package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.TransmissionStatus
import com.example.model.TransmissionTask
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.example.network.DeviceInfo
import com.example.ui.theme.AppTheme
import com.example.viewmodel.MainViewModel
import android.content.Intent
import android.Manifest
import android.os.Build
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.text.withStyle

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    val triggerFilePickerState = mutableStateOf(false)
    val sharedUrisState = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("LAUNCH_FILE_PICKER", false) == true) {
            triggerFilePickerState.value = true
        }
        handleShareIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themeColorHex by viewModel.themeColor.collectAsState()
            val appLanguage by viewModel.appLanguage.collectAsState()
            AppTheme(themeColorHex = themeColorHex) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.example.utils.LocalAppLanguage provides appLanguage
                ) {
                    PtfApp(viewModel, this)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("LAUNCH_FILE_PICKER", false) == true) {
            triggerFilePickerState.value = true
        }
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        if (android.content.Intent.ACTION_SEND == action && type != null) {
            val uri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
            if (uri != null) {
                sharedUrisState.value = listOf(uri)
            }
        } else if (android.content.Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris = intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM)
            if (uris != null) {
                sharedUrisState.value = uris.filterNotNull()
            }
        }
    }

    fun clearSharedUris() {
        sharedUrisState.value = null
        try {
            intent?.removeExtra(android.content.Intent.EXTRA_STREAM)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val prefs = getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        val isFloatingEnabled = prefs.getBoolean("floating_keepalive_enabled", false)
        if (isFloatingEnabled && android.provider.Settings.canDrawOverlays(this)) {
            val mode = prefs.getString("floating_mode", com.example.network.TransferForegroundService.FLOATING_TYPE_TRANSFER)
                ?: com.example.network.TransferForegroundService.FLOATING_TYPE_TRANSFER
            val serviceIntent = android.content.Intent(this, com.example.network.TransferForegroundService::class.java).apply {
                action = com.example.network.TransferForegroundService.ACTION_START_FLOATING
                putExtra(com.example.network.TransferForegroundService.EXTRA_FLOATING_TYPE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PtfApp(viewModel: MainViewModel = viewModel(), activity: MainActivity? = null) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val myDeviceId by viewModel.myDeviceId.collectAsState()
    val tasks by viewModel.allTasks.collectAsState()
    val pairingRequest by viewModel.pairingRequest.collectAsState()
    val activeWalkieTalkieRequest by com.example.network.TransferStateManager.activeWalkieTalkieRequest.collectAsState()
    val storagePath by viewModel.storagePath.collectAsState()

    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    
    var showFullSettingsScreen by remember { mutableStateOf(false) }
    var showWalkieTalkieScreen by remember { mutableStateOf(false) }

    LaunchedEffect(activity?.intent) {
        if (activity?.intent?.getBooleanExtra("OPEN_WALKIE_TALKIE", false) == true) {
            showWalkieTalkieScreen = true
            try {
                activity.intent?.removeExtra("OPEN_WALKIE_TALKIE")
            } catch (e: Exception) {
                // ignore
            }
        }
    }
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val drawerWidth = (screenWidth * 0.60f).dp
    var initialSettingsLevel by remember { mutableIntStateOf(0) }
    var showStoragePathDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    
    val transferringTasks = tasks.filter {
        it.status == TransmissionStatus.CONNECTING ||
        it.status == TransmissionStatus.SENDING ||
        it.status == TransmissionStatus.RESUMING ||
        it.status == TransmissionStatus.WAITING_RECONNECT ||
        it.status == TransmissionStatus.PAUSED
    }
    
    val historyTasks = tasks.filter {
        it.status == TransmissionStatus.SUCCESS ||
        it.status == TransmissionStatus.FAILED
    }

    var previousSuccessTaskIds by remember { mutableStateOf(setOf<Int>()) }
    var triggerParticleEffect by remember { mutableStateOf(false) }

    val currentSuccessTaskIds = remember(tasks) {
        tasks.filter { it.status == TransmissionStatus.SUCCESS }.map { it.id }.toSet()
    }

    LaunchedEffect(currentSuccessTaskIds) {
        if (previousSuccessTaskIds.isNotEmpty()) {
            val newSuccesses = currentSuccessTaskIds - previousSuccessTaskIds
            if (newSuccesses.isNotEmpty()) {
                triggerParticleEffect = true
            }
        }
        previousSuccessTaskIds = currentSuccessTaskIds
    }
    
    var showInAppFilePicker by remember { mutableStateOf(false) }
    var pendingSelectedUris by remember { mutableStateOf<List<Uri>?>(null) }
    var showQuickDeviceSelector by remember { mutableStateOf(false) }
    var pendingConfirmationDevice by remember { mutableStateOf<com.example.network.DeviceInfo?>(null) }
    var pendingConfirmationUris by remember { mutableStateOf<List<Uri>?>(null) }

    val triggerFilePickerExtra = activity?.triggerFilePickerState?.value ?: false
    LaunchedEffect(triggerFilePickerExtra) {
        if (triggerFilePickerExtra) {
            selectedDevice = null
            showInAppFilePicker = true
        }
    }

    val sharedUrisFromIntent = activity?.sharedUrisState?.value
    LaunchedEffect(sharedUrisFromIntent) {
        if (sharedUrisFromIntent != null && sharedUrisFromIntent.isNotEmpty()) {
            pendingSelectedUris = sharedUrisFromIntent
            showQuickDeviceSelector = true
            activity.clearSharedUris()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val device = selectedDevice
            if (device != null) {
                pendingConfirmationDevice = device
                pendingConfirmationUris = listOf(uri)
            }
        }
        selectedDevice = null
    }

    val storagePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            storagePermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
        
        try {
            val serviceIntent = android.content.Intent(context, com.example.network.TransferForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        viewModel.startDiscovery(context)
    }

    if (showQuickDeviceSelector) {
        AlertDialog(
            onDismissRequest = {
                showQuickDeviceSelector = false
                pendingSelectedUris = null
            },
            title = { Text("选择目标接收设备") },
            text = {
                if (devices.isEmpty()) {
                    Text("当前局域网没有发现其他设备，请先开启对方的 PTF 接收！", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    ) {
                        items(devices) { device ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingSelectedUris?.let { uris ->
                                            pendingConfirmationDevice = device
                                            pendingConfirmationUris = uris
                                        }
                                        showQuickDeviceSelector = false
                                        pendingSelectedUris = null
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(device.deviceId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Text(device.ipAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showQuickDeviceSelector = false
                    pendingSelectedUris = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    val currentConfirmDevice = pendingConfirmationDevice
    val currentConfirmUris = pendingConfirmationUris
    if (currentConfirmDevice != null && currentConfirmUris != null) {
        val device = currentConfirmDevice
        val uris = currentConfirmUris
        val fileInfos = remember(uris) {
            uris.map { uri ->
                try {
                    com.example.utils.FileUtil.getFileInfo(context, uri)
                } catch (e: Exception) {
                    Pair("unknown_file", 0L)
                }
            }
        }
        val totalSize = remember(fileInfos) { fileInfos.sumOf { it.second } }
        
        AlertDialog(
            onDismissRequest = {
                pendingConfirmationDevice = null
                pendingConfirmationUris = null
            },
            title = {
                Text("确认发送文件?", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "确认向设备「${device.deviceId}」发送以下文件？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(fileInfos) { (name, size) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatSize(size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "共 ${uris.size} 个文件",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatSize(totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.sendMultipleFilesToDevice(device, uris)
                        pendingConfirmationDevice = null
                        pendingConfirmationUris = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("发送")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingConfirmationDevice = null
                        pendingConfirmationUris = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    activeWalkieTalkieRequest?.let { req ->
        AlertDialog(
            onDismissRequest = {
                val r = com.example.network.TransferStateManager.activeWalkieTalkieRequest.value
                com.example.network.TransferStateManager.activeWalkieTalkieRequest.value = null
                r?.callback?.invoke(false)
            },
            title = {
                Text(
                    text = "对讲连接请求",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text("来自设备 ${req.deviceId} 的对讲请求，是否接受并开启对讲？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val r = com.example.network.TransferStateManager.activeWalkieTalkieRequest.value
                        com.example.network.TransferStateManager.activeWalkieTalkieRequest.value = null
                        showWalkieTalkieScreen = true
                        r?.callback?.invoke(true)
                    }
                ) {
                    Text("接受")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val r = com.example.network.TransferStateManager.activeWalkieTalkieRequest.value
                        com.example.network.TransferStateManager.activeWalkieTalkieRequest.value = null
                        r?.callback?.invoke(false)
                    }
                ) {
                    Text("拒绝")
                }
            }
        )
    }

    if (pairingRequest.isShowing) {
        AlertDialog(
            onDismissRequest = { pairingRequest.onReject() },
            confirmButton = {
                Button(onClick = { pairingRequest.onAccept() }) { Text(com.example.utils.stringResource("btn_accept")) }
            },
            dismissButton = {
                TextButton(onClick = { pairingRequest.onReject() }) { Text(com.example.utils.stringResource("btn_reject")) }
            },
            title = { Text(com.example.utils.stringResource("incoming_transfer_title")) },
            text = {
                val currentLang = com.example.utils.LocalAppLanguage.current
                val descTemplate = com.example.utils.stringResource("incoming_transfer_desc")
                val formattedSize = formatSize(pairingRequest.fileSize)
                val safeFileName = pairingRequest.fileName.replace("%", "%%")
                val safeDeviceId = pairingRequest.deviceId.replace("%", "%%")
                val baseMsg = try {
                    if (pairingRequest.groupFileCount > 1) {
                        if (currentLang == "zh") {
                            "设备 %s 想要向您发送 '%s' 等共 %d 个文件。是否接受？".format(safeDeviceId, safeFileName, pairingRequest.groupFileCount)
                        } else if (currentLang == "ja") {
                            "デバイス %s が '%s' など合計 %d 個のファイルを送信しようとしています。同意しますか？".format(safeDeviceId, safeFileName, pairingRequest.groupFileCount)
                        } else if (currentLang == "ru") {
                            "Устройство %s хочет отправить вам '%s' и другие файлы (всего %d). Принять?".format(safeDeviceId, safeFileName, pairingRequest.groupFileCount)
                        } else {
                            "Device %s wants to send '%s' and other files (total %d). Accept?".format(safeDeviceId, safeFileName, pairingRequest.groupFileCount)
                        }
                    } else {
                        descTemplate.format(safeDeviceId, safeFileName, formattedSize)
                    }
                } catch (e: Exception) {
                    "设备 ${pairingRequest.deviceId} 想要向您发送 ${pairingRequest.fileName}。是否接受？"
                }
                Text(baseMsg)
            }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(com.example.utils.stringResource("know_it"))
                }
            },
            title = { Text(com.example.utils.stringResource("cannot_find_device")) },
            text = {
                val annotatedText = androidx.compose.ui.text.buildAnnotatedString {
                    val fullText = com.example.utils.stringResource("troubleshoot_text")
                    val highlightText = if (fullText.contains("不会消耗您的流量！！")) "不会消耗您的流量！！" 
                                        else if (fullText.contains("NOT consume cellular data traffic!!")) "NOT consume cellular data traffic!!"
                                        else if (fullText.contains("テザリング経由のファイル転送はモバイル通信量を消費しません！")) "テザリング経由のファイル転送はモバイル通信量を消費しません！"
                                        else "НЕ расходует интернет-трафик mobile тарифа!!"
                    
                    val parts = fullText.split(highlightText)
                    if (parts.size >= 2) {
                        append(parts[0])
                        withStyle(style = androidx.compose.ui.text.SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) {
                            append(highlightText)
                        }
                        append(parts[1])
                    } else {
                        append(fullText)
                    }
                }
                Text(text = annotatedText)
            }
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    BackHandler(enabled = showWalkieTalkieScreen && !drawerState.isOpen && !showFullSettingsScreen) {
        showWalkieTalkieScreen = false
    }

    BackHandler(enabled = showFullSettingsScreen && !drawerState.isOpen) {
        showFullSettingsScreen = false
    }

    val drawerProgress by animateFloatAsState(
        targetValue = if (drawerState.isOpen) 1f else 0f,
        animationSpec = tween(
            durationMillis = 1000,
            easing = FastOutSlowInEasing
        ),
        label = "nvidia_drawer_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    coroutineScope.launch { drawerState.open() }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = "PTF", 
                                    fontWeight = FontWeight.ExtraBold
                                ) 
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
            ) { padding ->
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        coroutineScope.launch {
                            isRefreshing = true
                            viewModel.refreshDiscovery(context)
                            delay(1000)
                            isRefreshing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left Column (for My Device & Discovered Devices list)
                            Column(
                                modifier = Modifier
                                    .weight(0.42f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Spacer(modifier = Modifier.height(4.dp))
                                MyDeviceCard(deviceId = myDeviceId)
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = com.example.utils.stringResource("nearby_devices"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = com.example.utils.stringResource("troubleshoot_btn"),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        modifier = Modifier.clickable {
                                            showHelpDialog = true
                                        }
                                    )
                                }
                                
                                AnimatedContent(
                                    targetState = devices.isEmpty(),
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                    },
                                    label = "landscape_devices_radar_transition"
                                ) { isEmpty ->
                                    if (isEmpty) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(100.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = com.example.utils.stringResource("radar_tip"),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth().animateContentSize()
                                        ) {
                                            devices.forEach { device ->
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            selectedDevice = device
                                                            showInAppFilePicker = true
                                                        },
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(16.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.PhoneAndroid,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(16.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = device.deviceId,
                                                                fontWeight = FontWeight.Bold,
                                                                style = MaterialTheme.typography.titleMedium
                                                            )
                                                            Text(
                                                                text = device.ipAddress,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowRight,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(40.dp))
                            }
                            
                            // Right Scroll Container (for Active Tasks & History Tasks)
                            LazyColumn(
                                modifier = Modifier
                                    .weight(0.58f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                item {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    StackedTransferTasksSection(
                                        transferringTasks = transferringTasks,
                                        viewModel = viewModel
                                    )
                                }
                                
                                item {
                                    HistoryTasksSection(
                                        historyTasks = historyTasks,
                                        viewModel = viewModel
                                    )
                                }
                                
                                item {
                                    Spacer(modifier = Modifier.height(40.dp))
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            item {
                                MyDeviceCard(deviceId = myDeviceId)
                            }
                            
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = com.example.utils.stringResource("nearby_devices"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = com.example.utils.stringResource("troubleshoot_btn"),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        modifier = Modifier.clickable {
                                            showHelpDialog = true
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                AnimatedContent(
                                    targetState = devices.isEmpty(),
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                    },
                                    label = "devices_radar_transition"
                                ) { isEmpty ->
                                    if (isEmpty) {
                                        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                            Text(com.example.utils.stringResource("radar_tip"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    } else {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                            items(devices, key = { it.deviceId + it.ipAddress }) { device ->
                                                DeviceCard(device = device, modifier = Modifier.animateItem()) {
                                                    selectedDevice = device
                                                    showInAppFilePicker = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                StackedTransferTasksSection(
                                    transferringTasks = transferringTasks,
                                    viewModel = viewModel
                                )
                            }

                            item {
                                HistoryTasksSection(
                                    historyTasks = historyTasks,
                                    viewModel = viewModel
                                )
                            }

                            item {
                                Spacer(modifier = Modifier.height(40.dp))
                            }
                        }
                    }
                }
            }
        }

        ParticleFlyEffectOverlay(
            isTriggered = triggerParticleEffect,
            onAnimationEnd = { triggerParticleEffect = false }
        )

    AnimatedVisibility(
        visible = showInAppFilePicker,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        com.example.ui.InAppFilePickerDialog(
            onDismissRequest = {
                showInAppFilePicker = false
                activity?.triggerFilePickerState?.value = false
            },
            onConfirmSelection = { uris ->
                val device = selectedDevice
                if (device != null) {
                    pendingConfirmationDevice = device
                    pendingConfirmationUris = uris
                } else {
                    pendingSelectedUris = uris
                    showQuickDeviceSelector = true
                }
            }
        )
    }

    if (showFolderPicker) {
        com.example.ui.InAppFolderPickerDialog(
            initialPath = storagePath,
            onDismissRequest = { showFolderPicker = false },
            onConfirmFolder = { selectedFolder ->
                viewModel.setStoragePath(selectedFolder)
                showFolderPicker = false
                showStoragePathDialog = false
            }
        )
    }

    if (showStoragePathDialog) {
        var tempPath by remember { mutableStateOf(storagePath) }
        
        AlertDialog(
            onDismissRequest = { showStoragePathDialog = false },
            confirmButton = {
                Button(onClick = {
                    viewModel.setStoragePath(tempPath)
                    showStoragePathDialog = false
                }) {
                    Text(com.example.utils.stringResource("btn_save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePathDialog = false }) {
                    Text(com.example.utils.stringResource("btn_cancel"))
                }
            },
            title = { Text(com.example.utils.stringResource("set_storage_path")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(com.example.utils.stringResource("set_storage_position"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = tempPath,
                        onValueChange = { tempPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(com.example.utils.stringResource("storage_path_label")) },
                        singleLine = true
                    )

                    FilledTonalButton(
                        onClick = {
                            showFolderPicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("浏览文件夹选择")
                    }

                    val hintText = if (com.example.utils.LocalAppLanguage.current == "zh") {
                        "说明：您可以手动输入自定义路径，也可以点击上方按钮进行文件夹浏览选择。"
                    } else if (com.example.utils.LocalAppLanguage.current == "ja") {
                        "説明：パスを直接入力するか、上のボタンからフォルダを参照して選択できます。"
                    } else if (com.example.utils.LocalAppLanguage.current == "ru") {
                        "Примечание: вы можете ввести путь вручную или нажать кнопку выше для выбора папки."
                    } else {
                        "Note: You can enter the path manually or click the button above to browse and select a folder."
                    }
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    val layer1Progress = (drawerProgress / 0.45f).coerceIn(0f, 1f)
    val layer2Progress = ((drawerProgress - 0.45f) / 0.45f).coerceIn(0f, 1f)
    val optionsAlpha = ((drawerProgress - 0.90f) / 0.10f).coerceIn(0f, 1f)
    val optionsOffsetY = ((1f - optionsAlpha) * 12).dp

    if (drawerProgress > 0f) {
        // Scrim overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f * drawerProgress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    coroutineScope.launch { drawerState.close() }
                }
        )

        // Staggered drawer content container
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Consume click events to prevent closing
                )
        ) {
            // Layer 1 (Pure color primary accent layer - full width)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = drawerWidth * (layer1Progress - 1f))
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RectangleShape
                    )
            )

            // Layer 2 (UI display frame layer - leaves 14.dp pure color strip visible on the right edge)
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(end = 14.dp)
                    .offset(x = drawerWidth * (layer2Progress - 1f)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RectangleShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .graphicsLayer {
                            alpha = optionsAlpha
                            translationY = optionsOffsetY.toPx()
                        }
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = com.example.utils.stringResource("menu_title"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    
                    // Device ID Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Icon(
                                    Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    com.example.utils.stringResource("device_id"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = myDeviceId,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // "对讲机" Button Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                coroutineScope.launch { drawerState.close() }
                                showWalkieTalkieScreen = true
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WalkieTalkieIcon(
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "对讲机",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    // Storage Path Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                coroutineScope.launch { drawerState.close() }
                                showStoragePathDialog = true
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    com.example.utils.stringResource("storage_path"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit path",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = storagePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1
                            )
                        }
                    }
                    
                    var isFloatingEnabled by remember {
                        mutableStateOf(
                            android.provider.Settings.canDrawOverlays(context) && run {
                                val prefs = context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                                prefs.getBoolean("floating_keepalive_enabled", false)
                            }
                        )
                    }

                    LaunchedEffect(drawerState.isOpen) {
                        if (drawerState.isOpen) {
                            isFloatingEnabled = android.provider.Settings.canDrawOverlays(context) && run {
                                val prefs = context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                                prefs.getBoolean("floating_keepalive_enabled", false)
                            }
                        }
                    }

                    // Floating Window Keepalive Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "小悬浮窗保活",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Switch(
                                checked = isFloatingEnabled,
                                onCheckedChange = { checked ->
                                    try {
                                        com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Side drawer floating switch toggled to $checked")
                                        val prefs = context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                                        if (checked) {
                                            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                android.provider.Settings.canDrawOverlays(context)
                                            } else {
                                                true
                                            }
                                            com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Overlay permission status: $hasOverlay")

                                            if (!hasOverlay) {
                                                com.example.utils.AppLogger.warn("FLOATING_WINDOW_TOGGLE", "Overlay permission missing, redirecting to system settings")
                                                android.widget.Toast.makeText(context, "请先授予悬浮窗(显示在其他应用上层)权限", android.widget.Toast.LENGTH_LONG).show()
                                                try {
                                                    val intent = android.content.Intent(
                                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        android.net.Uri.parse("package:${context.packageName}")
                                                    ).apply {
                                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Throwable) {
                                                    com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Failed to open overlay settings", e)
                                                }
                                                isFloatingEnabled = false
                                                prefs.edit().putBoolean("background_keepalive", false).putBoolean("floating_keepalive_enabled", false).apply()
                                            } else {
                                                com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Starting TransferForegroundService with ACTION_START_FLOATING...")
                                                val serviceIntent = android.content.Intent(context, com.example.network.TransferForegroundService::class.java).apply {
                                                    action = com.example.network.TransferForegroundService.ACTION_START_FLOATING
                                                    putExtra(com.example.network.TransferForegroundService.EXTRA_FLOATING_TYPE, com.example.network.TransferForegroundService.FLOATING_TYPE_TRANSFER)
                                                }
                                                try {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        context.startForegroundService(serviceIntent)
                                                    } else {
                                                        context.startService(serviceIntent)
                                                    }
                                                    com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "TransferForegroundService start command sent successfully")
                                                    prefs.edit().putBoolean("background_keepalive", true).putBoolean("floating_keepalive_enabled", true).apply()
                                                    isFloatingEnabled = true
                                                    android.widget.Toast.makeText(context, "保活悬浮窗已开启", android.widget.Toast.LENGTH_SHORT).show()
                                                } catch (e: Throwable) {
                                                    com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Failed to start TransferForegroundService", e)
                                                    android.widget.Toast.makeText(context, "启动悬浮窗服务失败: ${e.localizedMessage ?: e.message}", android.widget.Toast.LENGTH_LONG).show()
                                                    isFloatingEnabled = false
                                                    prefs.edit().putBoolean("background_keepalive", false).putBoolean("floating_keepalive_enabled", false).apply()
                                                }
                                            }
                                        } else {
                                            com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Stopping floating window service...")
                                            prefs.edit().putBoolean("background_keepalive", false).putBoolean("floating_keepalive_enabled", false).apply()
                                            isFloatingEnabled = false
                                            
                                            val serviceIntent = android.content.Intent(context, com.example.network.TransferForegroundService::class.java).apply {
                                                action = com.example.network.TransferForegroundService.ACTION_STOP_FLOATING
                                            }
                                            try {
                                                context.startService(serviceIntent)
                                            } catch (e: Throwable) {
                                                com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Failed to send stop service command", e)
                                            }
                                            android.widget.Toast.makeText(context, "保活悬浮窗已关闭", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Throwable) {
                                        com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Unexpected crash in drawer floating switch", e)
                                        android.widget.Toast.makeText(context, "操作失败: ${e.localizedMessage ?: e.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }

                    // Settings Card Item
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { 
                                coroutineScope.launch { drawerState.close() }
                                initialSettingsLevel = 0
                                showFullSettingsScreen = true
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    com.example.utils.stringResource("settings"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    // About App Card Item
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { 
                                coroutineScope.launch { drawerState.close() }
                                initialSettingsLevel = 3
                                showFullSettingsScreen = true
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    com.example.utils.stringResource("about_app"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Full-screen independent Walkie-Talkie Screen
    AnimatedVisibility(
        visible = showWalkieTalkieScreen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            WalkieTalkieScreen(
                myDeviceId = myDeviceId,
                devices = devices,
                onBack = { showWalkieTalkieScreen = false }
            )
        }
    }

    // Full-screen independent Settings / About Screen
    AnimatedVisibility(
        visible = showFullSettingsScreen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            SettingsScreen(
                viewModel = viewModel,
                initialLevel = initialSettingsLevel,
                onBack = { showFullSettingsScreen = false }
            )
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieScreen(
    myDeviceId: String,
    devices: List<DeviceInfo>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val connectedDevices by com.example.network.TransferStateManager.walkieTalkieConnectedDevices.collectAsState()
    val peerSpeakingMap by com.example.network.TransferStateManager.peerSpeakingStates.collectAsState()
    val isPeerSpeaking by com.example.network.TransferStateManager.isPeerSpeaking.collectAsState()
    val peerAudioLevel by com.example.network.TransferStateManager.peerAudioLevel.collectAsState()
    val localAudioLevel by com.example.network.TransferStateManager.localAudioLevel.collectAsState()
    val isNoiseSuppressionEnabled by com.example.network.TransferStateManager.isNoiseSuppressionEnabled.collectAsState()
    val networkQuality by com.example.network.TransferStateManager.networkQualityRating.collectAsState()
    
    val intercomRooms by com.example.network.TransferStateManager.intercomRooms.collectAsState()
    val currentRoom by com.example.network.TransferStateManager.currentRoom.collectAsState()
    val discoveredRooms by com.example.network.TransferStateManager.discoveredRooms.collectAsState()
    val activeRoomJoinRequest by com.example.network.TransferStateManager.activeRoomJoinRequest.collectAsState()

    var showAddMenuDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showRoomManagerDialog by remember { mutableStateOf(false) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var isMicLocked by remember { mutableStateOf(false) }

    LaunchedEffect(isMicLocked) {
        com.example.network.TransferStateManager.isMicLockedState.value = isMicLocked
    }
    var connectingDeviceId by remember { mutableStateOf<String?>(null) }
    val myIp = remember { com.example.utils.NetworkUtil.getLocalIpAddress() }

    // Battery optimization check for background execution protection
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager }
    val isIgnoringBatteryOpt = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else true
    }

    val mediaProjectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            try {
                com.example.network.MediaProjectionForegroundService.startService(
                    context,
                    result.resultCode,
                    result.data!!
                )
                android.widget.Toast.makeText(context, "🔊 系统内部音频捕获已启动", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                com.example.network.TransferStateManager.isAudioSharingEnabled.value = false
                android.widget.Toast.makeText(context, "启动系统音频捕获异常", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            com.example.network.TransferStateManager.isAudioSharingEnabled.value = false
            android.widget.Toast.makeText(context, "未授予系统音频录制权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            android.widget.Toast.makeText(context, "对讲需要麦克风权限以传输语音", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Manage WalkieTalkieForegroundService lifecycle independently
    val connectedWtDevices by com.example.network.TransferStateManager.walkieTalkieConnectedDevices.collectAsState()
    LaunchedEffect(connectedWtDevices, currentRoom) {
        val hasActiveWalkieTalkie = connectedWtDevices.isNotEmpty() || currentRoom != null
        if (hasActiveWalkieTalkie) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.example.network.WalkieTalkieForegroundService.startService(context)
            }
        } else {
            com.example.network.WalkieTalkieForegroundService.stopService(context)
        }
    }

    // Room Owner Heartbeat every 5 seconds
    LaunchedEffect(currentRoom, myDeviceId) {
        val room = currentRoom
        if (room != null && room.creatorId == myDeviceId) {
            while (true) {
                delay(5000)
                com.example.network.TransferStateManager.sendRoomHeartbeat(myDeviceId)
            }
        }
    }

    // Periodically clean up stale rooms from network scanning (every 3 seconds)
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            com.example.network.TransferStateManager.cleanStaleRooms()
        }
    }

    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Start speaking helper
    val startSpeakingInternal: () -> Unit = {
        isSpeaking = true
        com.example.network.WalkieTalkieAudioManager.broadcastSpeakingState(true)
        com.example.network.WalkieTalkieAudioManager.startRecording(coroutineScope)
    }

    // Stop speaking helper
    val stopSpeakingInternal: () -> Unit = {
        isSpeaking = false
        isMicLocked = false
        com.example.network.WalkieTalkieAudioManager.stopRecording()
        com.example.network.WalkieTalkieAudioManager.broadcastSpeakingState(false)
    }

    // Pulsing animation when speaking
    val pulseScale by animateFloatAsState(
        targetValue = if (isSpeaking) 1.2f else 1.0f,
        animationSpec = if (isSpeaking) {
            infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            snap()
        },
        label = "mic_pulse_animation"
    )

    Scaffold(
        modifier = Modifier
            .statusBarsPadding()
            .navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "对讲机",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "此设备 ID: $myDeviceId · 已连 ${connectedDevices.size} 台",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    var isFloatingActive by remember {
                        mutableStateOf(
                            context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                                .getBoolean("background_keepalive", false) ||
                            context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                                .getBoolean("floating_keepalive_enabled", false)
                        )
                    }

                    // Floating window switch icon button with comprehensive logging and error handling
                    IconButton(
                        onClick = {
                            try {
                                com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Floating window top bar button clicked")
                                val prefs = context.getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                                val newState = !isFloatingActive
                                com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "New desired floating state: $newState")

                                if (newState) {
                                    val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        android.provider.Settings.canDrawOverlays(context)
                                    } else {
                                        true
                                    }
                                    com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Overlay permission status (canDrawOverlays): $hasOverlay")

                                    if (!hasOverlay) {
                                        com.example.utils.AppLogger.warn("FLOATING_WINDOW_TOGGLE", "Overlay permission missing, redirecting to system settings")
                                        android.widget.Toast.makeText(context, "请先授予悬浮窗(显示在其他应用上层)权限", android.widget.Toast.LENGTH_LONG).show()
                                        try {
                                            val intent = Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Throwable) {
                                            com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Failed to open overlay permission settings", e)
                                        }
                                        isFloatingActive = false
                                        prefs.edit().putBoolean("background_keepalive", false).putBoolean("floating_keepalive_enabled", false).apply()
                                        return@IconButton
                                    }

                                    com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Starting TransferForegroundService with ACTION_START_FLOATING (FLOATING_TYPE_WALKIE_TALKIE)...")
                                    val serviceIntent = Intent(context, com.example.network.TransferForegroundService::class.java).apply {
                                        action = com.example.network.TransferForegroundService.ACTION_START_FLOATING
                                        putExtra(com.example.network.TransferForegroundService.EXTRA_FLOATING_TYPE, com.example.network.TransferForegroundService.FLOATING_TYPE_WALKIE_TALKIE)
                                    }

                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(serviceIntent)
                                        } else {
                                            context.startService(serviceIntent)
                                        }
                                        com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "TransferForegroundService start command sent successfully")
                                        isFloatingActive = true
                                        prefs.edit()
                                            .putBoolean("background_keepalive", true)
                                            .putBoolean("floating_keepalive_enabled", true)
                                            .putString("floating_mode", com.example.network.TransferForegroundService.FLOATING_TYPE_WALKIE_TALKIE)
                                            .apply()
                                        android.widget.Toast.makeText(context, "保活悬浮窗已开启", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (e: Throwable) {
                                        com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Failed to start TransferForegroundService", e)
                                        android.widget.Toast.makeText(context, "启动悬浮窗服务失败: ${e.localizedMessage ?: e.message}", android.widget.Toast.LENGTH_LONG).show()
                                        isFloatingActive = false
                                        prefs.edit().putBoolean("background_keepalive", false).putBoolean("floating_keepalive_enabled", false).apply()
                                    }
                                } else {
                                    com.example.utils.AppLogger.info("FLOATING_WINDOW_TOGGLE", "Stopping floating window service...")
                                    val serviceIntent = Intent(context, com.example.network.TransferForegroundService::class.java).apply {
                                        action = com.example.network.TransferForegroundService.ACTION_STOP_FLOATING
                                    }
                                    try {
                                        context.startService(serviceIntent)
                                    } catch (e: Throwable) {
                                        com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Failed to send stop service command", e)
                                    }
                                    isFloatingActive = false
                                    prefs.edit().putBoolean("background_keepalive", false).putBoolean("floating_keepalive_enabled", false).apply()
                                    android.widget.Toast.makeText(context, "保活悬浮窗已关闭", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Throwable) {
                                com.example.utils.AppLogger.error("FLOATING_WINDOW_TOGGLE", "Unexpected crash in floating window toggle button", e)
                                android.widget.Toast.makeText(context, "操作失败: ${e.localizedMessage ?: e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = "悬浮窗开关",
                            tint = if (isFloatingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { showAddMenuDialog = true },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Active Room Header Card (If inside a room)
                if (currentRoom != null) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Column {
                                    Text("房间: ${currentRoom?.roomName}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("ID: ${currentRoom?.roomId} · 成员: ${connectedDevices.size + 1}人", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                                }
                            }
                            Row {
                                TextButton(onClick = { showSearchDialog = true }) {
                                    Text("邀请", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { com.example.network.TransferStateManager.leaveRoom() }) {
                                    Text("退出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // Connected Devices List or Empty State
                if (connectedDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                WalkieTalkieIcon(
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                            Text(
                                text = "暂无局域网对讲连接",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "点击右上角 '+' 扫描局域网 PTF 设备。\n支持多设备同时连接与实时广播对讲！",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "当前已连接设备 (${connectedDevices.size}) · 滑动卡片可断开",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(connectedDevices, key = { it.deviceId }) { dev ->
                                val devSpeaking = peerSpeakingMap[dev.deviceId] ?: false
                                com.example.ui.WalkieTalkieDeviceCard(
                                    device = dev,
                                    isPeerSpeaking = devSpeaking,
                                    peerAudioLevel = peerAudioLevel,
                                    networkQuality = networkQuality,
                                    onDisconnect = {
                                        com.example.network.TransferStateManager.removeIntercomDevice(dev.deviceId)
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom Controls: Badges, Waveform & Mic Button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status Badge Indicator
                    val statusText = when {
                        connectedDevices.isEmpty() -> "未连接设备 (请先添加对讲设备)"
                        isPeerSpeaking -> "📶 对方正在讲话..."
                        isMicLocked -> "🔒 已锁定持续讲话中 (轻触按钮关闭)"
                        isSpeaking -> "🎙️ 正在广播讲话 (拖动进入圆环功能区)"
                        else -> "长按麦克风 · 上滑共享 · 左滑锁定 · 右滑降噪"
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = when {
                            isPeerSpeaking -> MaterialTheme.colorScheme.errorContainer
                            isMicLocked || isSpeaking -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isPeerSpeaking -> MaterialTheme.colorScheme.onErrorContainer
                                    isMicLocked || isSpeaking -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            // Noise suppression mode tag
                            if (connectedDevices.isNotEmpty()) {
                                Text(
                                    text = if (isNoiseSuppressionEnabled) "[降噪开启]" else "[普通模式]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Local speaking waveform visualizer
                    if (isSpeaking) {
                        com.example.ui.AudioWaveformVisualizer(
                            audioLevel = localAudioLevel,
                            barColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(24.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Large Mic Button with Real-time Drag & Gesture Interactions
                    val micEnabled = connectedDevices.isNotEmpty() && !isPeerSpeaking

                    com.example.ui.WalkieTalkieMicButton(
                        micEnabled = micEnabled,
                        isSpeaking = isSpeaking,
                        isMicLocked = isMicLocked,
                        pulseScale = pulseScale,
                        connectedDevicesCount = connectedDevices.size,
                        isPeerSpeaking = isPeerSpeaking,
                        onStartSpeaking = { startSpeakingInternal() },
                        onStopSpeaking = { stopSpeakingInternal() },
                        onLockMic = { isMicLocked = true },
                        onToggleNoiseSuppression = {
                            val newNS = !com.example.network.TransferStateManager.isNoiseSuppressionEnabled.value
                            com.example.network.TransferStateManager.isNoiseSuppressionEnabled.value = newNS
                            val modeText = if (newNS) "已切换为：降噪增强模式" else "已切换为：普通模式"
                            android.widget.Toast.makeText(context, modeText, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onRequestMicPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onRequestSystemAudioCapture = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjectionManager != null) {
                                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            } else {
                                com.example.network.TransferStateManager.isAudioSharingEnabled.value = true
                                android.widget.Toast.makeText(context, "🔊 已开启系统内部音频共享", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        centerIcon = {
                            WalkieTalkieIcon(
                                tint = when {
                                    isSpeaking -> MaterialTheme.colorScheme.onPrimary
                                    micEnabled -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                },
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    )
                }
            }
        }
    }

    // Search Dialog
    if (showSearchDialog) {
        val availableDevices = devices.filter { dev ->
            dev.deviceId != myDeviceId && connectedDevices.none { it.deviceId == dev.deviceId }
        }
        com.example.ui.WalkieTalkieSearchDialog(
            devices = availableDevices,
            rooms = discoveredRooms,
            connectingDeviceId = connectingDeviceId,
            onConnectRequest = { dev ->
                connectingDeviceId = dev.deviceId
                coroutineScope.launch(Dispatchers.IO) {
                    var connectedSuccess = false
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(dev.ipAddress, com.example.network.TcpTransferProtocol.PORT), 8000)
                        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                        val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)
                        
                        val req = org.json.JSONObject().apply {
                            put("type", "WALKIE_TALKIE_REQUEST")
                            put("deviceId", myDeviceId)
                        }
                        com.example.network.TcpTransferProtocol.sendJsonString(outStream, req)
                        
                        val resp = com.example.network.TcpTransferProtocol.receiveJsonString(inStream)
                        if (resp?.optString("type") == "WALKIE_TALKIE_ACCEPT") {
                            val peerId = resp.optString("deviceId", dev.deviceId)
                            val peerDevice = DeviceInfo(peerId, dev.ipAddress, lastSeenTimestamp = System.currentTimeMillis())
                            
                            withContext(Dispatchers.Main) {
                                com.example.network.TransferStateManager.addIntercomDevice(peerDevice, socket)
                                showSearchDialog = false
                                android.widget.Toast.makeText(context, "设备 $peerId 已加入对讲网络", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            connectedSuccess = true
                            
                            // Start playback engine & broadcast peer sync
                            com.example.network.WalkieTalkieAudioManager.startPlayback()
                            com.example.network.TransferStateManager.broadcastPeerSync(myDeviceId)
                            
                            // Start reading loop on this socket
                            try {
                                while (true) {
                                    val msg = com.example.network.TcpTransferProtocol.receiveJsonString(inStream) ?: break
                                    val msgType = msg.optString("type")
                                    if (msgType == "WALKIE_TALKIE_STATE") {
                                        val peerSpeaking = msg.optBoolean("isSpeaking", false)
                                        com.example.network.TransferStateManager.setPeerSpeaking(peerId, peerSpeaking)
                                    } else if (msgType == "WALKIE_TALKIE_AUDIO_SHARING_STATE") {
                                        val isSharing = msg.optBoolean("isAudioSharing", false)
                                        com.example.network.TransferStateManager.setPeerAudioSharing(peerId, isSharing)
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
                                                if (pId.isNotEmpty() && pId != myDeviceId && !com.example.network.TransferStateManager.walkieTalkieSockets.containsKey(pId) && pIp.isNotEmpty()) {
                                                    com.example.network.TransferStateManager.autoConnectMeshPeer(context, DeviceInfo(pId, pIp, System.currentTimeMillis()), myDeviceId, coroutineScope)
                                                }
                                            }
                                        }
                                    } else if (msgType == "WALKIE_TALKIE_DISCONNECT") {
                                        break
                                    }
                                }
                            } catch (e: Throwable) {
                                com.example.utils.AppLogger.error("WALKIE_TALKIE_CLIENT", "Loop error for $peerId", e)
                            } finally {
                                withContext(Dispatchers.Main) {
                                    com.example.network.TransferStateManager.removeIntercomDevice(peerId)
                                    try { socket.close() } catch (ex: Throwable) {}
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "连接请求被拒绝", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Throwable) {
                        com.example.utils.AppLogger.error("WALKIE_TALKIE_CLIENT", "Failed to connect", e)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "连接失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        if (!connectedSuccess) {
                            try { socket.close() } catch (ex: Throwable) {}
                        }
                        withContext(Dispatchers.Main) {
                            connectingDeviceId = null
                        }
                    }
                }
            },
            onJoinRoomRequest = { room, hostIp ->
                connectingDeviceId = "ROOM_${room.roomId}"
                coroutineScope.launch(Dispatchers.IO) {
                    var connectedSuccess = false
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(hostIp, com.example.network.TcpTransferProtocol.PORT), 8000)
                        val outStream = java.io.BufferedOutputStream(socket.getOutputStream(), 262144)
                        val inStream = java.io.BufferedInputStream(socket.getInputStream(), 262144)
                        
                        val req = org.json.JSONObject().apply {
                            put("type", "WALKIE_TALKIE_ROOM_JOIN_REQUEST")
                            put("deviceId", myDeviceId)
                            put("roomId", room.roomId)
                            put("roomName", room.roomName)
                            put("ipAddress", myIp)
                        }
                        com.example.network.TcpTransferProtocol.sendJsonString(outStream, req)
                        
                        val resp = com.example.network.TcpTransferProtocol.receiveJsonString(inStream)
                        if (resp?.optString("type") == "WALKIE_TALKIE_ACCEPT") {
                            val peerId = resp.optString("deviceId", "HOST")
                            val peerDevice = DeviceInfo(peerId, hostIp, lastSeenTimestamp = System.currentTimeMillis())
                            
                            withContext(Dispatchers.Main) {
                                com.example.network.TransferStateManager.addIntercomDevice(peerDevice, socket)
                                com.example.network.TransferStateManager.currentRoom.value = room
                                showSearchDialog = false
                                android.widget.Toast.makeText(context, "已成功加入房间 '${room.roomName}'！", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            connectedSuccess = true
                            
                            com.example.network.WalkieTalkieAudioManager.startPlayback()
                            com.example.network.TransferStateManager.broadcastPeerSync(myDeviceId)
                            if (com.example.network.TransferStateManager.isAudioSharingEnabled.value) {
                                com.example.network.WalkieTalkieAudioManager.broadcastAudioSharingState(true)
                            }
                            
                            try {
                                while (true) {
                                    val msg = com.example.network.TcpTransferProtocol.receiveJsonString(inStream) ?: break
                                    val msgType = msg.optString("type")
                                    if (msgType == "WALKIE_TALKIE_STATE") {
                                        val peerSpeaking = msg.optBoolean("isSpeaking", false)
                                        com.example.network.TransferStateManager.setPeerSpeaking(peerId, peerSpeaking)
                                    } else if (msgType == "WALKIE_TALKIE_AUDIO_SHARING_STATE") {
                                        val isSharing = msg.optBoolean("isAudioSharing", false)
                                        com.example.network.TransferStateManager.setPeerAudioSharing(peerId, isSharing)
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
                                                if (pId.isNotEmpty() && pId != myDeviceId && !com.example.network.TransferStateManager.walkieTalkieSockets.containsKey(pId) && pIp.isNotEmpty()) {
                                                    com.example.network.TransferStateManager.autoConnectMeshPeer(context, DeviceInfo(pId, pIp, System.currentTimeMillis()), myDeviceId, coroutineScope)
                                                }
                                            }
                                        }
                                    } else if (msgType == "WALKIE_TALKIE_DISCONNECT") {
                                        break
                                    }
                                }
                            } catch (e: Throwable) {
                                com.example.utils.AppLogger.error("WALKIE_TALKIE_CLIENT", "Room loop error for $peerId", e)
                            } finally {
                                withContext(Dispatchers.Main) {
                                    com.example.network.TransferStateManager.removeIntercomDevice(peerId)
                                    try { socket.close() } catch (ex: Throwable) {}
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "加入房间请求被拒绝", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Throwable) {
                        com.example.utils.AppLogger.error("WALKIE_TALKIE_CLIENT", "Failed to join room", e)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "加入房间失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        if (!connectedSuccess) {
                            try { socket.close() } catch (ex: Throwable) {}
                        }
                        withContext(Dispatchers.Main) {
                            connectingDeviceId = null
                        }
                    }
                }
            },
            onDismiss = { showSearchDialog = false }
        )
    }

    // Host side approval dialog for room join requests
    val currentRoomJoinReq = activeRoomJoinRequest
    if (currentRoomJoinReq != null) {
        AlertDialog(
            onDismissRequest = {
                currentRoomJoinReq.callback(false)
                com.example.network.TransferStateManager.activeRoomJoinRequest.value = null
            },
            title = {
                Text("🏠 房间加入申请", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("设备 ${currentRoomJoinReq.applicantDeviceId} (IP: ${currentRoomJoinReq.applicantIp}) 申请加入房间 '${currentRoomJoinReq.roomName}'，是否同意？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        currentRoomJoinReq.callback(true)
                        com.example.network.TransferStateManager.activeRoomJoinRequest.value = null
                    }
                ) {
                    Text("同意加入")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        currentRoomJoinReq.callback(false)
                        com.example.network.TransferStateManager.activeRoomJoinRequest.value = null
                    }
                ) {
                    Text("拒绝")
                }
            }
        )
    }

    // Add Menu Dialog ("添加设备" vs "创建房间")
    if (showAddMenuDialog) {
        com.example.ui.WalkieTalkieAddMenuDialog(
            onSelectAddDevice = { showSearchDialog = true },
            onSelectCreateRoom = { showRoomManagerDialog = true },
            onDismiss = { showAddMenuDialog = false }
        )
    }

    // Room Manager Dialog
    if (showRoomManagerDialog) {
        com.example.ui.WalkieTalkieRoomManagerDialog(
            rooms = intercomRooms,
            currentRoom = currentRoom,
            onCreateNewRoomClick = {
                showRoomManagerDialog = false
                showCreateRoomDialog = true
            },
            onSelectRoom = { selectedRoom ->
                com.example.network.TransferStateManager.currentRoom.value = selectedRoom
                showRoomManagerDialog = false
                android.widget.Toast.makeText(context, "已进入房间: ${selectedRoom.roomName}", android.widget.Toast.LENGTH_SHORT).show()
            },
            onLeaveRoom = {
                com.example.network.TransferStateManager.leaveRoom(myDeviceId)
                android.widget.Toast.makeText(context, "已退出当前房间", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showRoomManagerDialog = false }
        )
    }

    // Create Room Dialog
    if (showCreateRoomDialog) {
        com.example.ui.WalkieTalkieCreateRoomDialog(
            onCreateRoom = { newRoomName ->
                val created = com.example.network.TransferStateManager.createRoom(
                    roomName = newRoomName,
                    creatorId = myDeviceId,
                    myDevice = DeviceInfo(myDeviceId, myIp, System.currentTimeMillis())
                )
                showCreateRoomDialog = false
                android.widget.Toast.makeText(context, "房间 '${created.roomName}' 创建成功并已进入！", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showCreateRoomDialog = false }
        )
    }
}

@Composable
fun MyDeviceCard(deviceId: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(com.example.utils.stringResource("main_device_id"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text(deviceId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun DeviceCard(device: DeviceInfo, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.clickable(onClick = onClick).width(120.dp).height(120.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(device.deviceId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(device.ipAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun TaskCard(task: TransmissionTask, viewModel: MainViewModel) {
    val currentTask by rememberUpdatedState(task)
    
    var displayedOffset by remember(task.id) { mutableStateOf(task.offset) }
    var displayedSpeed by remember(task.id) { mutableStateOf(0.0) }
    var displayedEta by remember(task.id) { mutableStateOf(-1L) }
    
    LaunchedEffect(task.id, task.status) {
        if (task.status == TransmissionStatus.SENDING || task.status == TransmissionStatus.RESUMING) {
            var lastSampleOffset = currentTask.offset
            var lastSampleTime = System.currentTimeMillis()
            while (true) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val currentOffset = currentTask.offset
                val timeDiffSec = (now - lastSampleTime) / 1000.0
                if (timeDiffSec > 0) {
                    val bytesDiff = currentOffset - lastSampleOffset
                    val speedBytesPerSec = bytesDiff / timeDiffSec
                    val speedMBs = speedBytesPerSec / (1024.0 * 1024.0)
                    
                    displayedSpeed = if (speedMBs >= 0.0) speedMBs else 0.0
                    displayedOffset = currentOffset
                    
                    val remainingBytes = currentTask.fileSize - currentOffset
                    displayedEta = if (speedBytesPerSec > 0 && remainingBytes > 0) {
                        (remainingBytes / speedBytesPerSec).toLong()
                    } else {
                        -1L
                    }
                    
                    lastSampleOffset = currentOffset
                    lastSampleTime = now
                }
            }
        } else {
            displayedOffset = task.offset
            displayedSpeed = 0.0
            displayedEta = -1L
        }
    }
    
    val progress = if (task.fileSize > 0) displayedOffset.toFloat() / task.fileSize.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "progress"
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_rotation"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (task.status) {
                    TransmissionStatus.SUCCESS -> Icons.Default.CheckCircle
                    TransmissionStatus.FAILED -> Icons.Default.Warning
                    else -> Icons.Default.Sync
                }
                val iconTint = when (task.status) {
                    TransmissionStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                    TransmissionStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                }
                Icon(
                    icon, 
                    contentDescription = task.status.name, 
                    tint = iconTint,
                    modifier = Modifier.rotate(
                        if (task.status == TransmissionStatus.SENDING || task.status == TransmissionStatus.RESUMING) rotationAngle else 0f
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${if (task.isSender) "To" else "From"} ${task.deviceId} • ${task.status.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                if (task.status == TransmissionStatus.SUCCESS || task.status == TransmissionStatus.FAILED) {
                    IconButton(
                        onClick = { viewModel.deleteTask(task) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete task",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            if (task.status == TransmissionStatus.SENDING || 
                task.status == TransmissionStatus.RESUMING || 
                task.status == TransmissionStatus.CONNECTING || 
                task.status == TransmissionStatus.WAITING_RECONNECT ||
                task.status == TransmissionStatus.PAUSED) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val percent = (progress * 100).toInt().coerceIn(0, 100)
                    Text(
                        text = "$percent%${if (displayedSpeed > 0 && (task.status == TransmissionStatus.SENDING || task.status == TransmissionStatus.RESUMING)) " • ${String.format("%.1f", displayedSpeed)} MB/s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    val etaStr = when {
                        task.status == TransmissionStatus.PAUSED -> com.example.utils.stringResource("state_paused")
                        task.status == TransmissionStatus.CONNECTING -> com.example.utils.stringResource("state_connecting")
                        task.status == TransmissionStatus.WAITING_RECONNECT -> com.example.utils.stringResource("state_waiting_reconnect")
                        displayedEta > 0 -> {
                            if (displayedEta < 60) com.example.utils.stringResource("remaining_seconds").format(displayedEta.toString())
                            else com.example.utils.stringResource("remaining_minutes_seconds").format((displayedEta / 60).toString(), (displayedEta % 60).toString())
                        }
                        displayedSpeed > 0 -> com.example.utils.stringResource("state_calculating")
                        else -> "--"
                    }
                    
                    Text(
                        text = "${formatSize(displayedOffset)} / ${formatSize(task.fileSize)} (${etaStr})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (task.isSender && (task.status == TransmissionStatus.SENDING || task.status == TransmissionStatus.RESUMING || task.status == TransmissionStatus.CONNECTING || task.status == TransmissionStatus.WAITING_RECONNECT || task.status == TransmissionStatus.PAUSED)) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task.status == TransmissionStatus.PAUSED) {
                        TextButton(
                            onClick = { viewModel.resumeTask(task) }
                        ) {
                            Text(com.example.utils.stringResource("btn_resume"), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = { viewModel.pauseTask(task) }
                        ) {
                            Text(com.example.utils.stringResource("btn_pause"), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { viewModel.cancelTask(task) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(com.example.utils.stringResource("btn_cancel_task"), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (task.status == TransmissionStatus.SUCCESS) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            try {
                                val file = java.io.File(task.fileUri)
                                if (!file.exists()) {
                                    android.widget.Toast.makeText(context, "文件不存在: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                        androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                    } else {
                                        android.net.Uri.fromFile(file)
                                    }
                                    val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
                                        
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mimeType)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "选择打开方式"))
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "打开失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open file",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开文件")
                    }
                        
                    Spacer(modifier = Modifier.width(8.dp))
                        
                    TextButton(
                        onClick = {
                            try {
                                val file = java.io.File(task.fileUri)
                                if (!file.exists()) {
                                    android.widget.Toast.makeText(context, "文件不存在: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                        androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                    } else {
                                        android.net.Uri.fromFile(file)
                                    }
                                    val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
                                        
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = mimeType
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "分享文件"))
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "分享失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share file",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("分享")
                    }
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    initialLevel: Int,
    onBack: () -> Unit
) {
    var level by remember { mutableIntStateOf(initialLevel) }
    val storagePath by viewModel.storagePath.collectAsState()
    val customColors by viewModel.customColors.collectAsState()
    val currentThemeColor by viewModel.themeColor.collectAsState()
    val appLanguageFlow by viewModel.appLanguage.collectAsState()
    val maxConcurrency by viewModel.maxConcurrency.collectAsState()
    
    var showColorAddDialog by remember { mutableStateOf(false) }
    var colorToDelete by remember { mutableStateOf<String?>(null) }
    var showStoragePathDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (level == 0 || level == 3) {
            onBack()
        } else {
            level = 0
        }
    }

    Scaffold(
        modifier = Modifier
            .statusBarsPadding()
            .navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    val titleText = when (level) {
                        0 -> com.example.utils.stringResource("settings")
                        1 -> com.example.utils.stringResource("color_setting")
                        2 -> com.example.utils.stringResource("lang_setting")
                        3 -> com.example.utils.stringResource("about_app")
                        4 -> "运行及异常日志"
                        else -> com.example.utils.stringResource("settings")
                    }
                    Text(titleText, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (level == 0 || level == 3) {
                            onBack()
                        } else {
                            level = 0
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = level,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(220)))
                            .togetherWith(slideOutHorizontally { width -> -width } + fadeOut(animationSpec = tween(220)))
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn(animationSpec = tween(220)))
                            .togetherWith(slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(220)))
                    }
                },
                label = "settings_level_transition",
                modifier = Modifier.fillMaxSize()
            ) { activeLevel ->
                when (activeLevel) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showStoragePathDialog = true },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(com.example.utils.stringResource("set_storage_position"), fontWeight = FontWeight.Bold) },
                                supportingContent = { 
                                    Text(
                                        text = com.example.utils.stringResource("current_path_format").format(storagePath),
                                        style = MaterialTheme.typography.bodySmall,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1
                                    ) 
                                },
                                leadingContent = { Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
                            )
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            var isAllFilesGranted by remember {
                                mutableStateOf(android.os.Environment.isExternalStorageManager())
                            }
                            val context = LocalContext.current
                            LaunchedEffect(Unit) {
                                isAllFilesGranted = android.os.Environment.isExternalStorageManager()
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                context.startActivity(intent)
                                            } catch (ex: Exception) {
                                                ex.printStackTrace()
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isAllFilesGranted) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                    }
                                )
                            ) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = {
                                        Text(
                                            if (com.example.utils.LocalAppLanguage.current == "zh") "文件管理权限 (All Files Access)"
                                            else "All Files Access Status",
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            if (com.example.utils.LocalAppLanguage.current == "zh") {
                                                if (isAllFilesGranted) "已授予 - 允许直接保存到系统 Download 文件夹"
                                                else "未授予 - 无法写入公共 Download 目录，点击去授权"
                                            } else {
                                                if (isAllFilesGranted) "Granted - Directly save to system Downloads"
                                                else "Not Granted - Unable to write to Downloads, click to authorize"
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            if (isAllFilesGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (isAllFilesGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    },
                                    trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
                                )
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("同时传输任务数上限", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text("设置同时最大并发传输的文件数。较小数值可减轻老旧旧设备处理与内存压力，防止发热或断连。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                val concurrencyLimits = listOf(1, 2, 3, 4, 6, 8)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    concurrencyLimits.forEach { limit ->
                                        val isSelected = maxConcurrency == limit
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .clickable {
                                                    viewModel.setMaxConcurrency(limit)
                                                },
                                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Text(
                                                    text = limit.toString(),
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("最大文件传输速度速率限制", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text("可以配置限制最高传输的速度速率（MB/s），输入 -1 或滑动到最左侧设为无限制（-1）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                
                                val speedLimitBytesPerSec by com.example.network.TransferStateManager.speedLimitBytesPerSec.collectAsState()
                                val isUnlimited = speedLimitBytesPerSec <= 0L
                                val currentMBs = if (isUnlimited) 0f else (speedLimitBytesPerSec.toDouble() / (1024.0 * 1024.0)).toFloat()
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Slider(
                                        value = if (isUnlimited) 0f else currentMBs.coerceIn(0f, 100f),
                                        onValueChange = { valMBs ->
                                            val roundedMBs = Math.round(valMBs * 10) / 10f
                                            com.example.network.TransferStateManager.speedLimitBytesPerSec.value = if (roundedMBs < 0.1f) -1L else (roundedMBs * 1024L * 1024L).toLong()
                                        },
                                        valueRange = 0f..100f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    var speedInputText by remember(speedLimitBytesPerSec) { 
                                        mutableStateOf(if (isUnlimited) "-1" else String.format("%.1f", currentMBs)) 
                                    }
                                    
                                    OutlinedTextField(
                                        value = speedInputText,
                                        onValueChange = { input ->
                                            speedInputText = input
                                            val parsed = speedInputText.toFloatOrNull()
                                            if (parsed != null) {
                                                com.example.network.TransferStateManager.speedLimitBytesPerSec.value = if (parsed <= 0f) -1L else (parsed * 1024L * 1024L).toLong()
                                            }
                                        },
                                        singleLine = true,
                                        suffix = { Text("MB/s", style = MaterialTheme.typography.bodySmall) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                        modifier = Modifier.width(110.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { level = 1 },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(com.example.utils.stringResource("color_setting"), fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
                            )
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { level = 2 },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(com.example.utils.stringResource("lang_setting"), fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
                            )
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { level = 3 },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text("运行及异常日志", fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
                            )
                        }
                    }
                }
                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = com.example.utils.stringResource("lang_system"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (currentThemeColor == "SYSTEM") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setThemeColor("SYSTEM") }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFF3F51B5),
                                                    Color(0xFF00BCD4),
                                                    Color(0xFF4CAF50),
                                                    Color(0xFFFFEB3B),
                                                    Color(0xFFFF5722)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentThemeColor == "SYSTEM") {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = com.example.utils.stringResource("theme_system"),
                                        fontWeight = if (currentThemeColor == "SYSTEM") FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = com.example.utils.stringResource("theme_system_desc"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        
                        Text(
                            text = com.example.utils.stringResource("preset_colors"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        val presetColors = listOf(
                            "#0061A4" to "默认蓝 (Slate Blue)",
                            "#2E7D32" to "绿色 (Emerald)",
                            "#D84315" to "落日橙 (Sunset)",
                            "#6A1B9A" to "丁香紫 (Lavender)",
                            "#FF8F00" to "琥珀金 (Amber)",
                            "#C62828" to "绯红红 (Crimson)",
                            "#E91E63" to "樱花粉 (Sakura Pink)"
                        )
                        
                        presetColors.forEach { (colorHex, colorName) ->
                            val colorItem = Color(android.graphics.Color.parseColor(colorHex))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (currentThemeColor == colorHex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setThemeColor(colorHex) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(colorItem),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (currentThemeColor == colorHex) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = if (ColorUtils.calculateLuminance(colorItem.toArgb()) > 0.5) Color.Black else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = colorName,
                                        fontWeight = if (currentThemeColor == colorHex) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = com.example.utils.stringResource("custom_colors"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        if (customColors.isEmpty()) {
                            Text(
                                text = com.example.utils.stringResource("custom_colors_empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )
                        } else {
                            customColors.forEach { colorHex ->
                                val colorItem = try {
                                    Color(android.graphics.Color.parseColor(colorHex))
                                } catch (e: Exception) {
                                    Color.Gray
                                }
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (currentThemeColor == colorHex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { viewModel.setThemeColor(colorHex) },
                                            onLongClick = { colorToDelete = colorHex }
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(colorItem),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (currentThemeColor == colorHex) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = if (ColorUtils.calculateLuminance(colorItem.toArgb()) > 0.5) Color.Black else Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = colorHex,
                                            fontWeight = if (currentThemeColor == colorHex) FontWeight.Bold else FontWeight.Normal,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                        
                        Button(
                            onClick = { showColorAddDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.example.utils.stringResource("add_custom_color_btn"))
                        }
                    }
                }
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val languagesList = listOf(
                            com.example.utils.AppLanguage.SYSTEM,
                            com.example.utils.AppLanguage.ZH,
                            com.example.utils.AppLanguage.EN,
                            com.example.utils.AppLanguage.JA,
                            com.example.utils.AppLanguage.RU
                        )
                        
                        languagesList.forEach { languageOpt ->
                            val isSelected = appLanguageFlow == languageOpt.code
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        viewModel.setAppLanguage(languageOpt.code)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = languageOpt.nativeName,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        val subText = when (languageOpt) {
                                            com.example.utils.AppLanguage.SYSTEM -> com.example.utils.stringResource("lang_system_sub")
                                            com.example.utils.AppLanguage.ZH -> "中文简体 (Simplified Chinese)"
                                            com.example.utils.AppLanguage.EN -> "English LANGUAGE"
                                            com.example.utils.AppLanguage.JA -> "日本語のインターフェース (Japanese)"
                                            com.example.utils.AppLanguage.RU -> "Русский интерфейс (Russian)"
                                        }
                                        Text(
                                            text = subText,
                                            modifier = Modifier.padding(top = 2.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    com.example.ui.LogViewerContent(
                        onBack = { level = 0 }
                    )
                }
            }
        }
        }
    }

    var showFolderPicker by remember { mutableStateOf(false) }

    if (showFolderPicker) {
        com.example.ui.InAppFolderPickerDialog(
            initialPath = storagePath,
            onDismissRequest = { showFolderPicker = false },
            onConfirmFolder = { selectedFolder ->
                viewModel.setStoragePath(selectedFolder)
                showFolderPicker = false
                showStoragePathDialog = false
            }
        )
    }

    if (showStoragePathDialog) {
        var tempPath by remember { mutableStateOf(storagePath) }
        
        AlertDialog(
            onDismissRequest = { showStoragePathDialog = false },
            confirmButton = {
                Button(onClick = {
                    viewModel.setStoragePath(tempPath)
                    showStoragePathDialog = false
                }) {
                    Text(com.example.utils.stringResource("btn_save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePathDialog = false }) {
                    Text(com.example.utils.stringResource("btn_cancel"))
                }
            },
            title = { Text(com.example.utils.stringResource("set_storage_path")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(com.example.utils.stringResource("set_storage_position"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = tempPath,
                        onValueChange = { tempPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(com.example.utils.stringResource("storage_path_label")) },
                        singleLine = true
                    )

                    FilledTonalButton(
                        onClick = {
                            showFolderPicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("浏览文件夹选择")
                    }

                    val hintText = if (com.example.utils.LocalAppLanguage.current == "zh") {
                        "说明：您可以手动输入自定义路径，也可以点击上方按钮进行文件夹浏览选择。"
                    } else if (com.example.utils.LocalAppLanguage.current == "ja") {
                        "説明：パスを直接入力するか、上のボタンからフォルダを参照して選択できます。"
                    } else if (com.example.utils.LocalAppLanguage.current == "ru") {
                        "Примечание: вы можете ввести путь вручную или нажать кнопку выше для выбора папки."
                    } else {
                        "Note: You can enter the path manually or click the button above to browse and select a folder."
                    }
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showFolderPicker) {
        com.example.ui.InAppFolderPickerDialog(
            initialPath = storagePath,
            onDismissRequest = { showFolderPicker = false },
            onConfirmFolder = { selectedFolderPath ->
                viewModel.setStoragePath(selectedFolderPath)
                showFolderPicker = false
            }
        )
    }

    if (showColorAddDialog) {
        var inputHex by remember { mutableStateOf("") }
        var isHexError by remember { mutableStateOf(false) }
        
        val previewColor = remember(inputHex) {
            try {
                val formatted = if (inputHex.startsWith("#")) inputHex else "#$inputHex"
                Color(android.graphics.Color.parseColor(formatted))
            } catch (e: Exception) {
                null
            }
        }
        
        AlertDialog(
            onDismissRequest = { showColorAddDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val formatted = if (inputHex.startsWith("#")) inputHex else "#$inputHex"
                        if (previewColor != null) {
                            viewModel.addCustomColor(formatted)
                            showColorAddDialog = false
                            isHexError = false
                        } else {
                            isHexError = true
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showColorAddDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("添加自定义颜色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入自定义颜色的十六进制 (HEX) 代码 (例如: #E040FB 或 E040FB)")
                    OutlinedTextField(
                        value = inputHex,
                        onValueChange = { 
                            inputHex = it 
                            isHexError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        isError = isHexError,
                        label = { Text("颜色十六进制") },
                        placeholder = { Text("E040FB") }
                    )
                    
                    if (isHexError) {
                        Text("请输入有效的十六进制颜色代码！", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    
                    if (previewColor != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("预览: ")
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(previewColor)
                            )
                        }
                    }
                }
            }
        )
    }

    if (colorToDelete != null) {
        val displayColor = colorToDelete ?: ""
        AlertDialog(
            onDismissRequest = { colorToDelete = null },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeCustomColor(displayColor)
                        colorToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { colorToDelete = null }) {
                    Text("取消")
                }
            },
            title = { Text("删除自定义颜色") },
            text = {
                Text("您确定要删除自定义颜色 $displayColor 吗？如果当前处于选中状态，删除后软件将自动恢复成默认颜色设定。")
            }
        )
    }
}

@Composable
fun StackedTransferTasksSection(
    transferringTasks: List<TransmissionTask>,
    viewModel: MainViewModel
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = com.example.utils.stringResource("transfer_tasks"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (transferringTasks.size > 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${transferringTasks.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (transferringTasks.size > 1) {
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isExpanded) "收起卡片" else "重叠视图",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (transferringTasks.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val emptyTransferMsg = when (com.example.utils.LocalAppLanguage.current) {
                        "zh" -> "暂时还没有文件正在传输中哦~(•ᴗ•)"
                        "ja" -> "現在、転送中のファイルはありません~(•ᴗ•)"
                        "ru" -> "В данный момент нет активных передач~(•ᴗ•)"
                        else -> "No files are currently transferring~(•ᴗ•)"
                    }
                    Text(
                        text = emptyTransferMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (transferringTasks.size == 1 || isExpanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.animateContentSize()
            ) {
                transferringTasks.forEach { task ->
                    TaskCard(task = task, viewModel = viewModel)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                val stackLayers = minOf(3, transferringTasks.size)
                for (i in (stackLayers - 1) downTo 1) {
                    val bgTask = transferringTasks[i]
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f - (i * 0.15f))
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = (i * 12).dp.toPx()
                                scaleX = 1f - (i * 0.04f)
                                alpha = 1f - (i * 0.2f)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bgTask.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "后台重叠排队中...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                TaskCard(task = transferringTasks.first(), viewModel = viewModel)
            }
        }
    }
}

@Composable
fun HistoryTasksSection(
    historyTasks: List<TransmissionTask>,
    viewModel: MainViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val filteredHistory = remember(historyTasks, selectedTab) {
        if (selectedTab == 0) {
            historyTasks.filter { !it.isSender }
        } else {
            historyTasks.filter { it.isSender }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = com.example.utils.stringResource("transfer_history"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.SouthWest,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = com.example.utils.stringResource("history_incoming"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.NorthEast,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = com.example.utils.stringResource("history_outgoing"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (filteredHistory.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTab == 0) com.example.utils.stringResource("no_incoming_history") else com.example.utils.stringResource("no_outgoing_history"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.animateContentSize()
            ) {
                filteredHistory.forEach { task ->
                    TaskCard(task = task, viewModel = viewModel)
                }
            }
        }
    }
}

private data class ParticleData(
    val startX: Float,
    val startY: Float,
    val controlX: Float,
    val controlY: Float,
    val targetX: Float,
    val targetY: Float,
    val size: Float,
    val color: Color,
    val delayFraction: Float,
    val speedFactor: Float,
    val shapeType: Int
)

@Composable
fun ParticleFlyEffectOverlay(
    isTriggered: Boolean,
    onAnimationEnd: () -> Unit
) {
    if (!isTriggered) return

    val animProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val startX = screenWidthPx * 0.5f
    val startY = screenHeightPx * 0.68f

    val targetX = screenWidthPx * 0.5f
    val targetY = screenHeightPx * 0.40f

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val particles = remember(screenWidthPx, screenHeightPx) {
        val colors = listOf(
            primaryColor,
            tertiaryColor,
            Color(0xFF00E5FF),
            Color(0xFFFFD700),
            Color(0xFF7C4DFF)
        )
        List(32) { i ->
            val angle = Math.toRadians((i * (360.0 / 32) + kotlin.random.Random.nextDouble(-15.0, 15.0)))
            val spreadRadius = kotlin.random.Random.nextFloat() * 180f - 90f

            val ctrlX = ((startX + targetX) / 2f) + (Math.cos(angle) * (120f + spreadRadius)).toFloat()
            val ctrlY = ((startY + targetY) / 2f) + kotlin.random.Random.nextFloat() * -100f

            ParticleData(
                startX = startX + kotlin.random.Random.nextFloat() * 80f - 40f,
                startY = startY + kotlin.random.Random.nextFloat() * 40f - 20f,
                controlX = ctrlX,
                controlY = ctrlY,
                targetX = targetX + kotlin.random.Random.nextFloat() * 60f - 30f,
                targetY = targetY + kotlin.random.Random.nextFloat() * 30f - 15f,
                size = kotlin.random.Random.nextFloat() * 10f + 6f,
                color = colors[i % colors.size],
                delayFraction = kotlin.random.Random.nextFloat() * 0.25f,
                speedFactor = kotlin.random.Random.nextFloat() * 0.3f + 0.85f,
                shapeType = i % 3
            )
        }
    }

    LaunchedEffect(isTriggered) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1200, easing = LinearOutSlowInEasing)
        )
        onAnimationEnd()
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {}
    ) {
        val globalProgress = animProgress.value
        if (globalProgress <= 0f || globalProgress >= 1f) return@Canvas

        particles.forEach { p ->
            val effectiveProgress = ((globalProgress - p.delayFraction) / (1f - p.delayFraction) * p.speedFactor)
                .coerceIn(0f, 1f)

            if (effectiveProgress in 0.001f..0.999f) {
                val t = effectiveProgress
                val oneMinusT = 1f - t

                val px = oneMinusT * oneMinusT * p.startX + 2 * oneMinusT * t * p.controlX + t * t * p.targetX
                val py = oneMinusT * oneMinusT * p.startY + 2 * oneMinusT * t * p.controlY + t * t * p.targetY

                val alpha = (1f - (t * t)).coerceIn(0f, 1f)
                val currentSize = p.size * (1f + (1f - t) * 0.5f)

                val prevT = (t - 0.05f).coerceAtLeast(0f)
                val prevOneMinusT = 1f - prevT
                val prevPx = prevOneMinusT * prevOneMinusT * p.startX + 2 * prevOneMinusT * prevT * p.controlX + prevT * prevT * p.targetX
                val prevPy = prevOneMinusT * prevOneMinusT * p.startY + 2 * prevOneMinusT * prevT * p.controlY + prevT * prevT * p.targetY

                drawLine(
                    color = p.color.copy(alpha = alpha * 0.4f),
                    start = androidx.compose.ui.geometry.Offset(prevPx, prevPy),
                    end = androidx.compose.ui.geometry.Offset(px, py),
                    strokeWidth = currentSize * 0.6f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )

                if (p.shapeType == 1) {
                    val path = Path().apply {
                        moveTo(px, py - currentSize)
                        lineTo(px + currentSize * 0.4f, py - currentSize * 0.4f)
                        lineTo(px + currentSize, py)
                        lineTo(px + currentSize * 0.4f, py + currentSize * 0.4f)
                        lineTo(px, py + currentSize)
                        lineTo(px - currentSize * 0.4f, py + currentSize * 0.4f)
                        lineTo(px - currentSize, py)
                        lineTo(px - currentSize * 0.4f, py - currentSize * 0.4f)
                        close()
                    }
                    drawPath(path = path, color = p.color.copy(alpha = alpha))
                } else {
                    drawCircle(
                        color = p.color.copy(alpha = alpha * 0.3f),
                        radius = currentSize * 1.8f,
                        center = androidx.compose.ui.geometry.Offset(px, py)
                    )
                    drawCircle(
                        color = p.color.copy(alpha = alpha),
                        radius = currentSize,
                        center = androidx.compose.ui.geometry.Offset(px, py)
                    )
                }
            }
        }

        if (globalProgress > 0.6f) {
            val rippleProgress = ((globalProgress - 0.6f) / 0.4f).coerceIn(0f, 1f)
            val rippleRadius = 40f + rippleProgress * 70f
            val rippleAlpha = (1f - rippleProgress).coerceIn(0f, 1f)

            drawCircle(
                color = primaryColor.copy(alpha = rippleAlpha * 0.6f),
                radius = rippleRadius,
                center = androidx.compose.ui.geometry.Offset(targetX, targetY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
            )
        }
    }
}

@Composable
fun WalkieTalkieIcon(
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        
        // Antenna
        val antennaWidth = w * 0.12f
        val antennaHeight = h * 0.22f
        drawRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.02f),
            size = androidx.compose.ui.geometry.Size(antennaWidth, antennaHeight)
        )
        
        // Knob
        val knobWidth = w * 0.14f
        val knobHeight = h * 0.10f
        drawRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.14f),
            size = androidx.compose.ui.geometry.Size(knobWidth, knobHeight)
        )
        
        // Body
        val bodyTop = h * 0.24f
        val bodyHeight = h * 0.74f
        val bodyWidth = w * 0.74f
        val bodyLeft = w * 0.13f
        
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(bodyLeft, bodyTop),
            size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, w * 0.12f)
        )
        
        // Inner screen
        val screenTop = bodyTop + bodyHeight * 0.15f
        val screenHeight = bodyHeight * 0.25f
        val screenWidth = bodyWidth * 0.72f
        val screenLeft = bodyLeft + bodyWidth * 0.14f
        
        drawRoundRect(
            color = if (tint == Color.White) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.5f),
            topLeft = androidx.compose.ui.geometry.Offset(screenLeft, screenTop),
            size = androidx.compose.ui.geometry.Size(screenWidth, screenHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
        )
        
        // Screen detail line (antenna/wave indicator)
        drawLine(
            color = tint.copy(alpha = 0.6f),
            start = androidx.compose.ui.geometry.Offset(screenLeft + screenWidth * 0.2f, screenTop + screenHeight * 0.5f),
            end = androidx.compose.ui.geometry.Offset(screenLeft + screenWidth * 0.4f, screenTop + screenHeight * 0.5f),
            strokeWidth = h * 0.02f
        )
        drawLine(
            color = tint.copy(alpha = 0.6f),
            start = androidx.compose.ui.geometry.Offset(screenLeft + screenWidth * 0.5f, screenTop + screenHeight * 0.3f),
            end = androidx.compose.ui.geometry.Offset(screenLeft + screenWidth * 0.5f, screenTop + screenHeight * 0.7f),
            strokeWidth = h * 0.02f
        )
        
        // Speaker Grille lines (below screen)
        val grilleStartTop = screenTop + screenHeight + bodyHeight * 0.14f
        val lineSpacing = bodyHeight * 0.09f
        val stripeWidth = bodyWidth * 0.48f
        val stripeLeft = bodyLeft + bodyWidth * 0.26f
        val strokeW = h * 0.025f
        
        for (i in 0..2) {
            val y = grilleStartTop + i * lineSpacing
            drawLine(
                color = if (tint == Color.White) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.5f),
                start = androidx.compose.ui.geometry.Offset(stripeLeft, y),
                end = androidx.compose.ui.geometry.Offset(stripeLeft + stripeWidth, y),
                strokeWidth = strokeW
            )
        }
    }
}
