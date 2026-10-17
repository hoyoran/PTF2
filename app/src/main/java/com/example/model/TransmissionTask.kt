package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransmissionStatus {
    IDLE, CONNECTING, SENDING, WAITING_RECONNECT, RESUMING, VERIFYING, SUCCESS, FAILED, PAUSED
}

@Entity(
    tableName = "transmission_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["deviceId"]),
        Index(value = ["timestamp"]),
        Index(value = ["status", "isSender"]),
        Index(value = ["groupId"])
    ]
)
data class TransmissionTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileUri: String,
    val fileName: String,
    val fileSize: Long,
    val offset: Long = 0L,
    val deviceId: String,
    val connectionId: String,
    val status: TransmissionStatus = TransmissionStatus.IDLE,
    val isSender: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val fileHash: String? = null,
    val groupId: String? = null,
    val groupFileCount: Int = 1
)
