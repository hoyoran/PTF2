package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.TransmissionRepository
import com.example.model.TransmissionStatus
import com.example.model.TransmissionTask
import com.example.network.DeviceInfo
import com.example.network.UdpDiscoveryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update // 引入原子更新扩展
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.random.Random

data class PairingRequestDialogState(
    val isShowing: Boolean = false,
    val deviceId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val groupId: String? = null,
    val groupFileCount: Int = 1,
    val onAccept: () -> Unit = {},
    val onReject: () -> Unit = {}
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TransmissionRepository

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _myDeviceId = MutableStateFlow(com.example.network.TransferStateManager.getOrCreateDeviceId(application))
    val myDeviceId = _myDeviceId.asStateFlow()

    private val _pairingRequest = MutableStateFlow(PairingRequestDialogState())
    val pairingRequest: StateFlow<PairingRequestDialogState> = _pairingRequest.asStateFlow()

    private val _storagePath = MutableStateFlow("/storage/emulated/0/Download/")
    val storagePath: StateFlow<String> = _storagePath.asStateFlow()

    private val _themeColor = MutableStateFlow("#0061A4")
    val themeColor: StateFlow<String> = _themeColor.asStateFlow()

    private val _customColors = MutableStateFlow<List<String>>(emptyList())
    val customColors: StateFlow<List<String>> = _customColors.asStateFlow()

    private val _appLanguage = MutableStateFlow("SYSTEM")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _maxConcurrency = MutableStateFlow(3)
    val maxConcurrency: StateFlow<Int> = _maxConcurrency.asStateFlow()

    val allTasks: StateFlow<List<TransmissionTask>>

    private var udpManager: UdpDiscoveryManager? = null
    private var isDiscovering = false
    private var broadcastJob: Job? = null
    
    init {
        val database = AppDatabase.getDatabase(application)
        repository = TransmissionRepository(database.transmissionTaskDao())
        loadStoragePath()
        loadThemeAndColors()
        loadAppLanguage()
        loadMaxConcurrency()
        
        allTasks = repository.allTasks.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        viewModelScope.launch {
            allTasks.collect { tasks ->
                val remaining = tasks.filter {
                    it.status == TransmissionStatus.CONNECTING ||
                    it.status == TransmissionStatus.SENDING ||
                    it.status == TransmissionStatus.RESUMING ||
                    it.status == TransmissionStatus.WAITING_RECONNECT
                }
                com.example.network.TransferStateManager.remainingFilesCount.value = remaining.size
            }
        }

        viewModelScope.launch {
            _devices.collect { list ->
                com.example.network.TransferStateManager.discoveredDevicesCount.value = list.size
            }
        }

        // 定时清理离线设备列表
        viewModelScope.launch {
            while (true) {
                delay(3000)
                val now = System.currentTimeMillis()
                _devices.update { currentList ->
                    val fresh = currentList.filter { now - it.lastSeenTimestamp < 10000 }
                    if (fresh.size != currentList.size) {
                        com.example.network.TransferStateManager.discoveredDevices.value = fresh
                        fresh
                    } else {
                        currentList
                    }
                }
            }
        }

        // 监听底层服务上报的配对请求
        viewModelScope.launch {
            com.example.network.TransferStateManager.activePairingRequest.collect { activeReq ->
                if (activeReq != null) {
                    _pairingRequest.value = PairingRequestDialogState(
                        isShowing = true,
                        deviceId = activeReq.deviceId,
                        fileName = activeReq.fileName,
                        fileSize = activeReq.fileSize,
                        groupId = activeReq.groupId,
                        groupFileCount = activeReq.groupFileCount,
                        onAccept = {
                            _pairingRequest.value = PairingRequestDialogState()
                            activeReq.callback(true)
                            com.example.network.TransferStateManager.activePairingRequest.value = null
                        },
                        onReject = {
                            _pairingRequest.value = PairingRequestDialogState()
                            activeReq.callback(false)
                            com.example.network.TransferStateManager.activePairingRequest.value = null
                        }
                    )
                } else {
                    _pairingRequest.value = PairingRequestDialogState()
                }
            }
        }
    }

    private fun generateDeviceId(): String {
        return buildString {
            append((1..5).map { 
                if(Random.nextBoolean()) Random.nextInt(0, 10).toString() 
                else ('A'..'Z').random() 
            }.joinToString(""))
        }
    }

    // 修复：绕过高版本系统的 WifiManager 隐私限制，通过标准网络接口直接遍历获取真实的本机 IP
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddr = addr.hostAddress
                        if (!hostAddr.isNullOrEmpty()) {
                            return hostAddr
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            com.example.utils.AppLogger.error("MAIN_VIEW_MODEL", "Failed to get local IP via NetworkInterface", ex)
        }
        return "127.0.0.1" // 兜底降级处理
    }

    fun startDiscovery(context: Context) {
        if (isDiscovering) return
        isDiscovering = true
        
        // 修复：强行隔离 Activity 级 Context，直接锁定 Application 上下文以绝内存泄漏
        val appContext = context.applicationContext 
        val ip = getLocalIpAddress()
        
        udpManager = UdpDiscoveryManager(_myDeviceId.value, ip)
        
        viewModelScope.launch {
            udpManager?.startListening(
                onDeviceDiscovered = { newDevice ->
                    // 修复：使用原子更新规避高频 UDP 广播引发的 ConcurrentModificationException
                    _devices.update { currentList ->
                        val currentMutable = currentList.toMutableList()
                        val existing = currentMutable.find { it.deviceId == newDevice.deviceId }
                        if (existing != null) {
                            currentMutable[currentMutable.indexOf(existing)] = newDevice
                        } else {
                            currentMutable.add(newDevice)
                        }
                        val now = System.currentTimeMillis()
                        val filtered = currentMutable.filter { now - it.lastSeenTimestamp < 10000 }
                        com.example.network.TransferStateManager.discoveredDevices.value = filtered
                        filtered
                    }
                },
                onRoomDiscovered = { room, hostIp ->
                    com.example.network.TransferStateManager.addDiscoveredRoom(room, hostIp)
                }
            )
        }
        
        broadcastJob = viewModelScope.launch {
            while (true) {
                udpManager?.broadcastPresence()
                delay(2000)
            }
        }
    }

    fun stopDiscovery() {
        isDiscovering = false
        broadcastJob?.cancel()
        udpManager?.stop()
        udpManager = null
    }

    fun sendFileToDevice(device: DeviceInfo, uri: Uri) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val (name, size) = com.example.utils.FileUtil.getFileInfo(getApplication(), uri)
                    val hash = com.example.utils.FileUtil.getFileHash(getApplication(), uri)
                    val connectionId = UUID.randomUUID().toString()
                    val groupUuid = UUID.randomUUID().toString()
                    val task = TransmissionTask(
                        fileUri = uri.toString(),
                        fileName = name,
                        fileSize = size,
                        offset = 0L,
                        deviceId = device.deviceId,
                        connectionId = connectionId,
                        status = TransmissionStatus.IDLE,
                        isSender = true,
                        fileHash = hash,
                        timestamp = System.currentTimeMillis(),
                        groupId = groupUuid,
                        groupFileCount = 1
                    )
                    repository.insert(task)
                } catch (e: Exception) {
                    com.example.utils.AppLogger.error("MAIN_VIEW_MODEL", "Failed to queue single file", e)
                }
            }
        }
    }

    fun sendMultipleFilesToDevice(device: DeviceInfo, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val groupUuid = UUID.randomUUID().toString()
                val totalCount = uris.size
                uris.forEach { uri ->
                    try {
                        val (name, size) = com.example.utils.FileUtil.getFileInfo(getApplication(), uri)
                        val hash = com.example.utils.FileUtil.getFileHash(getApplication(), uri)
                        val connectionId = UUID.randomUUID().toString()
                        val task = TransmissionTask(
                            fileUri = uri.toString(),
                            fileName = name,
                            fileSize = size,
                            offset = 0L,
                            deviceId = device.deviceId,
                            connectionId = connectionId,
                            status = TransmissionStatus.IDLE,
                            isSender = true,
                            fileHash = hash,
                            timestamp = System.currentTimeMillis(),
                            groupId = groupUuid,
                            groupFileCount = totalCount
                        )
                        repository.insert(task)
                    } catch (e: Exception) {
                        com.example.utils.AppLogger.error("MAIN_VIEW_MODEL", "Failed to queue file from list", e)
                    }
                }
            }
        }
    }

    fun pauseTask(task: TransmissionTask) {
        viewModelScope.launch {
            repository.updateTaskStatus(task.id, TransmissionStatus.PAUSED)
        }
    }

    fun cancelTask(task: TransmissionTask) {
        viewModelScope.launch {
            repository.updateTaskStatus(task.id, TransmissionStatus.FAILED)
        }
    }

    fun deleteTask(task: TransmissionTask) {
        viewModelScope.launch {
            repository.deleteTaskById(task.id)
        }
    }

    fun resumeTask(task: TransmissionTask) {
        viewModelScope.launch {
            repository.updateTaskStatus(task.id, TransmissionStatus.WAITING_RECONNECT)
        }
    }

    fun loadStoragePath() {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        _storagePath.value = prefs.getString("storage_path", "/storage/emulated/0/Download/") ?: "/storage/emulated/0/Download/"
    }

    fun setStoragePath(path: String) {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("storage_path", path).apply()
        _storagePath.value = path
    }

    fun loadThemeAndColors() {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        _themeColor.value = prefs.getString("theme_color", "#0061A4") ?: "#0061A4"
        val colorsStr = prefs.getString("custom_colors", "") ?: ""
        if (colorsStr.isNotEmpty()) {
            _customColors.value = colorsStr.split(",").filter { it.isNotEmpty() }
        } else {
            _customColors.value = emptyList()
        }
    }

    fun setThemeColor(colorHex: String) {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("theme_color", colorHex).apply()
        _themeColor.value = colorHex
    }

    fun loadAppLanguage() {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        _appLanguage.value = prefs.getString("app_language", "SYSTEM") ?: "SYSTEM"
    }

    fun setAppLanguage(langCode: String) {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", langCode).apply()
        _appLanguage.value = langCode
    }

    fun addCustomColor(colorHex: String) {
        val cleaned = if (colorHex.startsWith("#")) colorHex.uppercase() else "#${colorHex.uppercase()}"
        if (cleaned.length == 7 || cleaned.length == 9) {
            val newList = _customColors.value.toMutableList()
            if (!newList.contains(cleaned)) {
                newList.add(cleaned)
                val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("custom_colors", newList.joinToString(",")).apply()
                _customColors.value = newList
            }
        }
    }

    fun removeCustomColor(colorHex: String) {
        val newList = _customColors.value.toMutableList()
        if (newList.remove(colorHex)) {
            val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_colors", newList.joinToString(",")).apply()
            _customColors.value = newList
            if (_themeColor.value == colorHex) {
                setThemeColor("#0061A4")
            }
        }
    }

    fun refreshDiscovery(context: Context) {
        viewModelScope.launch {
            _devices.value = emptyList()
            stopDiscovery()
            delay(300)
            startDiscovery(context)
        }
    }

    fun loadMaxConcurrency() {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        val value = prefs.getInt("max_concurrency", 3)
        _maxConcurrency.value = value
        com.example.network.TransferStateManager.maxConcurrencyLimit.value = value
    }

    fun setMaxConcurrency(value: Int) {
        val prefs = getApplication<Application>().getSharedPreferences("ptf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("max_concurrency", value).apply()
        _maxConcurrency.value = value
        com.example.network.TransferStateManager.maxConcurrencyLimit.value = value
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}