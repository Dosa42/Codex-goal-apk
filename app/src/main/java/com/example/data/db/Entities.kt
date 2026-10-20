package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val endpoint: String,
    val environmentInfoJson: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "processes",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"])
    ]
)
data class ProcessEntity(
    @PrimaryKey val processId: String,
    val sessionId: String,
    val title: String,
    val argvJson: String,
    val cwd: String?,
    val envJson: String?,
    val tty: Boolean,
    val status: String, // STARTING, RUNNING, EXITED, CLOSED, UNCERTAIN
    val exitCode: Int?,
    val lastConsumedSeq: Long?,
    val deliveryUncertain: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "output_chunks",
    indices = [
        // Composite unique index ensures deduplication of notifications and process/read chunks
        Index(value = ["processId", "seq"], unique = true),
        Index(value = ["processId", "id"])
    ]
)
data class OutputChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val processId: String,
    val seq: Long,
    val stream: String, // stdout, stderr
    val chunkBase64: String,
    val timestamp: Long
)

@Entity(
    tableName = "protocol_events",
    indices = [Index(value = ["timestamp"])]
)
data class ProtocolEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val direction: String, // OUT, IN
    val method: String,
    val summary: String,
    val fullPayload: String
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
