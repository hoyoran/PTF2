package com.example.network

import android.content.Context
import com.example.data.TransmissionRepository
import com.example.model.TransmissionStatus
import com.example.model.TransmissionTask
import com.example.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TransferQueueManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var repository: TransmissionRepository? = null
    private var tcpClient: TcpClientManager? = null
    private var myDeviceId: String = ""
    private val dispatchMutex = Mutex()
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)

    fun notifyQueueChanged() {
        triggerChannel.trySend(Unit)
    }

    fun start(context: Context, repo: TransmissionRepository, client: TcpClientManager, deviceId: String) {
        if (isRunning) return
        isRunning = true
        repository = repo
        tcpClient = client
        myDeviceId = deviceId

        scope.launch {
            try {
                repo.resetActiveTasksToWaiting()
            } catch (e: Throwable) {
                AppLogger.error("QUEUE_MANAGER", "Failed to reset active tasks on startup", e)
            }

            while (isRunning) {
                try {
                    // Check active task count under mutex lock
                    val canProcess = dispatchMutex.withLock {
                        val activeCount = TransferStateManager.activeTaskIds.size
                        val maxLimit = TransferStateManager.maxConcurrencyLimit.value
                        val pairing = TransferStateManager.activePairingRequest.value
                        activeCount < maxLimit && pairing == null
                    }

                    if (!canProcess) {
                        delay(500)
                        continue
                    }

                    // Look for the next sender task that is runnable (IDLE or WAITING_RECONNECT)
                    val nextTask = repo.getNextRunnableSenderTask()
                    if (nextTask == null) {
                        // Wait for new tasks or state changes with a timeout fallback
                        withTimeoutOrNull(1000) {
                            triggerChannel.receive()
                        }
                        continue
                    }

                    // Locate target device address
                    val onlineDevices = TransferStateManager.discoveredDevices.value
                    val targetDevice = onlineDevices.find { it.deviceId == nextTask.deviceId }

                    if (targetDevice != null) {
                        dispatchMutex.withLock {
                            AppLogger.info("QUEUE_MANAGER", "Starting concurrent queued task ${nextTask.id} (${nextTask.fileName}) to IP: ${targetDevice.ipAddress}")
                            // Mark task as CONNECTING immediately to avoid double-selection race conditions
                            repo.updateTaskStatus(nextTask.id, TransmissionStatus.CONNECTING)
                        }

                        scope.launch {
                            try {
                                if (nextTask.offset > 0L) {
                                    client.resumeTask(targetDevice.ipAddress, nextTask, myDeviceId)
                                } else {
                                    client.startOutgoingTask(targetDevice.ipAddress, nextTask, myDeviceId)
                                }
                            } catch (e: Throwable) {
                                AppLogger.error("QUEUE_MANAGER", "Concurrent task execution failed for task ${nextTask.id}", e)
                                repo.updateTaskStatus(nextTask.id, TransmissionStatus.WAITING_RECONNECT)
                            } finally {
                                notifyQueueChanged()
                            }
                        }
                    } else {
                        // Device not currently online. Put to WAITING_RECONNECT state if it was IDLE.
                        if (nextTask.status == TransmissionStatus.IDLE) {
                            repo.updateTaskStatus(nextTask.id, TransmissionStatus.WAITING_RECONNECT)
                        }
                        delay(1000) // Wait before re-querying for missing devices
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.error("QUEUE_MANAGER", "Error in transfer queue loop step", e)
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.coroutineContext.cancelChildren()
    }
}
