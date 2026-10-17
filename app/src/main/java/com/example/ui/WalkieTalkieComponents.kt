package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.DeviceInfo
import com.example.network.TransferStateManager
import com.example.network.WalkieTalkieAudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieDeviceCard(
    device: DeviceInfo,
    isPeerSpeaking: Boolean,
    peerAudioLevel: Float,
    networkQuality: Int, // 1 to 5
    onDisconnect: () -> Unit
) {
    var showDetailDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val peerAudioSharingMap by com.example.network.TransferStateManager.peerAudioSharingStates.collectAsState()
    val isAudioSharingEnabled = peerAudioSharingMap[device.deviceId] == true

    // Unified Sound Detection: Mic speaking OR System internal audio active
    val isSoundActive = isPeerSpeaking || peerAudioLevel > 0.05f || (isAudioSharingEnabled && peerAudioLevel > 0.02f)

    // Glow pulsing animation when sound is active
    val infiniteTransition = rememberInfiniteTransition(label = "peer_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    if (showDetailDialog) {
        WalkieTalkieDetailDialog(
            device = device,
            isPeerSpeaking = isPeerSpeaking,
            networkQuality = networkQuality,
            onDismiss = { showDetailDialog = false }
        )
    }

    if (showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            title = {
                Text("断开连接确认", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("是否断开与 ${device.deviceId} 的连接？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        onDisconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (kotlin.math.abs(offsetX) > 30f)
                    MaterialTheme.colorScheme.errorContainer
                else Color.Transparent
            )
    ) {
        // Background Disconnect Prompt shown when card is swiped
        if (kotlin.math.abs(offsetX) > 30f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (offsetX > 0) Arrangement.Start else Arrangement.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "断开连接",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (kotlin.math.abs(offsetX) > 100f) "松手断开连接" else "滑动断开连接",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSoundActive)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isSoundActive) 8.dp else 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .then(
                    if (isSoundActive) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = glowAlpha),
                            shape = RoundedCornerShape(16.dp)
                        )
                    } else Modifier
                )
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-240f, 240f)
                        },
                        onDragEnd = {
                            if (kotlin.math.abs(offsetX) > 100f) {
                                showDisconnectConfirmDialog = true
                            }
                            offsetX = 0f
                        },
                        onDragCancel = {
                            offsetX = 0f
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Device Avatar / Icon with glow when sound is active
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(
                                elevation = if (isSoundActive) 8.dp else 0.dp,
                                shape = CircleShape,
                                clip = false
                            )
                            .background(
                                color = if (isSoundActive)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = "设备",
                            tint = if (isSoundActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Device Name, ID, and System Audio Sharing status badge
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (device.deviceId.isNotEmpty()) "设备 ${device.deviceId}" else "对讲设备",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSoundActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "ID: ${device.deviceId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSoundActive) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        if (isAudioSharingEnabled) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "系统内部音频共享已开启",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Action buttons: Eye (Info) + Network Quality Rating
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Eye button for detailed privacy-protected info
                        IconButton(
                            onClick = { showDetailDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(
                                text = "👁",
                                fontSize = 18.sp
                            )
                        }

                        // Network Quality Rating
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📶",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 2.dp)
                                )
                                Text(
                                    text = "★".repeat(networkQuality.coerceIn(1, 5)),
                                    color = Color(0xFFFFB300),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Sound Activity Audio Waveform Visualizer
                if (isSoundActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isPeerSpeaking) "🎙 对方讲话中..." else "🔊 系统音频播放中...",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        AudioWaveformVisualizer(
                            audioLevel = peerAudioLevel.coerceAtLeast(0.2f),
                            barColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "滑动卡片可断开对讲",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "状态: 已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioWaveformVisualizer(
    audioLevel: Float,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val barCount = 12
    val animatedLevels = remember { mutableStateListOf(*Array(barCount) { 0.2f }) }

    LaunchedEffect(audioLevel) {
        for (i in 0 until barCount) {
            val factor = (0.3f + (i % 5) * 0.15f)
            val noise = (kotlin.random.Random.nextFloat() * 0.3f)
            animatedLevels[i] = ((audioLevel * factor + noise).coerceIn(0.15f, 1.0f))
        }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = (width / barCount) * 0.6f
        val gap = (width / barCount) * 0.4f

        for (i in 0 until barCount) {
            val barHeight = height * animatedLevels[i]
            val x = i * (barWidth + gap) + gap / 2
            val y = (height - barHeight) / 2

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
            )
        }
    }
}

@Composable
fun WalkieTalkieDetailDialog(
    device: DeviceInfo,
    isPeerSpeaking: Boolean,
    networkQuality: Int,
    onDismiss: () -> Unit
) {
    var isIpRevealed by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { 
                showPasswordDialog = false
                passwordError = false
                passwordInput = ""
            },
            title = {
                Text("🔑 身份密码验证", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "为保护设备隐私，查看网络 IP 地址需要管理员验证。\n默认密码：123456",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { 
                            passwordInput = it
                            passwordError = false 
                        },
                        label = { Text("请输入密码") },
                        isError = passwordError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordError) {
                        Text(
                            "密码错误，请重新输入",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (passwordInput == "123456") {
                            isIpRevealed = true
                            showPasswordDialog = false
                            passwordInput = ""
                            passwordError = false
                        } else {
                            passwordError = true
                        }
                    }
                ) {
                    Text("确认验证")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showPasswordDialog = false 
                        passwordError = false
                        passwordInput = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("👁 设备详细信息", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DetailItem("设备 ID", device.deviceId)
                
                // Privacy protection for IP Address
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "IP 地址",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isIpRevealed) {
                        Text(
                            text = device.ipAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "••••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showPasswordDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("查看", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                DetailItem("MAC 地址", "•••••••• (需密码验证)")
                DetailItem("系统类型", "Android (PTF Direct)")
                DetailItem("PTF 版本", "v2.1.0 (实时对讲)")
                DetailItem("网络质量", "★".repeat(networkQuality.coerceIn(1, 5)) + " ($networkQuality/5)")
                DetailItem(
                    "连接状态",
                    if (isPeerSpeaking) "正在传输语音 (通话中)" else "在线待命 (延迟 <30ms)"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun WalkieTalkieSearchDialog(
    devices: List<DeviceInfo>,
    rooms: Map<String, com.example.network.TransferStateManager.DiscoveredRoomInfo>,
    connectingDeviceId: String?,
    onConnectRequest: (DeviceInfo) -> Unit,
    onJoinRoomRequest: (com.example.network.IntercomRoom, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("添加设备与加入房间", fontWeight = FontWeight.Bold)
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
            ) {
                Text(
                    text = "正在自动扫描局域网内的设备与开放房间...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Category 1: Device List (第一类：设备列表)
                    item {
                        Text(
                            text = "📱 第一类：设备列表",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    if (devices.isEmpty()) {
                        item {
                            Text(
                                text = "未扫描到普通设备",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    } else {
                        items(devices) { dev ->
                            val isConnecting = connectingDeviceId == dev.deviceId
                            Card(
                                onClick = { if (!isConnecting) onConnectRequest(dev) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "设备 ID: ${dev.deviceId}",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "状态: 在线待命",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isConnecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Button(
                                            onClick = { onConnectRequest(dev) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text("连接", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Category 2: Room List (第二类：房间列表)
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🏠 第二类：局域网房间列表",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    if (rooms.isEmpty()) {
                        item {
                            Text(
                                text = "当前局域网无开放房间",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    } else {
                        items(rooms.values.toList()) { info ->
                            val room = info.room
                            val hostIp = info.hostIp
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.secondary,
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = room.roomName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "状态: 在线 · 人数: ${room.members.size}人",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                    Button(
                                        onClick = { onJoinRoomRequest(room, hostIp) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Text("申请加入", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun WalkieTalkieMicButton(
    micEnabled: Boolean,
    isSpeaking: Boolean,
    isMicLocked: Boolean,
    pulseScale: Float,
    connectedDevicesCount: Int,
    isPeerSpeaking: Boolean,
    onStartSpeaking: () -> Unit,
    onStopSpeaking: () -> Unit,
    onLockMic: () -> Unit,
    onToggleNoiseSuppression: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestSystemAudioCapture: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    centerIcon: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val isAudioSharingEnabled by TransferStateManager.isAudioSharingEnabled.collectAsState()
    val isNoiseSuppressionEnabled by TransferStateManager.isNoiseSuppressionEnabled.collectAsState()

    val buttonOffsetX = remember { Animatable(0f) }
    val buttonOffsetY = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val maxDragPx = with(density) { 130.dp.toPx() }
    val triggerThresholdPx = with(density) { 75.dp.toPx() }

    val dragRatioX = (buttonOffsetX.value / triggerThresholdPx).coerceIn(-1.5f, 1.5f)
    val dragRatioY = (buttonOffsetY.value / triggerThresholdPx).coerceIn(-1.5f, 1.5f)

    val isLeftTriggered = buttonOffsetX.value <= -triggerThresholdPx
    val isRightTriggered = buttonOffsetX.value >= triggerThresholdPx
    val isUpTriggered = buttonOffsetY.value <= -triggerThresholdPx

    val ringScale by animateFloatAsState(
        targetValue = if (isDragging) 1.0f else 0.3f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "ring_scale"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1.0f else 0.0f,
        animationSpec = tween(180),
        label = "ring_alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        // Prompt banner when microphone is locked
        if (isMicLocked && !isDragging) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                shadowElevation = 4.dp,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "锁定中",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "再次点击返回原始状态",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        } else if (isAudioSharingEnabled && !isDragging) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "系统音频共享",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "🔊 系统内部音频共享中",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            contentAlignment = Alignment.Center
        ) {
            // Android 4 Lockscreen Style Circular Control Ring Area (圆环功能控制区域)
            if (ringAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .graphicsLayer {
                            scaleX = ringScale
                            scaleY = ringScale
                            alpha = ringAlpha
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Outer translucent ring guide circle
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        border = BorderStroke(
                            width = 2.dp,
                            color = when {
                                isUpTriggered -> MaterialTheme.colorScheme.primary
                                isLeftTriggered -> MaterialTheme.colorScheme.tertiary
                                isRightTriggered -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            }
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {}

                    // 1. LEFT TARGET NODE: Lock Mic (锁定麦克风)
                    val leftScale by animateFloatAsState(
                        targetValue = if (isLeftTriggered) 1.35f else if (dragRatioX < 0) 1.0f + (-dragRatioX * 0.2f) else 1.0f,
                        label = "left_node_scale"
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = (-85).dp, y = 0.dp)
                            .graphicsLayer {
                                scaleX = leftScale
                                scaleY = leftScale
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isLeftTriggered || isMicLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shadowElevation = if (isLeftTriggered) 8.dp else 2.dp,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = if (isLeftTriggered || isMicLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "锁定模式",
                                    tint = if (isLeftTriggered || isMicLocked) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (isLeftTriggered) "松开锁定" else "锁定",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLeftTriggered || isMicLocked) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 2. RIGHT TARGET NODE: Noise Reduction Control (降噪控制)
                    val rightScale by animateFloatAsState(
                        targetValue = if (isRightTriggered) 1.35f else if (dragRatioX > 0) 1.0f + (dragRatioX * 0.2f) else 1.0f,
                        label = "right_node_scale"
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = 85.dp, y = 0.dp)
                            .graphicsLayer {
                                scaleX = rightScale
                                scaleY = rightScale
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isRightTriggered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shadowElevation = if (isRightTriggered) 8.dp else 2.dp,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "降噪控制",
                                    tint = if (isRightTriggered) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (isRightTriggered) "松开切换" else "降噪",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRightTriggered) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3. TOP TARGET NODE: System Audio Sharing (系统声音共享)
                    val topScale by animateFloatAsState(
                        targetValue = if (isUpTriggered) 1.35f else if (dragRatioY < 0) 1.0f + (-dragRatioY * 0.2f) else 1.0f,
                        label = "top_node_scale"
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = 0.dp, y = (-85).dp)
                            .graphicsLayer {
                                scaleX = topScale
                                scaleY = topScale
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isUpTriggered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shadowElevation = if (isUpTriggered) 8.dp else 2.dp,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneInTalk,
                                    contentDescription = "系统声音共享",
                                    tint = if (isUpTriggered) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (isUpTriggered) "松开共享" else "系统音频",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUpTriggered) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // The Microphone Center Button Following Finger in Realtime
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset(buttonOffsetX.value.roundToInt(), buttonOffsetY.value.roundToInt()) }
                    .size(86.dp)
                    .graphicsLayer {
                        scaleX = if (isDragging) pulseScale * 1.08f else pulseScale
                        scaleY = if (isDragging) pulseScale * 1.08f else pulseScale
                    }
                    .shadow(
                        elevation = if (isSpeaking || isMicLocked || isDragging) 12.dp else if (micEnabled) 4.dp else 0.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .background(
                        color = when {
                            isUpTriggered -> MaterialTheme.colorScheme.primary
                            isLeftTriggered -> MaterialTheme.colorScheme.tertiary
                            isRightTriggered -> MaterialTheme.colorScheme.secondary
                            isMicLocked -> MaterialTheme.colorScheme.tertiary
                            isSpeaking -> MaterialTheme.colorScheme.primary
                            micEnabled -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        shape = CircleShape
                    )
                    .pointerInput(micEnabled, isMicLocked) {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.RECORD_AUDIO
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            detectTapGestures(
                                onTap = {
                                    onRequestMicPermission()
                                }
                            )
                        } else if (connectedDevicesCount == 0 && !isPeerSpeaking) {
                            detectTapGestures(
                                onTap = {
                                    android.widget.Toast.makeText(context, "请点击右上角 '+' 创建或加入房间/设备", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else if (isMicLocked) {
                            detectTapGestures(
                                onTap = {
                                    onStopSpeaking()
                                    android.widget.Toast.makeText(context, "已恢复普通长按讲话模式", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    onStartSpeaking()
                                    isDragging = true
                                    var accumulatedX = 0f
                                    var accumulatedY = 0f

                                    val pointerId = down.id
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == pointerId }
                                        if (change == null || !change.pressed) {
                                            break
                                        }
                                        val dragX = change.positionChange().x
                                        val dragY = change.positionChange().y
                                        change.consume()
                                        accumulatedX = (accumulatedX + dragX).coerceIn(-maxDragPx, maxDragPx)
                                        accumulatedY = (accumulatedY + dragY).coerceIn(-maxDragPx, maxDragPx)
                                        coroutineScope.launch {
                                            buttonOffsetX.snapTo(accumulatedX)
                                            buttonOffsetY.snapTo(accumulatedY)
                                        }
                                    }

                                    isDragging = false
                                    val currentOffsetX = buttonOffsetX.value
                                    val currentOffsetY = buttonOffsetY.value

                                    if (currentOffsetY <= -triggerThresholdPx) {
                                        val newState = !TransferStateManager.isAudioSharingEnabled.value
                                        if (newState) {
                                            if (onRequestSystemAudioCapture != null) {
                                                onRequestSystemAudioCapture()
                                            } else {
                                                TransferStateManager.isAudioSharingEnabled.value = true
                                                android.widget.Toast.makeText(context, "🔊 已开启系统内部音频共享", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            TransferStateManager.isAudioSharingEnabled.value = false
                                            WalkieTalkieAudioManager.stopSystemAudioCapture()
                                            android.widget.Toast.makeText(context, "🔇 已关闭系统内部音频共享", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        onStopSpeaking()
                                    } else if (currentOffsetX <= -triggerThresholdPx) {
                                        onLockMic()
                                        android.widget.Toast.makeText(context, "🔒 已开启持续对讲模式 (再次点击返回原始状态)", android.widget.Toast.LENGTH_SHORT).show()
                                    } else if (currentOffsetX >= triggerThresholdPx) {
                                        onToggleNoiseSuppression()
                                        onStopSpeaking()
                                    } else {
                                        onStopSpeaking()
                                    }

                                    coroutineScope.launch {
                                        buttonOffsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            )
                                        )
                                        buttonOffsetY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
            ) {
                if (isMicLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "已锁定对讲",
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(38.dp)
                    )
                } else {
                    centerIcon()
                }
            }
        }
    }
}

@Composable
fun WalkieTalkieAddMenuDialog(
    onSelectAddDevice: () -> Unit,
    onSelectCreateRoom: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("对讲功能选择", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Card(
                    onClick = {
                        onDismiss()
                        onSelectAddDevice()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("添加设备", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("搜索局域网/热点中的对讲设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Card(
                    onClick = {
                        onDismiss()
                        onSelectCreateRoom()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.secondary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("创建房间", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("新建多人对讲房间，邀请设备加入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun WalkieTalkieRoomManagerDialog(
    rooms: List<com.example.network.IntercomRoom>,
    currentRoom: com.example.network.IntercomRoom?,
    onCreateNewRoomClick: () -> Unit,
    onSelectRoom: (com.example.network.IntercomRoom) -> Unit,
    onLeaveRoom: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("房间管理", fontWeight = FontWeight.Bold)
                TextButton(onClick = onCreateNewRoomClick) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建房间")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                if (rooms.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "暂无房间\n点击上方按钮立即创建多人对讲房间",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text("我的房间列表:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rooms) { room ->
                            val isSelected = currentRoom?.roomId == room.roomId
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                onClick = { onSelectRoom(room) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(room.roomName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text("ID: ${room.roomId} · 成员数: ${room.members.size}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (isSelected) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text("当前进入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentRoom != null) {
                TextButton(
                    onClick = {
                        onLeaveRoom()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("退出当前房间")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

@Composable
fun WalkieTalkieCreateRoomDialog(
    onCreateRoom: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var roomNameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建房间", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请输入对讲房间名称:", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = roomNameInput,
                    onValueChange = { roomNameInput = it },
                    placeholder = { Text("例如: 户外特训对讲组, 车队1组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (roomNameInput.isNotBlank()) {
                        onCreateRoom(roomNameInput.trim())
                    }
                },
                enabled = roomNameInput.isNotBlank()
            ) {
                Text("创建并进入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
