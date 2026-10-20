package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Enums representing the client connection state to the Termux exec-server.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    INITIALIZED,
    SESSION_LOST,
    ERROR
}

enum class ProcessStatus {
    STARTING,
    RUNNING,
    EXITED,
    CLOSED,
    UNCERTAIN
}

/**
 * Standard client-to-server RPC request envelope.
 */
@JsonClass(generateAdapter = true)
data class JsonRpcRequest(
    @Json(name = "id") val id: Long,
    @Json(name = "method") val method: String,
    @Json(name = "params") val params: Any? = null
)

/**
 * Standard client-to-server Notification (no id).
 */
@JsonClass(generateAdapter = true)
data class JsonRpcNotification(
    @Json(name = "method") val method: String,
    @Json(name = "params") val params: Any? = null
)

/**
 * Initialize method parameters.
 */
@JsonClass(generateAdapter = true)
data class InitializeParams(
    @Json(name = "clientName") val clientName: String = "codex-goal-android",
    @Json(name = "resumeSessionId") val resumeSessionId: String? = null
)

@JsonClass(generateAdapter = true)
data class ShellInfo(
    @Json(name = "name") val name: String? = null,
    @Json(name = "path") val path: String? = null
)

@JsonClass(generateAdapter = true)
data class EnvironmentInfo(
    @Json(name = "cwd") val cwd: String? = null,
    @Json(name = "userHomeDir") val userHomeDir: String? = null,
    @Json(name = "shell") val shell: ShellInfo? = null,
    @Json(name = "platformOs") val platformOs: String? = null,
    @Json(name = "executorVersion") val executorVersion: String? = null,
    @Json(name = "capabilities") val capabilities: Map<String, Boolean>? = null
)

@JsonClass(generateAdapter = true)
data class InitializeResult(
    @Json(name = "sessionId") val sessionId: String,
    @Json(name = "environmentInfo") val environmentInfo: EnvironmentInfo? = null
)

/**
 * process/start parameters.
 */
@JsonClass(generateAdapter = true)
data class ProcessStartParams(
    @Json(name = "processId") val processId: String,
    @Json(name = "argv") val argv: List<String>,
    @Json(name = "cwd") val cwd: String? = null,
    @Json(name = "env") val env: Map<String, String>? = null,
    @Json(name = "tty") val tty: Boolean = false,
    @Json(name = "pipeStdin") val pipeStdin: Boolean = false,
    @Json(name = "arg0") val arg0: String? = null
)

/**
 * process/read parameters.
 * Note: afterSeq is EXCLUSIVE.
 */
@JsonClass(generateAdapter = true)
data class ProcessReadParams(
    @Json(name = "processId") val processId: String,
    @Json(name = "afterSeq") val afterSeq: Long? = null,
    @Json(name = "maxBytes") val maxBytes: Int = 65536,
    @Json(name = "waitMs") val waitMs: Long = 1000
)

@JsonClass(generateAdapter = true)
data class OutputChunkDto(
    @Json(name = "seq") val seq: Long,
    @Json(name = "stream") val stream: String, // "stdout" | "stderr"
    @Json(name = "chunk") val chunk: String // Base64 encoded payload
)

@JsonClass(generateAdapter = true)
data class ProcessReadResult(
    @Json(name = "chunks") val chunks: List<OutputChunkDto> = emptyList(),
    @Json(name = "nextSeq") val nextSeq: Long? = null,
    @Json(name = "exited") val exited: Boolean = false,
    @Json(name = "exitCode") val exitCode: Int? = null,
    @Json(name = "closed") val closed: Boolean = false,
    @Json(name = "failure") val failure: Any? = null,
    @Json(name = "sandboxDenied") val sandboxDenied: Boolean = false
)

/**
 * process/write parameters.
 * Includes required writeId.
 */
@JsonClass(generateAdapter = true)
data class ProcessWriteParams(
    @Json(name = "processId") val processId: String,
    @Json(name = "chunk") val chunk: String, // Base64 encoded
    @Json(name = "writeId") val writeId: String
)

@JsonClass(generateAdapter = true)
data class ProcessSignalParams(
    @Json(name = "processId") val processId: String,
    @Json(name = "signal") val signal: String // e.g. "interrupt", "kill", "terminate"
)

@JsonClass(generateAdapter = true)
data class ProcessTerminateParams(
    @Json(name = "processId") val processId: String
)

@JsonClass(generateAdapter = true)
data class ProcessTerminateResult(
    @Json(name = "running") val running: Boolean = false
)

/**
 * Server notifications.
 */
@JsonClass(generateAdapter = true)
data class ProcessOutputNotification(
    @Json(name = "processId") val processId: String,
    @Json(name = "seq") val seq: Long,
    @Json(name = "stream") val stream: String,
    @Json(name = "chunk") val chunk: String
)

@JsonClass(generateAdapter = true)
data class ProcessExitedNotification(
    @Json(name = "processId") val processId: String,
    @Json(name = "seq") val seq: Long? = null,
    @Json(name = "exitCode") val exitCode: Int? = null
)

@JsonClass(generateAdapter = true)
data class ProcessClosedNotification(
    @Json(name = "processId") val processId: String,
    @Json(name = "seq") val seq: Long? = null
)
