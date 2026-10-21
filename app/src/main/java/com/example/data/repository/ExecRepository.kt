package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.*
import com.example.data.model.EnvironmentInfo
import com.example.data.model.InitializeResult
import com.example.data.model.OutputChunkDto
import com.example.data.model.ProcessStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExecRepository(private val db: AppDatabase) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val envAdapter = moshi.adapter(EnvironmentInfo::class.java)

    // Settings
    companion object {
        const val KEY_ENDPOINT = "setting_endpoint"
        const val DEFAULT_ENDPOINT = "ws://127.0.0.1:33995"
        const val KEY_AUTO_BOOT = "setting_auto_boot"
        const val KEY_CONNECTION_DESIRED = "setting_connection_desired"
    }

    suspend fun isConnectionDesired(): Boolean {
        return db.settingDao().get(KEY_CONNECTION_DESIRED) != "false"
    }

    suspend fun setConnectionDesired(desired: Boolean) {
        db.settingDao().set(SettingEntity(KEY_CONNECTION_DESIRED, desired.toString()))
    }

    suspend fun getEndpoint(): String {
        return db.settingDao().get(KEY_ENDPOINT) ?: DEFAULT_ENDPOINT
    }

    suspend fun setEndpoint(endpoint: String) {
        db.settingDao().set(SettingEntity(KEY_ENDPOINT, endpoint.trim()))
    }

    suspend fun applyInfrastructureDefaultEndpoint(endpoint: String) = db.withTransaction {
        // Existing settings and session history establish the user's executor binding.
        if (db.settingDao().get(KEY_ENDPOINT) == null && db.sessionDao().getLatestSession() == null) {
            db.settingDao().set(SettingEntity(KEY_ENDPOINT, endpoint))
        }
    }

    fun getEndpointFlow(): Flow<String> {
        return db.settingDao().getFlow(KEY_ENDPOINT).map { it ?: DEFAULT_ENDPOINT }
    }

    suspend fun isAutoBootEnabled(): Boolean {
        return db.settingDao().get(KEY_AUTO_BOOT) == "true"
    }

    suspend fun setAutoBootEnabled(enabled: Boolean) {
        db.settingDao().set(SettingEntity(KEY_AUTO_BOOT, enabled.toString()))
    }

    // Sessions
    suspend fun saveSession(result: InitializeResult, endpoint: String): SessionEntity {
        val now = System.currentTimeMillis()
        val envJson = result.environmentInfo?.let { envAdapter.toJson(it) }
        val entity = SessionEntity(
            sessionId = result.sessionId,
            endpoint = endpoint,
            environmentInfoJson = envJson,
            status = "ACTIVE",
            createdAt = now,
            updatedAt = now
        )
        db.sessionDao().insertOrUpdate(entity)
        return entity
    }

    suspend fun getLatestSession(): SessionEntity? {
        return db.sessionDao().getLatestSession()
    }

    suspend fun getLatestSessionForEndpoint(endpoint: String): SessionEntity? =
        db.sessionDao().getLatestSessionForEndpoint(endpoint)

    fun getLatestSessionFlow(): Flow<SessionEntity?> {
        return db.sessionDao().getLatestSessionFlow()
    }

    suspend fun markSessionLost(sessionId: String) {
        db.sessionDao().updateStatus(sessionId, "SESSION_LOST", System.currentTimeMillis())
    }

    suspend fun markSessionResumable(sessionId: String) {
        db.sessionDao().updateStatus(sessionId, "RESUMABLE", System.currentTimeMillis())
    }

    // Processes
    suspend fun createProcess(
        processId: String,
        sessionId: String,
        title: String,
        argv: List<String>,
        cwd: String?,
        env: Map<String, String>?,
        tty: Boolean
    ): ProcessEntity {
        val now = System.currentTimeMillis()
        val listAdapter = moshi.adapter<List<String>>(List::class.java)
        val mapAdapter = moshi.adapter<Map<String, String>>(Map::class.java)
        val entity = ProcessEntity(
            processId = processId,
            sessionId = sessionId,
            title = title,
            argvJson = listAdapter.toJson(argv),
            cwd = cwd,
            envJson = env?.let { mapAdapter.toJson(it) },
            tty = tty,
            status = ProcessStatus.STARTING.name,
            exitCode = null,
            lastConsumedSeq = null,
            deliveryUncertain = false,
            createdAt = now,
            updatedAt = now
        )
        db.processDao().insertOrUpdate(entity)
        return entity
    }

    suspend fun setProcessRunning(processId: String) {
        val now = System.currentTimeMillis()
        db.processDao().setRunningMonotonic(processId, now)
    }

    suspend fun setProcessExited(processId: String, exitCode: Int?) {
        val now = System.currentTimeMillis()
        db.processDao().updateStatusAndExit(processId, ProcessStatus.EXITED.name, exitCode, now)
    }

    suspend fun setProcessClosed(processId: String) {
        val now = System.currentTimeMillis()
        db.processDao().updateStatusAndExit(processId, ProcessStatus.CLOSED.name, null, now)
    }

    suspend fun setProcessDeliveryUncertain(processId: String, uncertain: Boolean) {
        db.processDao().setDeliveryUncertain(processId, uncertain, System.currentTimeMillis())
    }

    suspend fun getProcess(processId: String): ProcessEntity? {
        return db.processDao().getProcess(processId)
    }

    fun getProcessFlow(processId: String): Flow<ProcessEntity?> {
        return db.processDao().getProcessFlow(processId)
    }

    fun getAllProcessesFlow(): Flow<List<ProcessEntity>> {
        return db.processDao().getAllProcessesFlow()
    }

    suspend fun getActiveProcesses(): List<ProcessEntity> {
        return db.processDao().getActiveProcesses()
    }

    suspend fun getActiveProcessesForSession(sessionId: String): List<ProcessEntity> {
        return db.processDao().getActiveProcessesForSession(sessionId)
    }

    suspend fun getCatchupProcesses(sessionId: String): List<ProcessEntity> =
        db.processDao().getProcessesForSession(sessionId).filter {
            db.settingDao().get("outputComplete:${it.processId}") != "true"
        }

    suspend fun getAllProcesses(): List<ProcessEntity> = db.processDao().getAllProcesses()

    suspend fun setOutputComplete(processId: String, complete: Boolean) {
        putSetting("outputComplete:$processId", complete.toString())
    }

    suspend fun putSetting(key: String, value: String) = db.settingDao().set(SettingEntity(key, value))
    suspend fun getSetting(key: String): String? = db.settingDao().get(key)

    // Output Chunks & Dedup with Contiguous Cursor
    suspend fun recordChunk(processId: String, seq: Long, stream: String, chunkBase64: String): Boolean {
        return db.withTransaction {
            val now = System.currentTimeMillis()
            val entity = OutputChunkEntity(
                processId = processId,
                seq = seq,
                stream = stream,
                chunkBase64 = chunkBase64,
                timestamp = now
            )
            val rowId = db.outputChunkDao().insertIgnore(entity)
            val isNew = (rowId != -1L)

            // Recompute contiguous cursor from persisted chunks
            val allSeqs = db.outputChunkDao().getOrderedSeqsForProcess(processId)
            val contiguousCursor = computeContiguousCursor(allSeqs, db.processDao().getProcess(processId)?.lastConsumedSeq)
            if (contiguousCursor != null) {
                db.processDao().updateLastConsumedSeq(processId, contiguousCursor, now)
            }
            isNew
        }
    }

    suspend fun recordChunksBatch(processId: String, chunks: List<OutputChunkDto>): Int {
        if (chunks.isEmpty()) return 0
        return db.withTransaction {
            val now = System.currentTimeMillis()
            val entities = chunks.map {
                OutputChunkEntity(
                    processId = processId,
                    seq = it.seq,
                    stream = it.stream,
                    chunkBase64 = it.chunk,
                    timestamp = now
                )
            }
            val insertedRowIds = db.outputChunkDao().insertIgnoreAll(entities)
            val insertedCount = insertedRowIds.count { it != -1L }

            val allSeqs = db.outputChunkDao().getOrderedSeqsForProcess(processId)
            val contiguousCursor = computeContiguousCursor(allSeqs, db.processDao().getProcess(processId)?.lastConsumedSeq)
            if (contiguousCursor != null) {
                db.processDao().updateLastConsumedSeq(processId, contiguousCursor, now)
            }
            insertedCount
        }
    }

    // process/read enumerates all output after the requested cursor. Lifecycle
    // events also consume sequence numbers, so a complete read page may contain
    // legitimate gaps. Live notifications alone cannot certify those gaps.
    suspend fun recordReadPage(processId: String, afterSeq: Long?, chunks: List<OutputChunkDto>): Int {
        require(chunks.all { it.seq > (afterSeq ?: 0L) }) { "Read page precedes requested cursor" }
        require(chunks.zipWithNext().all { (a, b) -> a.seq < b.seq }) { "Read page is not ordered" }
        return db.withTransaction {
            val priorCursor = db.processDao().getProcess(processId)?.lastConsumedSeq ?: 0L
            val inserted = recordChunksBatch(processId, chunks)
            if ((afterSeq ?: 0L) <= priorCursor) {
                chunks.lastOrNull()?.let {
                    db.processDao().updateLastConsumedSeq(processId, it.seq, System.currentTimeMillis())
                }
            }
            inserted
        }
    }

    private fun computeContiguousCursor(orderedSeqs: List<Long>, priorCursor: Long?): Long? {
        if (orderedSeqs.isEmpty()) return null
        var expected = (priorCursor ?: 0L) + 1L
        var contiguous: Long? = priorCursor
        for (seq in orderedSeqs) {
            if (seq == expected) {
                contiguous = seq
                expected++
            } else if (seq < expected) {
                continue
            } else {
                // Gap encountered! Stop at last contiguous sequence
                break
            }
        }
        return contiguous
    }

    fun getChunksFlow(processId: String): Flow<List<OutputChunkEntity>> {
        return db.outputChunkDao().getChunksForProcessFlow(processId)
    }

    suspend fun getChunks(processId: String): List<OutputChunkEntity> {
        return db.outputChunkDao().getChunksForProcess(processId)
    }

    // Protocol Events
    suspend fun recordEvent(direction: String, method: String, summary: String, payload: String) {
        val entity = ProtocolEventEntity(
            timestamp = System.currentTimeMillis(),
            direction = direction,
            method = method,
            summary = summary,
            fullPayload = payload
        )
        db.protocolEventDao().insert(entity)
    }

    fun getRecentEventsFlow(limit: Int = 100): Flow<List<ProtocolEventEntity>> {
        return db.protocolEventDao().getRecentEventsFlow(limit)
    }

    suspend fun clearEvents() {
        db.protocolEventDao().clearAll()
    }
}
