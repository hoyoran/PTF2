package com.example.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewConfiguration
import android.view.MotionEvent
import androidx.lifecycle.*
import androidx.savedstate.*
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.model.TransmissionStatus
import com.example.model.TransmissionTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class TransferForegroundService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    
    companion object {
        const val CHANNEL_ID = "ptf_transfer_channel"
        const val NOTIFICATION_ID = 8899
        
        const val ACTION_START_FLOATING = "ACTION_START_FLOATING"
        const val ACTION_STOP_FLOATING = "ACTION_STOP_FLOATING"

        const val EXTRA_FLOATING_TYPE = "EXTRA_FLOATING_TYPE"
        const val FLOATING_TYPE_TRANSFER = "FLOATING_TYPE_TRANSFER"
        const val FLOATING_TYPE_WALKIE_TALKIE = "FLOATING_TYPE_WALKIE_TALKIE"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val myViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = myViewModelStore
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingX = 100f
    private var floatingY = 600f
    private var currentFloatingType: String? = null
    private var floatingUpdatesJob: Job? = null

    private var tcpServer: TcpServerManager? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        createNotificationChannel()
        try {
            val notification = buildNotification("准备就绪", "PTF 传输后台保活服务已启动")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            com.example.utils.AppLogger.info("FOREGROUND_SERVICE", "TransferForegroundService startForeground completed successfully")
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("FOREGROUND_SERVICE", "startForeground failed in TransferForegroundService", e)
        }

        // Initialize TCP Client, Server, and Queue Manager under service lifecycle
        val dao = AppDatabase.getDatabase(this).transmissionTaskDao()
        val repository = com.example.data.TransmissionRepository(dao)
        val tcpClient = TcpClientManager(this, repository)
        val selfDeviceId = TransferStateManager.getOrCreateDeviceId(this)

        val server = TcpServerManager(this, repository, serviceScope) { request, callback ->
            val dId = request.optString("deviceId")
            val fName = request.optString("fileName")
            val fSize = request.optLong("fileSize")
            val gId = request.optString("groupId")
            val gCount = request.optInt("groupFileCount", 1)
            TransferStateManager.activePairingRequest.value = PairingRequest(
                deviceId = dId,
                fileName = fName,
                fileSize = fSize,
                groupId = if (gId.isEmpty()) null else gId,
                groupFileCount = gCount,
                callback = callback
            )
        }
        tcpServer = server

        serviceScope.launch {
            server.startServer()
        }

        val prefs = getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        val savedLimit = prefs.getLong("speed_limit_bytes", -1L)
        TransferStateManager.speedLimitBytesPerSec.value = if (savedLimit == 0L) -1L else savedLimit
        TransferStateManager.floatingAlpha.value = prefs.getFloat("floating_alpha", 1.0f)
        TransferStateManager.floatingSize.value = prefs.getFloat("floating_size", 60f)
        
        serviceScope.launch {
            TransferStateManager.speedLimitBytesPerSec.collect { limit ->
                prefs.edit().putLong("speed_limit_bytes", limit).apply()
            }
        }
        serviceScope.launch {
            TransferStateManager.floatingAlpha.collect { alpha ->
                prefs.edit().putFloat("floating_alpha", alpha).apply()
            }
        }
        serviceScope.launch {
            TransferStateManager.floatingSize.collect { size ->
                prefs.edit().putFloat("floating_size", size).apply()
            }
        }

        TransferQueueManager.start(this, repository, tcpClient, selfDeviceId)

        // Observe tasks in DB to calculate detailed states
        serviceScope.launch {
            var lastTotalReadOffset = 0L
            var lastCalculationTime = System.currentTimeMillis()
            var lastCollectedTasks = emptyList<TransmissionTask>()
            
            // Gather items asynchronously
            launch {
                dao.getAllTasks().collect { tasks ->
                    lastCollectedTasks = tasks
                    TransferStateManager.allQueueTasks.value = tasks
                }
            }
            
            // Calc stats & maintain locks every 1 second
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val activeTasks = lastCollectedTasks.filter {
                    it.status == TransmissionStatus.CONNECTING ||
                    it.status == TransmissionStatus.SENDING ||
                    it.status == TransmissionStatus.RESUMING
                }
                
                val remainingTasks = lastCollectedTasks.filter {
                    it.status == TransmissionStatus.CONNECTING ||
                    it.status == TransmissionStatus.SENDING ||
                    it.status == TransmissionStatus.RESUMING ||
                    it.status == TransmissionStatus.WAITING_RECONNECT
                }
                TransferStateManager.remainingFilesCount.value = remainingTasks.size
                
                if (activeTasks.isNotEmpty()) {
                    acquireLocks()
                    val first = activeTasks.first()
                    updateNotification("正在传输: ${activeTasks.size}个项目", first.fileName)
                } else {
                    releaseLocks()
                    updateNotification("准备就绪", "无活跃的文件传输任务")
                }

                // Speed estimation logic (bytes per second)
                val totalCurrentOffset = activeTasks.sumOf { it.offset }
                val timeDiffSec = (now - lastCalculationTime) / 1000.0
                lastCalculationTime = now
                
                val delta = if (lastTotalReadOffset > 0 && totalCurrentOffset >= lastTotalReadOffset) {
                    totalCurrentOffset - lastTotalReadOffset
                } else 0L
                lastTotalReadOffset = totalCurrentOffset
                
                val speedVal = if (timeDiffSec > 0) (delta / timeDiffSec).toLong() else delta
                TransferStateManager.currentSpeedBytesPerSec.value = speedVal
                val formattedSpeed = formatSpeed(speedVal)
                TransferStateManager.speedText.value = formattedSpeed
                
                if (activeTasks.isNotEmpty()) {
                    val first = activeTasks.first()
                    val remainingBytes = first.fileSize - first.offset
                    if (speedVal > 0 && remainingBytes > 0) {
                        val remSeconds = remainingBytes / speedVal
                        val mm = remSeconds / 60
                        val ss = remSeconds % 60
                        TransferStateManager.remainingTimeText.value = if (mm > 0) "${mm}分${ss}秒" else "${ss}秒"
                    } else {
                        TransferStateManager.remainingTimeText.value = "计算中..."
                    }
                    
                    // Push states
                    TransferStateManager.isUploadingOrDownloading.value = true
                    TransferStateManager.isSending.value = first.isSender
                    TransferStateManager.activeFileName.value = first.fileName
                    TransferStateManager.activeFileSize.value = first.fileSize
                    TransferStateManager.activeBytesTransferred.value = first.offset
                    val progress = if (first.fileSize > 0) first.offset.toFloat() / first.fileSize.toFloat() else 0f
                    TransferStateManager.activeProgress.value = progress.coerceIn(0f, 1f)
                } else {
                    TransferStateManager.remainingTimeText.value = "-"
                    TransferStateManager.isUploadingOrDownloading.value = false
                    TransferStateManager.activeFileName.value = ""
                    TransferStateManager.activeFileSize.value = 0L
                    TransferStateManager.activeBytesTransferred.value = 0L
                    TransferStateManager.activeProgress.value = 0f
                }
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val prefs = getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        val floatingType = intent?.getStringExtra(EXTRA_FLOATING_TYPE)
            ?: prefs.getString("floating_mode", FLOATING_TYPE_TRANSFER)
            ?: FLOATING_TYPE_TRANSFER

        if (action == ACTION_START_FLOATING) {
            prefs.edit().putString("floating_mode", floatingType).apply()
            showFloatingController(floatingType)
        } else if (action == ACTION_STOP_FLOATING) {
            removeFloatingController()
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PTF:TransferKeepAlive").apply {
                    acquire()
                }
                com.example.utils.AppLogger.info("FOREGROUND_SERVICE", "WakeLock successfully acquired")
            } catch (e: Throwable) {
                com.example.utils.AppLogger.error("FOREGROUND_SERVICE", "Failed to acquire WakeLock (likely permission issue or restrictions)", e)
                wakeLock = null
            }
        }
        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PTF:WifiHighPerf")
                } else {
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "PTF:WifiLock")
                }.apply {
                    acquire()
                }
                com.example.utils.AppLogger.info("FOREGROUND_SERVICE", "WifiLock successfully acquired")
            } catch (e: Throwable) {
                com.example.utils.AppLogger.error("FOREGROUND_SERVICE", "Failed to acquire WifiLock", e)
                wifiLock = null
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.run { if (isHeld) release() }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("FOREGROUND_SERVICE", "Failed to release WakeLock gracefully", e)
        } finally {
            wakeLock = null
        }

        try {
            wifiLock?.run { if (isHeld) release() }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("FOREGROUND_SERVICE", "Failed to release WifiLock gracefully", e)
        } finally {
            wifiLock = null
        }
    }

    private fun showFloatingController(type: String = FLOATING_TYPE_TRANSFER) {
        com.example.utils.AppLogger.info("FLOATING_WINDOW", "showFloatingController initiated with type: $type")
        try {
            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else {
                true
            }
            if (!hasOverlay) {
                com.example.utils.AppLogger.warn("FLOATING_WINDOW", "Overlay permission missing, cannot add floating view")
                return
            }

            if (floatingView != null) {
                if (currentFloatingType == type) {
                    com.example.utils.AppLogger.info("FLOATING_WINDOW", "floatingView of type $type already exists, skipping creation")
                    return
                } else {
                    com.example.utils.AppLogger.info("FLOATING_WINDOW", "Floating type changed from $currentFloatingType to $type, removing previous view")
                    removeFloatingController()
                }
            }

            currentFloatingType = type
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            floatingParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 600
            }
            floatingX = 100f
            floatingY = 600f

            TransferStateManager.collapsedState.value = true

            val density = resources.displayMetrics.density
            val bubbleSizePx = (58 * density).toInt()

            val container = android.widget.FrameLayout(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(bubbleSizePx, bubbleSizePx)
                elevation = 8f * density

                val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#0061A4"))
                    setStroke((2 * density).toInt(), android.graphics.Color.parseColor("#60A5FA"))
                }
                background = shapeDrawable
            }

            val iconView = android.widget.ImageView(this).apply {
                val iconRes = if (type == FLOATING_TYPE_WALKIE_TALKIE) {
                    android.R.drawable.ic_btn_speak_now
                } else {
                    android.R.drawable.stat_sys_upload
                }
                setImageResource(iconRes)
                setColorFilter(android.graphics.Color.WHITE)
                val iconSizePx = (28 * density).toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    iconSizePx,
                    iconSizePx,
                    Gravity.CENTER
                )
            }

            container.addView(iconView)

            var lastClickTime = 0L
            var isLongPressing = false
            var singleTapRunnable: Runnable? = null
            var longPressRunnable: Runnable? = null
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            container.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false
                private val touchSlop = ViewConfiguration.get(this@TransferForegroundService).scaledTouchSlop

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(100).start()

                            initialX = floatingParams?.x ?: 0
                            initialY = floatingParams?.y ?: 0
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            isLongPressing = false

                            if (type == FLOATING_TYPE_WALKIE_TALKIE) {
                                longPressRunnable = Runnable {
                                    if (!isDragging) {
                                        isLongPressing = true
                                        WalkieTalkieAudioManager.startRecording(serviceScope)
                                        WalkieTalkieAudioManager.broadcastSpeakingState(true)
                                        android.widget.Toast.makeText(applicationContext, "🎙 正在讲话...", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                handler.postDelayed(longPressRunnable!!, 380L)
                            }
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (!isDragging && Math.sqrt((dx * dx + dy * dy).toDouble()) > touchSlop * 2f) {
                                isDragging = true
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                            }
                            if (isDragging) {
                                floatingParams?.let { params ->
                                    floatingX = initialX + dx
                                    floatingY = initialY + dy
                                    params.x = floatingX.toInt()
                                    params.y = floatingY.toInt()
                                    windowManager?.updateViewLayout(floatingView, params)
                                }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()

                            longPressRunnable?.let { handler.removeCallbacks(it) }

                            if (isLongPressing) {
                                isLongPressing = false
                                WalkieTalkieAudioManager.stopRecording()
                                WalkieTalkieAudioManager.broadcastSpeakingState(false)
                            } else if (!isDragging) {
                                val dx = event.rawX - initialTouchX
                                val dy = event.rawY - initialTouchY
                                val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

                                if (distance < touchSlop * 2.5f) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastClickTime < 320L) {
                                        singleTapRunnable?.let { handler.removeCallbacks(it) }
                                        if (type == FLOATING_TYPE_WALKIE_TALKIE) {
                                            val isLocked = TransferStateManager.isMicLockedState.value
                                            if (isLocked) {
                                                WalkieTalkieAudioManager.stopRecording()
                                                WalkieTalkieAudioManager.broadcastSpeakingState(false)
                                                TransferStateManager.isMicLockedState.value = false
                                                android.widget.Toast.makeText(applicationContext, "已解除麦克风锁定", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                TransferStateManager.isMicLockedState.value = true
                                                WalkieTalkieAudioManager.startRecording(serviceScope)
                                                WalkieTalkieAudioManager.broadcastSpeakingState(true)
                                                android.widget.Toast.makeText(applicationContext, "🔒 浮窗双击: 已锁定持续讲话", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            launchMainActivity(launchFilePicker = false, openWalkieTalkie = false)
                                        }
                                        lastClickTime = 0L
                                    } else {
                                        lastClickTime = now
                                        singleTapRunnable = Runnable {
                                            if (type == FLOATING_TYPE_WALKIE_TALKIE) {
                                                launchMainActivity(launchFilePicker = false, openWalkieTalkie = true)
                                            } else {
                                                launchMainActivity(launchFilePicker = false, openWalkieTalkie = false)
                                            }
                                        }
                                        handler.postDelayed(singleTapRunnable!!, 280L)
                                    }
                                }
                            } else {
                                snapToEdge()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            floatingView = container

            floatingUpdatesJob?.cancel()
            floatingUpdatesJob = serviceScope.launch {
                if (type == FLOATING_TYPE_WALKIE_TALKIE) {
                    launch {
                        TransferStateManager.isSpeakingState.collect { isSpeaking ->
                            val isMicLocked = TransferStateManager.isMicLockedState.value
                            val isAnyPeerSpeaking = TransferStateManager.isAnyPeerSpeaking()

                            val bg = container.background as? android.graphics.drawable.GradientDrawable
                            if (isSpeaking || isMicLocked) {
                                bg?.setColor(android.graphics.Color.parseColor("#0061A4"))
                                bg?.setStroke((2.5f * density).toInt(), android.graphics.Color.parseColor("#93C5FD"))
                            } else if (isAnyPeerSpeaking) {
                                bg?.setColor(android.graphics.Color.parseColor("#BA1A1A"))
                                bg?.setStroke((2.5f * density).toInt(), android.graphics.Color.parseColor("#FCA5A5"))
                            } else {
                                bg?.setColor(android.graphics.Color.parseColor("#0061A4"))
                                bg?.setStroke((2 * density).toInt(), android.graphics.Color.parseColor("#60A5FA"))
                            }
                        }
                    }
                } else {
                    launch {
                        TransferStateManager.isUploadingOrDownloading.collect { isTransferring ->
                            val bg = container.background as? android.graphics.drawable.GradientDrawable
                            if (isTransferring) {
                                bg?.setColor(android.graphics.Color.parseColor("#0061A4"))
                                bg?.setStroke((2.5f * density).toInt(), android.graphics.Color.parseColor("#93C5FD"))
                            } else {
                                bg?.setColor(android.graphics.Color.parseColor("#0061A4"))
                                bg?.setStroke((2 * density).toInt(), android.graphics.Color.parseColor("#60A5FA"))
                            }
                        }
                    }
                }

                launch {
                    TransferStateManager.floatingAlpha.collect { alpha ->
                        floatingParams?.let { params ->
                            params.alpha = alpha
                            floatingView?.let { v -> windowManager?.updateViewLayout(v, params) }
                        }
                    }
                }
            }

            try {
                com.example.utils.AppLogger.info("FLOATING_WINDOW", "Adding native floatingView ($type) to WindowManager...")
                windowManager?.addView(floatingView, floatingParams)
                com.example.utils.AppLogger.info("FLOATING_WINDOW", "Native floating window ($type) added successfully")
            } catch (e: Throwable) {
                com.example.utils.AppLogger.error("FLOATING_WINDOW", "Failed to add floating view to WindowManager", e)
                floatingView = null
                floatingParams = null
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("FLOATING_WINDOW", "Failed in showFloatingController", e)
            floatingView = null
            floatingParams = null
        }
    }

    private fun snapToEdge() {
        val params = floatingParams ?: return
        val view = floatingView ?: return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val density = metrics.density
        val cardWidthPx = (220 * density).toInt()

        val isPastEdge = floatingX < 12f * density || (floatingX + cardWidthPx) > (screenWidth - 12f * density)
        if (isPastEdge) {
            val side = if (floatingX + cardWidthPx / 2f < screenWidth / 2f) DockSide.LEFT else DockSide.RIGHT
            TransferStateManager.dockSideState.value = side
            TransferStateManager.isDockedState.value = true
            return
        }
        
        val targetX = if (floatingX + cardWidthPx / 2f < screenWidth / 2f) {
            12f * density
        } else {
            (screenWidth - cardWidthPx - 12f * density).toFloat()
        }
        
        serviceScope.launch {
            val startX = floatingX
            val duration = 250 // ms
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= duration) {
                    floatingX = targetX
                    params.x = targetX.toInt()
                    if (floatingView != null) {
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    break
                }
                val t = elapsed.toFloat() / duration
                val progress = 1f - Math.pow((1f - t).toDouble(), 3.0).toFloat()
                floatingX = startX + (targetX - startX) * progress
                params.x = floatingX.toInt()
                if (floatingView != null) {
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        break
                    }
                }
                delay(16)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (floatingView != null && floatingParams != null) {
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val density = metrics.density
            val floatingSizeVal = TransferStateManager.floatingSize.value
            val bubbleWidthPx = ((floatingSizeVal + 16) * density).toInt()

            if (floatingY > screenHeight - bubbleWidthPx) {
                floatingY = (screenHeight - bubbleWidthPx * 2).toFloat().coerceAtLeast(0f)
                floatingParams?.y = floatingY.toInt()
            }
            
            if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                snapToEdge()
            } else {
                if (floatingX > screenWidth - bubbleWidthPx) {
                    floatingX = (screenWidth - bubbleWidthPx).toFloat().coerceAtLeast(0f)
                    floatingParams?.x = floatingX.toInt()
                }
                windowManager?.updateViewLayout(floatingView, floatingParams)
            }
        }
    }

    private fun removeFloatingController() {
        com.example.utils.AppLogger.info("FLOATING_WINDOW", "removeFloatingController started")
        floatingUpdatesJob?.cancel()
        floatingUpdatesJob = null
        try {
            floatingView?.let { view ->
                windowManager?.removeView(view)
                com.example.utils.AppLogger.info("FLOATING_WINDOW", "floatingView successfully removed from WindowManager")
            }
        } catch (e: Throwable) {
            com.example.utils.AppLogger.error("FLOATING_WINDOW", "Failed to remove floatingView from WindowManager", e)
        } finally {
            floatingView = null
            floatingParams = null
            currentFloatingType = null
        }
    }

    private fun launchMainActivity(launchFilePicker: Boolean = false, openWalkieTalkie: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (launchFilePicker) {
                putExtra("LAUNCH_FILE_PICKER", true)
            }
            if (openWalkieTalkie) {
                putExtra("OPEN_WALKIE_TALKIE", true)
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    if (launchFilePicker) 1001 else if (openWalkieTalkie) 1003 else 1002,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件无线传输保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "确保处于后台或息屏状态下的传输不中断"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        removeFloatingController()
        try {
            tcpServer?.stop()
        } catch (e: Throwable) {}
        try {
            TransferQueueManager.stop()
        } catch (e: Throwable) {}
        serviceJob.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        myViewModelStore.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
