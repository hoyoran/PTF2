package com.example.data

import com.example.model.TransmissionTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TransmissionRepository(private val taskDao: TransmissionTaskDao) {
    val allTasks: Flow<List<TransmissionTask>> = taskDao.getAllTasks()

    suspend fun getTaskById(id: Int): TransmissionTask? = withContext(Dispatchers.IO) {
        taskDao.getTaskById(id)
    }

    suspend fun getActiveTask(): TransmissionTask? = withContext(Dispatchers.IO) {
        taskDao.getActiveTask()
    }

    suspend fun getTaskByConnection(deviceId: String, connectionId: String): TransmissionTask? = withContext(Dispatchers.IO) {
        taskDao.getTaskByConnection(deviceId, connectionId)
    }

    suspend fun insert(task: TransmissionTask): Long = withContext(Dispatchers.IO) {
        taskDao.insertTask(task)
    }

    suspend fun getNextRunnableSenderTask(): TransmissionTask? = withContext(Dispatchers.IO) {
        taskDao.getNextRunnableSenderTask()
    }

    suspend fun resetActiveTasksToWaiting() = withContext(Dispatchers.IO) {
        taskDao.resetActiveTasksToWaiting()
    }

    suspend fun pauseAllActiveTasks() = withContext(Dispatchers.IO) {
        taskDao.pauseAllActiveTasks()
    }

    suspend fun resumeAllPausedTasks() = withContext(Dispatchers.IO) {
        taskDao.resumeAllPausedTasks()
    }

    suspend fun update(task: TransmissionTask) = withContext(Dispatchers.IO) {
        taskDao.updateTask(task)
    }

    suspend fun updateTaskStatus(id: Int, status: com.example.model.TransmissionStatus) = withContext(Dispatchers.IO) {
        taskDao.updateTaskStatus(id, status)
    }

    suspend fun updateTaskOffset(id: Int, offset: Long) = withContext(Dispatchers.IO) {
        taskDao.updateTaskOffset(id, offset)
    }

    suspend fun updateTaskFileUri(id: Int, fileUri: String) = withContext(Dispatchers.IO) {
        taskDao.updateTaskFileUri(id, fileUri)
    }

    suspend fun deleteTaskById(id: Int) = withContext(Dispatchers.IO) {
        taskDao.deleteTaskById(id)
    }
}
