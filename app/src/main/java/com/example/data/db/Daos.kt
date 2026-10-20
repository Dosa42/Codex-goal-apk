package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 1")
    fun getLatestSessionFlow(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestSession(): SessionEntity?

    @Query("UPDATE sessions SET status = :status, updatedAt = :timestamp WHERE sessionId = :sessionId")
    suspend fun updateStatus(sessionId: String, status: String, timestamp: Long)

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessionsFlow(): Flow<List<SessionEntity>>
}

@Dao
interface ProcessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(process: ProcessEntity)

    @Query("SELECT * FROM processes WHERE processId = :processId LIMIT 1")
    suspend fun getProcess(processId: String): ProcessEntity?

    @Query("SELECT * FROM processes WHERE processId = :processId LIMIT 1")
    fun getProcessFlow(processId: String): Flow<ProcessEntity?>

    @Query("SELECT * FROM processes WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun getProcessesForSessionFlow(sessionId: String): Flow<List<ProcessEntity>>

    @Query("SELECT * FROM processes ORDER BY createdAt DESC")
    fun getAllProcessesFlow(): Flow<List<ProcessEntity>>

    @Query("UPDATE processes SET status = :status, exitCode = :exitCode, updatedAt = :timestamp WHERE processId = :processId")
    suspend fun updateStatusAndExit(processId: String, status: String, exitCode: Int?, timestamp: Long)

    @Query("UPDATE processes SET lastConsumedSeq = :seq, updatedAt = :timestamp WHERE processId = :processId AND (lastConsumedSeq IS NULL OR lastConsumedSeq < :seq)")
    suspend fun updateLastConsumedSeq(processId: String, seq: Long, timestamp: Long)

    @Query("UPDATE processes SET deliveryUncertain = :uncertain, updatedAt = :timestamp WHERE processId = :processId")
    suspend fun setDeliveryUncertain(processId: String, uncertain: Boolean, timestamp: Long)

    @Query("UPDATE processes SET status = 'RUNNING', deliveryUncertain = 0, updatedAt = :timestamp WHERE processId = :processId AND status NOT IN ('EXITED', 'CLOSED')")
    suspend fun setRunningMonotonic(processId: String, timestamp: Long): Int

    @Query("SELECT * FROM processes WHERE status IN ('STARTING', 'RUNNING')")
    suspend fun getActiveProcesses(): List<ProcessEntity>

    @Query("SELECT * FROM processes WHERE sessionId = :sessionId AND status IN ('STARTING', 'RUNNING', 'UNCERTAIN')")
    suspend fun getActiveProcessesForSession(sessionId: String): List<ProcessEntity>
}

@Dao
interface OutputChunkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(chunk: OutputChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(chunks: List<OutputChunkEntity>): List<Long>

    @Query("SELECT * FROM output_chunks WHERE processId = :processId ORDER BY seq ASC")
    fun getChunksForProcessFlow(processId: String): Flow<List<OutputChunkEntity>>

    @Query("SELECT * FROM output_chunks WHERE processId = :processId ORDER BY seq ASC")
    suspend fun getChunksForProcess(processId: String): List<OutputChunkEntity>

    @Query("SELECT MAX(seq) FROM output_chunks WHERE processId = :processId")
    suspend fun getMaxSeq(processId: String): Long?

    @Query("SELECT seq FROM output_chunks WHERE processId = :processId ORDER BY seq ASC")
    suspend fun getOrderedSeqsForProcess(processId: String): List<Long>

    @Query("DELETE FROM output_chunks WHERE processId = :processId")
    suspend fun deleteForProcess(processId: String)
}

@Dao
interface ProtocolEventDao {
    @Insert
    suspend fun insert(event: ProtocolEventEntity): Long

    @Query("SELECT * FROM protocol_events ORDER BY id DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int = 100): Flow<List<ProtocolEventEntity>>

    @Query("DELETE FROM protocol_events")
    suspend fun clearAll()
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    fun getFlow(key: String): Flow<String?>
}
