package com.example.network

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase

@Composable
fun FloatingBubbleContent(
    onClose: () -> Unit,
    onQuickUpload: () -> Unit,
    onLaunchApp: () -> Unit,
    onDragWindow: (Float, Float) -> Unit,
    onSnapToEdge: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.transmissionTaskDao() }

    val activeProgress by TransferStateManager.activeProgress.collectAsState()
    val activeFileName by TransferStateManager.activeFileName.collectAsState()
    val speedText by TransferStateManager.speedText.collectAsState()
    val remainingTimeText by TransferStateManager.remainingTimeText.collectAsState()
    val isUploadingOrDownloading by TransferStateManager.isUploadingOrDownloading.collectAsState()
    val isSending by TransferStateManager.isSending.collectAsState()
    
    val activePairingRequest by TransferStateManager.activePairingRequest.collectAsState()

    val isDocked by TransferStateManager.isDockedState.collectAsState()
    val dockSide by TransferStateManager.dockSideState.collectAsState()

    // Determine current logical state (IDLE, PAIRING_REQUEST, TRANSFERRING)
    val bubbleState = when {
        activePairingRequest != null -> BubbleState.PAIRING_REQUEST
        isUploadingOrDownloading -> BubbleState.TRANSFERRING
        else -> BubbleState.IDLE
    }

    val connectedDevices by TransferStateManager.walkieTalkieConnectedDevices.collectAsState()
    val isSpeaking by TransferStateManager.isSpeakingState.collectAsState()
    val isMicLocked by TransferStateManager.isMicLockedState.collectAsState()
    val isAnyPeerSpeaking = TransferStateManager.isAnyPeerSpeaking()

    var lastClickTime by remember { mutableLongStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        shape = CircleShape,
        color = when {
            isSpeaking || isMicLocked -> MaterialTheme.colorScheme.primary
            isAnyPeerSpeaking -> MaterialTheme.colorScheme.error
            connectedDevices.isNotEmpty() -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shadowElevation = 8.dp,
        border = BorderStroke(
            width = 2.dp,
            color = when {
                isSpeaking || isMicLocked -> MaterialTheme.colorScheme.primaryContainer
                isAnyPeerSpeaking -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            }
        ),
        modifier = Modifier
            .size(58.dp)
            .clickable {
                val now = System.currentTimeMillis()
                if (now - lastClickTime < 350L) {
                    onLaunchApp()
                } else {
                    lastClickTime = now
                    if (connectedDevices.isEmpty()) {
                        android.widget.Toast.makeText(context, "请先在 PTF 内连接对讲设备", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (isAnyPeerSpeaking) {
                        android.widget.Toast.makeText(context, "对方正在讲话...", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (isMicLocked) {
                        WalkieTalkieAudioManager.stopRecording()
                        WalkieTalkieAudioManager.broadcastSpeakingState(false)
                        TransferStateManager.isMicLockedState.value = false
                        android.widget.Toast.makeText(context, "已结束持续对讲", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        onLaunchApp()
                    }
                }
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = when {
                    isMicLocked -> Icons.Default.Lock
                    isSpeaking -> Icons.Default.Mic
                    isAnyPeerSpeaking -> Icons.Default.VolumeUp
                    else -> Icons.Default.Mic
                },
                contentDescription = "PTF对讲",
                tint = when {
                    isSpeaking || isMicLocked -> MaterialTheme.colorScheme.onPrimary
                    isAnyPeerSpeaking -> MaterialTheme.colorScheme.onError
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
