package com.example.ui.viewmodel

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.OutputChunkEntity
import com.example.data.db.ProcessEntity
import com.example.data.db.ProtocolEventEntity
import com.example.data.model.ConnectionState
import com.example.data.model.EnvironmentInfo
import com.example.data.model.ProcessStatus
import com.example.data.repository.ExecRepository
import com.example.service.ExecServerService
import com.example.service.TermuxLauncher
import com.example.transport.CodexWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AcceptanceCheckStep(
    val name: String,
    val description: String,
    val status: StepStatus, // PENDING, RUNNING, PASSED, FAILED
    val detail: String? = null
)

enum class StepStatus { PENDING, RUNNING, PASSED, FAILED }

class MainViewModel(
    private val repository: ExecRepository
) : ViewModel() {

    private val TAG = "MainViewModel"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    val endpoint: StateFlow<String> = repository.getEndpointFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExecRepository.DEFAULT_ENDPOINT)

    val processes: StateFlow<List<ProcessEntity>> = repository.getAllProcessesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val protocolEvents: StateFlow<List<ProtocolEventEntity>> = repository.getRecentEventsFlow(150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedProcessId = MutableStateFlow<String?>(null)
    val selectedProcessId: StateFlow<String?> = _selectedProcessId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedProcessChunks: StateFlow<List<OutputChunkEntity>> = _selectedProcessId
        .flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList())
            else repository.getChunksFlow(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedProcess: StateFlow<ProcessEntity?> = _selectedProcessId
        .flatMapLatest { pid ->
            if (pid == null) flowOf(null)
            else repository.getProcessFlow(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _healthStatus = MutableStateFlow<String?>("Unchecked")
    val healthStatus: StateFlow<String?> = _healthStatus.asStateFlow()

    private val _termuxLaunchMessage = MutableStateFlow<String?>(null)
    val termuxLaunchMessage: StateFlow<String?> = _termuxLaunchMessage.asStateFlow()

    private val _acceptanceSteps = MutableStateFlow<List<AcceptanceCheckStep>>(emptyList())
    val acceptanceSteps: StateFlow<List<AcceptanceCheckStep>> = _acceptanceSteps.asStateFlow()

    private val _isAcceptanceRunning = MutableStateFlow(false)
    val isAcceptanceRunning: StateFlow<Boolean> = _isAcceptanceRunning.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<ConnectionState> = ExecServerService.activeClientFlow
        .flatMapLatest { it?.connectionState ?: flowOf(ConnectionState.DISCONNECTED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    @OptIn(ExperimentalCoroutinesApi::class)
    val observedEnvironment: StateFlow<EnvironmentInfo?> = ExecServerService.activeClientFlow
        .flatMapLatest { it?.observedEnvironment ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val lastConnectionError: StateFlow<String?> = ExecServerService.activeClientFlow
        .flatMapLatest { it?.lastError ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectProcess(processId: String?) {
        _selectedProcessId.value = processId
    }

    fun updateEndpoint(newEndpoint: String) {
        viewModelScope.launch {
            repository.setEndpoint(newEndpoint)
        }
    }

    private val _lastWriteStatus = MutableStateFlow<String?>(null)
    val lastWriteStatus: StateFlow<String?> = _lastWriteStatus.asStateFlow()

    fun connect(context: Context, endpoint: String? = null) {
        viewModelScope.launch {
            endpoint?.let { repository.setEndpoint(it) }
            val ep = repository.getEndpoint()
            try { ExecServerService.startService(context, ep) } catch (e: Exception) { _lastWriteStatus.value = "Error: ${e.message}" }
        }
    }

    fun connectFreshSession(context: Context, endpoint: String? = null) {
        viewModelScope.launch {
            endpoint?.let { repository.setEndpoint(it) }
            val ep = repository.getEndpoint()
            try { ExecServerService.startService(context, ep, freshSession = true) } catch (e: Exception) { _lastWriteStatus.value = "Error: ${e.message}" }
        }
    }

    fun reconnect(context: Context) {
        ExecServerService.reconnectService(context)
    }

    fun stop(context: Context) {
        ExecServerService.stopService(context)
    }

    fun checkHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _healthStatus.value = "Checking..."
            val ep = repository.getEndpoint()
            // Derive http url from ws url
            val httpUrl = ep.replace("ws://", "http://").replace("wss://", "https://")
                .trimEnd('/') + "/readyz"

            try {
                val req = Request.Builder().url(httpUrl).build()
                val resp = httpClient.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string()?.trim() ?: ""
                _healthStatus.value = if (resp.isSuccessful) {
                    "HTTP $code OK ($body)"
                } else {
                    "HTTP $code ($body)"
                }
            } catch (e: Exception) {
                _healthStatus.value = "Unreachable: ${e.message}"
            }
        }
    }

    fun launchBashShell(cwd: String? = null) {
        viewModelScope.launch {
            val client = ExecServerService.activeClient
            if (client == null || client.connectionState.value != ConnectionState.INITIALIZED) {
                return@launch
            }
            val defaultCwd = cwd ?: "file:///data/data/com.termux/files/home"
            val env = mapOf(
                "HOME" to "/data/data/com.termux/files/home",
                "PREFIX" to "/data/data/com.termux/files/usr",
                "PATH" to "/data/data/com.termux/files/home/.local/bin:/data/data/com.termux/files/usr/bin:/system/bin",
                "TERM" to "xterm-256color"
            )
            val result = client.startProcess(
                argv = listOf("/data/data/com.termux/files/usr/bin/bash", "-l"),
                cwd = defaultCwd,
                env = env,
                tty = true,
                title = "Interactive Bash Shell"
            )
            result.onSuccess { params ->
                _selectedProcessId.value = params.processId
            }.onFailure { _lastWriteStatus.value = "Error: ${it.message}" }
        }
    }

    fun runOneShotCommand(command: String, cwd: String? = null) {
        viewModelScope.launch {
            val client = ExecServerService.activeClient
            if (client == null || client.connectionState.value != ConnectionState.INITIALIZED) {
                return@launch
            }
            val defaultCwd = cwd ?: "file:///data/data/com.termux/files/home"
            val env = mapOf(
                "HOME" to "/data/data/com.termux/files/home",
                "PREFIX" to "/data/data/com.termux/files/usr",
                "PATH" to "/data/data/com.termux/files/home/.local/bin:/data/data/com.termux/files/usr/bin:/system/bin"
            )
            val result = client.startProcess(
                argv = listOf("/system/bin/sh", "-c", command),
                cwd = defaultCwd,
                env = env,
                tty = false,
                title = "sh -c: $command"
            )
            result.onSuccess { params ->
                _selectedProcessId.value = params.processId
            }.onFailure { _lastWriteStatus.value = "Error: ${it.message}" }
        }
    }

    fun writeInput(processId: String, text: String, enter: Boolean = false) {
        viewModelScope.launch {
            val client = ExecServerService.activeClient ?: return@launch
            val fullText = if (enter) "$text\n" else text
            val status = client.writeProcess(processId, fullText.toByteArray(Charsets.UTF_8))
            _lastWriteStatus.value = when (status) {
                is com.example.transport.WriteStatus.Accepted -> "Accepted"
                is com.example.transport.WriteStatus.Starting -> "Starting"
                is com.example.transport.WriteStatus.StdinClosed -> "Stdin Closed"
                is com.example.transport.WriteStatus.UnknownProcess -> "Unknown Process"
                is com.example.transport.WriteStatus.Other -> status.raw
                is com.example.transport.WriteStatus.Failed -> "Error: ${status.message}"
            }
        }
    }

    fun writeRawBase64(processId: String, base64: String) {
        try { writeInput(processId, String(Base64.decode(base64, Base64.NO_WRAP), Charsets.UTF_8)) }
        catch (e: Exception) { _lastWriteStatus.value = "Error: ${e.message}" }
    }

    fun sendCtrlC(processId: String) {
        viewModelScope.launch {
            val result = ExecServerService.activeClient?.signalProcess(processId, "interrupt")
            _lastWriteStatus.value = result?.fold({ "Interrupt requested" }, { "Error: ${it.message}" }) ?: "Error: disconnected"
        }
    }

    fun sendEof(processId: String) = writeInput(processId, "\u0004")

    fun terminateProcess(processId: String) {
        viewModelScope.launch {
            val result = ExecServerService.activeClient?.terminateProcess(processId)
            _lastWriteStatus.value = result?.fold(
                { "Termination acknowledged; was running: $it" },
                { "Error: ${it.message}" }
            ) ?: "Error: disconnected"
        }
    }

    fun startTermuxExecutor(context: Context, onRequestPermission: (() -> Unit)? = null) {
        val result = TermuxLauncher.startTermuxExecutor(context)
        _termuxLaunchMessage.value = when (result) {
            is TermuxLauncher.TermuxLaunchResult.Success ->
                "Termux RUN_COMMAND intent dispatched successfully!\nNote: Intent dispatched != executor ready. Wait a few seconds, then tap 'Probe /readyz'."
            is TermuxLauncher.TermuxLaunchResult.Error -> {
                if (result.message.contains("Missing RUN_COMMAND permission") && onRequestPermission != null) {
                    onRequestPermission()
                }
                "Termux Launch Failed: ${result.message}\nHint: ${result.recoveryHint}"
            }
        }
    }

    fun dismissTermuxMessage() {
        _termuxLaunchMessage.value = null
    }

    fun runAcceptanceChecks() {
        if (_isAcceptanceRunning.value) return
        _isAcceptanceRunning.value = true
        viewModelScope.launch {
            val owned = mutableListOf<String>()
            val cleanupErrors = mutableListOf<String>()
            val startedAt = System.currentTimeMillis()
            val steps = mutableListOf(
                AcceptanceCheckStep("1. Health Endpoint", "GET /readyz", StepStatus.PENDING),
                AcceptanceCheckStep("2. Initialize Handshake", "Observed initialized session", StepStatus.PENDING),
                AcceptanceCheckStep("3. One-Shot Command", "Distinct stdout/stderr and Termux UID", StepStatus.PENDING),
                AcceptanceCheckStep("4. Exit Code Handling", "Observed exit 42", StepStatus.PENDING),
                AcceptanceCheckStep("5. Interactive PTY Roundtrip", "Write input and verify reply + exit 7", StepStatus.PENDING)
            )
            fun step(i: Int, status: StepStatus, detail: String) {
                steps[i] = steps[i].copy(status = status, detail = detail)
                _acceptanceSteps.value = steps.toList()
            }
            val client = ExecServerService.activeClient
            val cwd = "file:///data/data/com.termux/files/home"
            val env = mapOf(
                "HOME" to "/data/data/com.termux/files/home",
                "PREFIX" to "/data/data/com.termux/files/usr",
                "PATH" to "/data/data/com.termux/files/home/.local/bin:/data/data/com.termux/files/usr/bin:/system/bin",
                "TERM" to "xterm-256color"
            )
            suspend fun output(id: String, stream: String): String {
                val bytes = java.io.ByteArrayOutputStream()
                repository.getChunks(id).filter { it.stream == stream }.sortedBy { it.seq }.forEach {
                    bytes.write(Base64.decode(it.chunkBase64, Base64.NO_WRAP))
                }
                return String(bytes.toByteArray(), Charsets.UTF_8)
            }
            suspend fun drainUntilExit(id: String) {
                withTimeout(10000) {
                    while (true) {
                        val cursor = repository.getProcess(id)?.lastConsumedSeq
                        val read = client!!.readProcessCatchup(id, cursor)
                        if ((read.exited || read.closed) && read.chunks.isEmpty()) break
                        delay(50)
                    }
                }
            }
            try {
                withTimeout(30000) {
                    step(0, StepStatus.RUNNING, "Probing")
                    val ep = repository.getEndpoint()
                    val url = ep.replace("ws://", "http://").replace("wss://", "https://").trimEnd('/') + "/readyz"
                    withContext(Dispatchers.IO) {
                        httpClient.newCall(Request.Builder().url(url).build()).execute().use {
                            check(it.isSuccessful) { "Health HTTP " + it.code }
                        }
                    }
                    step(0, StepStatus.PASSED, "Executor health reachable")
                    check(client != null && client.connectionState.value == ConnectionState.INITIALIZED) { "Transport not initialized" }
                    step(1, StepStatus.PASSED, "Session=" + client.currentSessionId.value + "; OS=" + client.observedEnvironment.value?.platformOs)
                    val marker = java.util.UUID.randomUUID().toString().replace("-", "")
                    val oneId = java.util.UUID.randomUUID().toString()
                    owned.add(oneId)
                    step(2, StepStatus.RUNNING, "Executing owned process")
                    client.startProcess(
                        argv = listOf("/system/bin/sh", "-c", "printf 'OUT:$marker\\n'; printf 'ERR:$marker\\n' >&2; printf 'UID:'; /system/bin/id -u; exit 42"),
                        cwd = cwd, env = env, tty = false, title = "Acceptance One-Shot", processId = oneId
                    ).getOrThrow()
                    drainUntilExit(oneId)
                    val stdout = output(oneId, "stdout")
                    val stderr = output(oneId, "stderr")
                    check(stdout.contains("OUT:$marker") && stderr.contains("ERR:$marker")) { "Distinct markers missing" }
                    val uid = Regex("UID:(\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()
                    check(uid != null && uid != android.os.Process.myUid()) { "Executor UID missing or identical to APK UID: $uid" }
                    step(2, StepStatus.PASSED, "Distinct markers; executor UID=$uid; APK UID=" + android.os.Process.myUid())
                    check(repository.getProcess(oneId)?.exitCode == 42) { "Expected exit 42" }
                    step(3, StepStatus.PASSED, "Observed exit 42")
                    val ptyId = java.util.UUID.randomUUID().toString()
                    owned.add(ptyId)
                    step(4, StepStatus.RUNNING, "Starting owned PTY")
                    client.startProcess(
                        argv = listOf("/data/data/com.termux/files/usr/bin/bash", "-c",
                            "read -r line; printf 'PTY_REPLY:%s\\n' \"\$line\"; printf 'UID:'; /system/bin/id -u; exit 7"),
                        cwd = cwd, env = env, tty = true, title = "Acceptance PTY", processId = ptyId
                    ).getOrThrow()
                    val write = client.writeProcess(ptyId, "INPUT_$marker\n".toByteArray(Charsets.UTF_8))
                    check(write is com.example.transport.WriteStatus.Accepted) { "PTY write not accepted: $write" }
                    drainUntilExit(ptyId)
                    val ptyOut = output(ptyId, "stdout") + output(ptyId, "stderr")
                    check(ptyOut.contains("PTY_REPLY:INPUT_$marker")) { "Actual PTY response missing" }
                    check(ptyOut.contains("UID:$uid") && repository.getProcess(ptyId)?.exitCode == 7) { "PTY UID or exit mismatch" }
                    step(4, StepStatus.PASSED, "Exact input/reply, UID=$uid, exit=7")
                }
            } catch (e: Exception) {
                for (i in steps.indices) {
                    if (steps[i].status == StepStatus.RUNNING || steps[i].status == StepStatus.PENDING) {
                        step(i, StepStatus.FAILED, e.message ?: e.javaClass.name)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        withTimeout(10000) {
                            for (id in owned) {
                                val result = client?.terminateProcess(id)
                                if (result == null || result.isFailure) {
                                    cleanupErrors.add(id + ": " + (result?.exceptionOrNull()?.message ?: "disconnected"))
                                } else {
                                    try { drainUntilExit(id) }
                                    catch (e: Exception) { cleanupErrors.add(id + ": " + e.message) }
                                }
                            }
                        }
                    } catch (e: Exception) { cleanupErrors.add(e.message ?: "Cleanup timeout") }
                    val report = org.json.JSONObject().apply {
                        put("startedAt", startedAt); put("finishedAt", System.currentTimeMillis())
                        put("sessionId", client?.currentSessionId?.value ?: org.json.JSONObject.NULL)
                        put("ownedProcessIds", org.json.JSONArray(owned))
                        put("cleanupErrors", org.json.JSONArray(cleanupErrors))
                        put("steps", org.json.JSONArray().apply {
                            steps.forEach { s -> put(org.json.JSONObject().apply {
                                put("name", s.name); put("description", s.description)
                                put("status", s.status.name); put("detail", s.detail ?: "")
                            }) }
                        })
                    }
                    try { repository.putSetting("lastAcceptanceReport", report.toString()) }
                    finally { _isAcceptanceRunning.value = false }
                }
            }
        }
    }

    fun buildDiagnosticReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== CODEX GOAL DIAGNOSTIC REPORT ===")
        sb.appendLine("Timestamp: ${java.util.Date()}")
        sb.appendLine("Endpoint: ${endpoint.value}")
        sb.appendLine("Health: ${healthStatus.value}")
        sb.appendLine("Connection: ${connectionState.value}")
        sb.appendLine("SessionId: ${ExecServerService.activeClient?.currentSessionId?.value ?: "None"}")
        val env = observedEnvironment.value
        sb.appendLine("Environment: cwd=${env?.cwd}, shell=${env?.shell?.path}, os=${env?.platformOs}, version=${env?.executorVersion}")
        sb.appendLine("\n--- Acceptance Check Results ---")
        val steps = acceptanceSteps.value
        if (steps.isEmpty()) {
            sb.appendLine("No acceptance checks executed yet.")
        } else {
            for (s in steps) {
                sb.appendLine("[${s.status}] ${s.name}: ${s.detail ?: ""}")
            }
        }
        sb.appendLine("\n--- Processes (${processes.value.size}) ---")
        for (p in processes.value.take(5)) {
            sb.appendLine("Process ${p.processId.take(8)} [${p.status}] exit=${p.exitCode} title=${p.title}")
        }
        return sb.toString()
    }
}
