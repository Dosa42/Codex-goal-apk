package com.example.transport

import android.util.Base64
import android.util.Log
import com.example.data.model.*
import com.example.data.repository.ExecRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

sealed class WriteStatus {
    object Accepted : WriteStatus()
    object Starting : WriteStatus()
    object StdinClosed : WriteStatus()
    object UnknownProcess : WriteStatus()
    data class Other(val raw: String) : WriteStatus()
    data class Failed(val message: String) : WriteStatus()
}

class CodexWebSocketClient(
    private val repository: ExecRepository,
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val rpcTimeoutMs: Long = 10000,
    private val retryBaseMs: Long = 1000
) {
    private val TAG = "CodexWebSocketClient"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _observedEnvironment = MutableStateFlow<EnvironmentInfo?>(null)
    val observedEnvironment: StateFlow<EnvironmentInfo?> = _observedEnvironment.asStateFlow()

    private var webSocket: WebSocket? = null
    private val currentGeneration = AtomicLong(0)
    val socketGeneration: Long get() = currentGeneration.get()
    val pendingRpcCount: Int get() = pendingRequests.size
    private val requestIdCounter = AtomicLong(1)

    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    private var reconnectJob: Job? = null
    private var isUserStopped = false
    private var reconnectAttempt = 0
    private var forceFreshSession = false

    var currentEndpoint: String? = null
        private set

    fun start(endpoint: String, isReconnect: Boolean = false, freshSession: Boolean = false) {
        isUserStopped = false
        if (freshSession) {
            forceFreshSession = true
        }
        reconnectJob?.cancel()

        // Turnover: Invalidate generation -> cancel/close old socket -> fail old pending -> new socket
        closeCurrentSocket("Starting new connection turnover")
        connectInternal(endpoint)
    }

    fun stop() {
        isUserStopped = true
        forceFreshSession = false
        reconnectJob?.cancel()
        reconnectAttempt = 0
        closeCurrentSocket("User requested stop")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun closeCurrentSocket(reason: String) {
        currentGeneration.incrementAndGet()
        failPendingRequests("Connection closed: $reason")
        val ws = webSocket
        webSocket = null
        if (ws != null) {
            try {
                ws.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling websocket", e)
            }
        }
    }

    private fun failPendingRequests(message: String) {
        val iterator = pendingRequests.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.completeExceptionally(IOException(message))
            iterator.remove()
        }
    }

    private fun connectInternal(endpoint: String) {
        currentEndpoint = endpoint
        val generation = currentGeneration.incrementAndGet()
        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null

        val request = try {
            Request.Builder().url(endpoint).build()
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _lastError.value = "Invalid endpoint URL: ${e.message}"
            return
        }

        scope.launch {
            if (currentGeneration.get() != generation) return@launch
            repository.recordEvent("OUT", "CONNECT", "Attempting connection to $endpoint (gen $generation)", endpoint)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (currentGeneration.get() != generation) {
                    webSocket.close(1000, "Stale generation")
                    return
                }
                scope.launch {
                    if (currentGeneration.get() != generation) return@launch
                    reconnectAttempt = 0
                    _connectionState.value = ConnectionState.INITIALIZING
                    repository.recordEvent("IN", "OPEN", "WebSocket transport opened", response.message)
                    performHandshake(endpoint, generation, webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (currentGeneration.get() != generation) return
                scope.launch {
                    if (currentGeneration.get() != generation) return@launch
                    handleIncomingMessage(text, generation)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (currentGeneration.get() != generation) return
                // Complete close handshake
                try {
                    webSocket.close(code, reason)
                } catch (e: Exception) {
                    Log.w(TAG, "Error completing close handshake", e)
                }
                scope.launch {
                    if (currentGeneration.get() != generation) return@launch
                    repository.recordEvent("IN", "CLOSING", "WebSocket closing code=$code", reason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (currentGeneration.get() != generation) return
                scope.launch {
                    if (currentGeneration.get() != generation) return@launch
                    repository.recordEvent("IN", "CLOSED", "WebSocket closed code=$code", reason)
                }
                scope.launch { handleDisconnect(endpoint, "Closed: $reason ($code)", generation) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (currentGeneration.get() != generation) return
                val errorMsg = t.message ?: "Unknown connection error"
                Log.e(TAG, "WebSocket failure: $errorMsg", t)
                scope.launch {
                    if (currentGeneration.get() != generation) return@launch
                    repository.recordEvent("IN", "FAILURE", "WebSocket failure", errorMsg)
                }
                scope.launch { handleDisconnect(endpoint, errorMsg, generation) }
            }
        }

        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    private fun handleDisconnect(endpoint: String, reason: String, generation: Long) {
        if (currentGeneration.get() != generation) return
        closeCurrentSocket(reason)
        val retryGeneration = currentGeneration.get()
        _lastError.value = reason

        if (isUserStopped) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        // Bounded exponential backoff with unlimited retry while enabled
        _connectionState.value = ConnectionState.ERROR
        reconnectAttempt++
        val delayMs = min(5000L, retryBaseMs * (1 shl min(reconnectAttempt, 4)))
        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isUserStopped && currentGeneration.get() == retryGeneration) {
                connectInternal(endpoint)
            }
        }
    }

    private suspend fun performHandshake(endpoint: String, generation: Long, ws: WebSocket) {
        if (currentGeneration.get() != generation) return

        val lastSession = repository.getLatestSessionForEndpoint(endpoint)
        if (currentGeneration.get() != generation) return
        if (!forceFreshSession && lastSession?.endpoint == endpoint && lastSession.status == "SESSION_LOST") {
            _connectionState.value = ConnectionState.SESSION_LOST
            _lastError.value = "Previous session is lost. Choose New Session to continue."
            closeCurrentSocket("Explicit new session required")
            return
        }
        // Resume scope: same endpoint AND non-lost session
        val shouldAttemptResume = !forceFreshSession &&
                lastSession != null &&
                lastSession.endpoint == endpoint &&
                lastSession.status != "SESSION_LOST"

        val resumeSessionId = if (shouldAttemptResume) lastSession?.sessionId else null

        val initParams = InitializeParams(
            clientName = "codex-goal-android",
            resumeSessionId = resumeSessionId
        )

        try {
            val response = sendRpcRequestInternal("initialize", initParams, ws, generation)
            if (currentGeneration.get() != generation) return

            // Check for server error response (e.g. unknown-session error)
            if (response.has("error")) {
                val errorObj = response.getJSONObject("error")
                val errMsg = errorObj.optString("message", "Unknown initialize error")
                Log.w(TAG, "Initialize RPC returned error: $errMsg")

                if (resumeSessionId != null) {
                    // Unknown or expired session: halt resume loop and persist SESSION_LOST
                    repository.markSessionLost(resumeSessionId)
                    _connectionState.value = ConnectionState.SESSION_LOST
                    _lastError.value = "Session $resumeSessionId expired or unknown: $errMsg"
                    closeCurrentSocket("Session unknown/expired")
                    return
                } else {
                    _connectionState.value = ConnectionState.ERROR
                    _lastError.value = errMsg
                    closeCurrentSocket(errMsg)
                    return
                }
            }

            val resultObj = response.optJSONObject("result")
            if (resultObj == null) {
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = "Initialize response missing result object"
                closeCurrentSocket("Invalid handshake response")
                return
            }

            val newSessionId = resultObj.getString("sessionId")
            val envInfoObj = resultObj.optJSONObject("environmentInfo")
            val envInfo = envInfoObj?.let { parseEnvironmentInfo(it) }

            val initResult = InitializeResult(newSessionId, envInfo)
            _observedEnvironment.value = envInfo

            // Detect if resume was attempted but server allocated a different session ID
            if (resumeSessionId != null && resumeSessionId != newSessionId) {
                Log.w(TAG, "Server did not resume $resumeSessionId, issued $newSessionId")
                repository.markSessionLost(resumeSessionId)
                _connectionState.value = ConnectionState.SESSION_LOST
                closeCurrentSocket("Session replaced")
                return
            }

            // Save new/resumed session
            repository.saveSession(initResult, endpoint)
            if (currentGeneration.get() != generation) return
            _currentSessionId.value = newSessionId
            forceFreshSession = false

            // Send 'initialized' notification with empty params object {}
            val notifSent = sendNotificationInternal("initialized", JSONObject(), ws, generation)
            if (!notifSent) {
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = "Failed to send initialized notification"
                closeCurrentSocket("Send initialized failed")
                return
            }

            if (currentGeneration.get() != generation) return
            _connectionState.value = ConnectionState.INITIALIZED

            // Reconcile active processes scoped to this session
            reconcileActiveProcesses(newSessionId, generation)

        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)
            if (currentGeneration.get() != generation) return
            _connectionState.value = ConnectionState.ERROR
            _lastError.value = "Initialize failed: ${e.message}"
            handleDisconnect(endpoint, e.message ?: "Handshake failed", generation)
        }
    }

    private fun parseEnvironmentInfo(obj: JSONObject): EnvironmentInfo {
        val shellObj = obj.optJSONObject("shell")
        val shell = shellObj?.let {
            ShellInfo(
                name = if (it.has("name") && !it.isNull("name")) it.optString("name") else null,
                path = if (it.has("path") && !it.isNull("path")) it.optString("path") else null
            )
        }
        val capsObj = obj.optJSONObject("capabilities")
        val capabilities = mutableMapOf<String, Boolean>()
        if (capsObj != null) {
            val keys = capsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                capabilities[k] = capsObj.optBoolean(k, false)
            }
        }
        return EnvironmentInfo(
            cwd = if (obj.has("cwd") && !obj.isNull("cwd")) obj.optString("cwd") else null,
            userHomeDir = if (obj.has("userHomeDir") && !obj.isNull("userHomeDir")) obj.optString("userHomeDir") else null,
            shell = shell,
            platformOs = if (obj.has("platformOs") && !obj.isNull("platformOs")) obj.optString("platformOs") else null,
            executorVersion = if (obj.has("executorVersion") && !obj.isNull("executorVersion")) obj.optString("executorVersion") else null,
            capabilities = capabilities
        )
    }

    private suspend fun reconcileActiveProcesses(sessionId: String, generation: Long) {
        val activeProcesses = repository.getCatchupProcesses(sessionId)
        for (proc in activeProcesses) {
            if (currentGeneration.get() != generation) return
            try {
                var cursor = proc.lastConsumedSeq
                var progressMade = true

                // Paginate catchup until no further chunks returned
                while (progressMade && currentGeneration.get() == generation) {
                    val readResult = readProcessCatchup(proc.processId, cursor)
                    if (currentGeneration.get() != generation) return
                    val newCursor = repository.getProcess(proc.processId)?.lastConsumedSeq
                    progressMade = (readResult.chunks.isNotEmpty() && newCursor != cursor)
                    if (readResult.chunks.isNotEmpty() && newCursor == cursor) {
                        repository.recordEvent("IN", "OUTPUT_GAP", "Output did not advance for ${proc.processId}", "afterSeq=$cursor")
                    }
                    cursor = newCursor
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error catching up process ${proc.processId}", e)
            }
        }
    }

    private suspend fun handleIncomingMessage(text: String, generation: Long) {
        if (currentGeneration.get() != generation) return
        try {
            val json = JSONObject(text)
            if (json.has("id") && (json.has("result") || json.has("error"))) {
                // RPC Response
                val id = json.getLong("id")
                val deferred = pendingRequests.remove(id)
                deferred?.complete(json)
                val summary = if (json.has("error")) "RPC Error id=$id" else "RPC Success id=$id"
                repository.recordEvent("IN", "RESPONSE", summary, text)
            } else if (json.has("method")) {
                // Server Notification
                val method = json.getString("method")
                val params = json.optJSONObject("params")
                repository.recordEvent("IN", method, "Server notification $method", text)
                handleServerNotification(method, params, generation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming message", e)
        }
    }

    private suspend fun handleServerNotification(method: String, params: JSONObject?, generation: Long) {
        if (params == null || currentGeneration.get() != generation) return
        when (method) {
            "process/output" -> {
                val processId = params.getString("processId")
                val seq = params.getLong("seq")
                val stream = params.getString("stream")
                val chunk = params.getString("chunk")
                repository.recordChunk(processId, seq, stream, chunk)
                repository.setOutputComplete(processId, false)
            }
            "process/exited" -> {
                val processId = params.getString("processId")
                val exitCode = if (params.has("exitCode") && !params.isNull("exitCode")) params.getInt("exitCode") else null
                repository.setProcessExited(processId, exitCode)
            }
            "process/closed" -> {
                val processId = params.getString("processId")
                repository.setProcessClosed(processId)
            }
        }
    }

    // Public API Methods with State Gate

    private fun ensureInitialized(generation: Long? = null) {
        if (_connectionState.value != ConnectionState.INITIALIZED) {
            throw IOException("Transport is not INITIALIZED (state: ${_connectionState.value})")
        }
        if (generation != null && currentGeneration.get() != generation) {
            throw IOException("Stale connection generation")
        }
    }

    suspend fun startProcess(
        argv: List<String>,
        cwd: String? = null,
        env: Map<String, String>? = null,
        tty: Boolean = true,
        title: String = argv.firstOrNull() ?: "Process",
        processId: String = UUID.randomUUID().toString()
    ): Result<ProcessStartParams> {
        val gen = currentGeneration.get()
        try { ensureInitialized(gen) } catch (e: Exception) { return Result.failure(e) }
        val sessId = _currentSessionId.value ?: return Result.failure(IllegalStateException("No active session"))
        val ws = webSocket ?: return Result.failure(IOException("WebSocket is not connected"))


        // Native exec-server requires an object even when inheriting its environment.
        val effectiveEnv = env ?: emptyMap()
        // 1. Transactionally record the process as STARTING in repository
        repository.createProcess(
            processId = processId,
            sessionId = sessId,
            title = title,
            argv = argv,
            cwd = cwd,
            env = effectiveEnv,
            tty = tty
        )

        val params = ProcessStartParams(
            processId = processId,
            argv = argv,
            cwd = cwd,
            env = effectiveEnv,
            tty = tty,
            pipeStdin = false,
            arg0 = null
        )

        return try {
            ensureInitialized(gen)
            val response = sendRpcRequestInternal("process/start", params, ws, gen)
            ensureInitialized(gen)
            if (response.has("error")) {
                val err = response.getJSONObject("error").optString("message", "process/start rejected")
                repository.setProcessExited(processId, -1)
                Result.failure(IOException(err))
            } else {
                repository.setProcessRunning(processId)
                Result.success(params)
            }
        } catch (e: Exception) {
            // Delivery uncertain: network dropped before ack
            Log.w(TAG, "process/start delivery uncertain for $processId", e)
            repository.setProcessDeliveryUncertain(processId, true)
            Result.failure(e)
        }
    }

    suspend fun readProcessCatchup(processId: String, afterSeq: Long?): ProcessReadResult {
        val gen = currentGeneration.get()
        ensureInitialized(gen)
        val ws = webSocket ?: throw IOException("WebSocket is not connected")

        val params = ProcessReadParams(
            processId = processId,
            afterSeq = afterSeq,
            maxBytes = 65536,
            waitMs = 500
        )
        val response = sendRpcRequestInternal("process/read", params, ws, gen)
        ensureInitialized(gen)
        if (response.has("error")) {
            val err = response.getJSONObject("error").optString("message", "process/read error")
            throw IOException(err)
        }

        val resultObj = response.optJSONObject("result") ?: throw IOException("process/read response missing result")

        val chunksArray = resultObj.optJSONArray("chunks") ?: JSONArray()
        val chunkList = mutableListOf<OutputChunkDto>()
        for (i in 0 until chunksArray.length()) {
            val item = chunksArray.getJSONObject(i)
            chunkList.add(
                OutputChunkDto(
                    seq = item.getLong("seq"),
                    stream = item.getString("stream"),
                    chunk = item.getString("chunk")
                )
            )
        }

        val exited = resultObj.optBoolean("exited", false)
        val exitCode = if (resultObj.has("exitCode") && !resultObj.isNull("exitCode")) resultObj.getInt("exitCode") else null
        val closed = resultObj.optBoolean("closed", false)
        val nextSeq = if (resultObj.has("nextSeq")) resultObj.getLong("nextSeq") else null
        val failure = if (resultObj.has("failure")) resultObj.get("failure") else null
        val sandboxDenied = resultObj.optBoolean("sandboxDenied", false)

        // Clear uncertainty now that read has contacted server
        repository.setProcessDeliveryUncertain(processId, false)

        // A read page certifies output coverage across intervening lifecycle events.
        repository.recordReadPage(processId, afterSeq, chunkList)
        ensureInitialized(gen)
        val metadata = JSONObject(resultObj.toString()).apply { remove("chunks") }
        repository.putSetting("processResult:$processId", metadata.toString())

        if (exited) {
            repository.setProcessExited(processId, exitCode)
        }
        if (closed) {
            repository.setProcessClosed(processId)
        }
        repository.setOutputComplete(processId, closed && chunkList.isEmpty())

        return ProcessReadResult(
            chunks = chunkList,
            nextSeq = nextSeq,
            exited = exited,
            exitCode = exitCode,
            closed = closed,
            failure = failure,
            sandboxDenied = sandboxDenied
        )
    }

    suspend fun writeProcess(processId: String, rawBytes: ByteArray): WriteStatus {
        val gen = currentGeneration.get()
        try { ensureInitialized(gen) } catch (e: Exception) { return WriteStatus.Failed(e.message ?: "Not initialized") }
        val ws = webSocket ?: return WriteStatus.Failed("WebSocket is not connected")

        val base64Chunk = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        val writeId = UUID.randomUUID().toString()
        val params = ProcessWriteParams(
            processId = processId,
            chunk = base64Chunk,
            writeId = writeId
        )

        return try {
            val response = sendRpcRequestInternal("process/write", params, ws, gen)
            ensureInitialized(gen)
            if (response.has("error")) {
                val err = response.getJSONObject("error").optString("message", "write error")
                WriteStatus.Failed(err)
            } else {
                val res = response.optJSONObject("result")
                val statusStr = res?.optString("status", "") ?: ""
                if (statusStr.isEmpty()) return WriteStatus.Failed("Malformed process/write response: missing status")
                when (statusStr.lowercase()) {
                    "accepted" -> WriteStatus.Accepted
                    "starting" -> WriteStatus.Starting
                    "stdinclosed" -> WriteStatus.StdinClosed
                    "unknownprocess" -> WriteStatus.UnknownProcess
                    else -> WriteStatus.Other(statusStr)
                }
            }
        } catch (e: Exception) {
            WriteStatus.Failed(e.message ?: "Write failed")
        }
    }

    suspend fun signalProcess(processId: String, signal: String): Result<Boolean> {
        val gen = currentGeneration.get()
        try { ensureInitialized(gen) } catch (e: Exception) { return Result.failure(e) }
        val ws = webSocket ?: return Result.failure(IOException("WebSocket is not connected"))

        if (signal != "interrupt") {
            return Result.failure(IllegalArgumentException("Only 'interrupt' signal is supported by codex exec-server"))
        }

        val params = ProcessSignalParams(processId = processId, signal = signal)
        return try {
            val response = sendRpcRequestInternal("process/signal", params, ws, gen)
            ensureInitialized(gen)
            if (response.has("error")) Result.failure(IOException(response.getJSONObject("error").optString("message", "Signal rejected")))
            else Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun terminateProcess(processId: String): Result<Boolean> {
        val gen = currentGeneration.get()
        try { ensureInitialized(gen) } catch (e: Exception) { return Result.failure(e) }
        val ws = webSocket ?: return Result.failure(IOException("WebSocket is not connected"))

        val params = ProcessTerminateParams(processId = processId)
        return try {
            val response = sendRpcRequestInternal("process/terminate", params, ws, gen)
            ensureInitialized(gen)
            if (response.has("error")) {
                val err = response.getJSONObject("error").optString("message", "terminate error")
                Result.failure(IOException(err))
            } else {
                val running = response.optJSONObject("result")?.optBoolean("running", false) ?: false
                if (!running) {
                    repository.setProcessClosed(processId)
                }
                Result.success(running)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Execute filesystem operations in the executor's filesystem identity.
     * Mutations are never replayed after a timeout or connection turnover.
     */
    suspend fun fileSystemRequest(method: String, params: JSONObject, expectedGeneration: Long = socketGeneration): JSONObject {
        require(method.startsWith("fs/")) { "Expected an fs/ protocol method" }
        val generation = expectedGeneration
        ensureInitialized(generation)
        val socket = webSocket ?: throw IOException("WebSocket is not connected")
        val response = sendRpcRequestInternal(method, params, socket, generation)
        ensureInitialized(generation)
        response.optJSONObject("error")?.let { error ->
            throw IOException("$method: " + error.optString("message", error.toString()))
        }
        return response.optJSONObject("result")
            ?: throw IOException("$method response missing result")
    }

    // Low-level RPC sending bound to captured socket & generation

    private suspend fun sendRpcRequestInternal(
        method: String,
        params: Any?,
        capturedWs: WebSocket,
        capturedGen: Long
    ): JSONObject {
        if (currentGeneration.get() != capturedGen) {
            throw IOException("Stale generation before RPC send")
        }

        val id = requestIdCounter.getAndIncrement()
        val json = JSONObject().apply {
            put("id", id)
            put("method", method)
            when (params) {
                null -> put("params", JSONObject())
                is JSONObject -> put("params", params)
                else -> {
                    val adapter = moshi.adapter<Any>(params.javaClass)
                    put("params", JSONObject(adapter.toJson(params)))
                }
            }
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        val jsonStr = json.toString()
        return try {
        repository.recordEvent("OUT", method, "RPC Call $method id=$id", jsonStr)

        if (currentGeneration.get() != capturedGen) {
            pendingRequests.remove(id)
            throw IOException("Stale generation after recording RPC")
        }

        val sent = capturedWs.send(jsonStr)
        if (!sent) {
            pendingRequests.remove(id)
            throw IOException("Failed to send WebSocket frame for $method (send returned false)")
        }

            withTimeout(rpcTimeoutMs) {
                deferred.await()
            }
        } finally {
            // Remove from pending map on success, error, timeout, or cancellation
            pendingRequests.remove(id)
        }
    }

    private suspend fun sendNotificationInternal(
        method: String,
        params: Any?,
        capturedWs: WebSocket,
        capturedGen: Long
    ): Boolean {
        if (currentGeneration.get() != capturedGen) return false

        val json = JSONObject().apply {
            put("method", method)
            when (params) {
                null -> put("params", JSONObject())
                is JSONObject -> put("params", params)
                else -> {
                    val adapter = moshi.adapter<Any>(params.javaClass)
                    put("params", JSONObject(adapter.toJson(params)))
                }
            }
        }
        val jsonStr = json.toString()
        repository.recordEvent("OUT", method, "Notification $method", jsonStr)
        if (currentGeneration.get() != capturedGen) return false
        return capturedWs.send(jsonStr)
    }
}
