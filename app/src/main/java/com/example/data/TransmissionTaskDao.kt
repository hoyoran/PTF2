package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.model.TransmissionTask
import com.example.model.TransmissionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransmissionTaskDao {
    @Query("SELECT * FROM transmission_tasks ORDER BY timestamp DESC")
    fun getAllTasks(): Flow<List<TransmissionTask>>

    @Query("SELECT * FROM transmission_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Int): TransmissionTask?

    @Query("SELECT * FROM transmission_tasks WHERE status NOT IN ('SUCCESS', 'FAILED') LIMIT 1")
    suspend fun getActiveTask(): TransmissionTask?

    @Query("SELECT * FROM transmission_tasks WHERE deviceId = :deviceId AND connectionId = :connectionId LIMIT 1")
    suspend fun getTaskByConnection(deviceId: String, connectionId: String): TransmissionTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TransmissionTask): Long

    @Update
    suspend fun updateTask(task: TransmissionTask)

    @Query("UPDATE transmission_tasks SET status = :status WHERE id = :id")
    suspend fun updateTaskStatus(id: Int, status: TransmissionStatus)

    @Query("SELECT * FROM transmission_tasks WHERE status IN ('IDLE', 'WAITING_RECONNECT') AND isSender = 1 ORDER BY timestamp ASC LIMIT 1")
    suspend fun getNextRunnableSenderTask(): TransmissionTask?

    @Transaction
    @Query("UPDATE transmission_tasks SET status = 'WAITING_RECONNECT' WHERE status IN ('CONNECTING', 'SENDING', 'RESUMING')")
    suspend fun resetActiveTasksToWaiting()

    @Transaction
    @Query("UPDATE transmission_tasks SET status = 'PAUSED' WHERE status NOT IN ('SUCCESS', 'FAILED', 'PAUSED')")
    suspend fun pauseAllActiveTasks()

    @Transaction
    @Query("UPDATE transmission_tasks SET status = 'WAITING_RECONNECT' WHERE status = 'PAUSED'")
    suspend fun resumeAllPausedTasks()

    @Query("UPDATE transmission_tasks SET offset = :offset WHERE id = :id")
    suspend fun updateTaskOffset(id: Int, offset: Long)
    
    @Query("UPDATE transmission_tasks SET fileUri = :fileUri WHERE id = :id")
    suspend fun updateTaskFileUri(id: Int, fileUri: String)
    
    @Query("DELETE FROM transmission_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Int)
}
