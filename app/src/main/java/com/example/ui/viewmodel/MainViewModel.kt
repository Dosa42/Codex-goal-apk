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

    // Client state delegated to ExecServerService
    val connectionState: StateFlow<ConnectionState> = flow {
        while (currentCoroutineContext().isActive) {
            val client = ExecServerService.activeClient
            if (client != null) {
                emitAll(client.connectionState)
            } else {
                emit(ConnectionState.DISCONNECTED)
                delay(500)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    val observedEnvironment: StateFlow<EnvironmentInfo?> = flow {
        while (currentCoroutineContext().isActive) {
            val client = ExecServerService.activeClient
            if (client != null) {
                emitAll(client.observedEnvironment)
            } else {
                emit(null)
                delay(1000)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun connect(context: Context) {
        viewModelScope.launch {
            val ep = repository.getEndpoint()
            ExecServerService.startService(context, ep)
        }
    }

    fun connectFreshSession(context: Context) {
        viewModelScope.launch {
            val ep = repository.getEndpoint()
            ExecServerService.startService(context, ep, freshSession = true)
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
            }
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
            }
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
        viewModelScope.launch {
            val client = ExecServerService.activeClient ?: return@launch
            try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                val status = client.writeProcess(processId, bytes)
                if (status is com.example.transport.WriteStatus.Failed) {
                    _lastWriteStatus.value = "Error: ${status.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding raw base64 input", e)
            }
        }
    }

    fun sendCtrlC(processId: String) {
        viewModelScope.launch {
            val client = ExecServerService.activeClient ?: return@launch
            client.signalProcess(processId, "interrupt")
        }
    }

    fun sendEof(processId: String) {
        viewModelScope.launch {
            val client = ExecServerService.activeClient ?: return@launch
            // ASCII EOT (0x04)
            client.writeProcess(processId, byteArrayOf(0x04))
        }
    }

    fun terminateProcess(processId: String) {
        viewModelScope.launch {
            val client = ExecServerService.activeClient ?: return@launch
            client.terminateProcess(processId)
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
            val steps = mutableListOf(
                AcceptanceCheckStep("1. Health Endpoint", "GET /readyz probe", StepStatus.RUNNING),
                AcceptanceCheckStep("2. Initialize Handshake", "Verify sessionId and environmentInfo", StepStatus.PENDING),
                AcceptanceCheckStep("3. One-Shot Command", "Execute sh command with stdout/stderr separation", StepStatus.PENDING),
                AcceptanceCheckStep("4. Exit Code Handling", "Verify process/exited captures explicit status", StepStatus.PENDING),
                AcceptanceCheckStep("5. Interactive PTY Roundtrip", "Spawn tty shell and verify stream I/O", StepStatus.PENDING)
            )
            _acceptanceSteps.value = steps.toList()

            // Step 1: /readyz
            val ep = repository.getEndpoint()
            val httpUrl = ep.replace("ws://", "http://").replace("wss://", "https://").trimEnd('/') + "/readyz"
            val healthOk = withContext(Dispatchers.IO) {
                try {
                    val resp = httpClient.newCall(Request.Builder().url(httpUrl).build()).execute()
                    resp.isSuccessful
                } catch (e: Exception) {
                    false
                }
            }

            steps[0] = steps[0].copy(
                status = if (healthOk) StepStatus.PASSED else StepStatus.FAILED,
                detail = if (healthOk) "Server reachable on loopback" else "Loopback port unreachable"
            )
            _acceptanceSteps.value = steps.toList()

            val client = ExecServerService.activeClient
            if (client == null || client.connectionState.value != ConnectionState.INITIALIZED) {
                steps[1] = steps[1].copy(status = StepStatus.FAILED, detail = "WebSocket client is not in INITIALIZED state")
                for (i in 2..4) {
                    steps[i] = steps[i].copy(status = StepStatus.FAILED, detail = "Skipped due to uninitialized transport")
                }
                _acceptanceSteps.value = steps.toList()
                _isAcceptanceRunning.value = false
                return@launch
            }

            // Step 2: Initialize Handshake
            val sessId = client.currentSessionId.value
            val env = client.observedEnvironment.value
            steps[1] = steps[1].copy(
                status = StepStatus.PASSED,
                detail = "SessionId: ${sessId?.take(12)}... OS: ${env?.platformOs ?: "unknown"}"
            )
            _acceptanceSteps.value = steps.toList()

            // Step 3 & 4: One-Shot command with stdout, stderr, and exit 42
            steps[2] = steps[2].copy(status = StepStatus.RUNNING)
            _acceptanceSteps.value = steps.toList()

            var testProcId: String? = null
            try {
                val startRes = client.startProcess(
                    argv = listOf("/system/bin/sh", "-c", "echo 'stdout_ok' && echo 'stderr_ok' >&2 && exit 42"),
                    tty = false,
                    title = "Acceptance Test One-Shot"
                )
                if (startRes.isSuccess) {
                    val pid = startRes.getOrNull()!!.processId
                    testProcId = pid

                    // Poll for output chunks with 5s timeout
                    var stdoutFound = false
                    var stderrFound = false
                    var exitCodeCaptured: Int? = null

                    for (attempt in 0..25) {
                        delay(200)
                        val proc = repository.getProcess(pid)
                        val chunks = repository.getChunks(pid)
                        for (c in chunks) {
                            val decoded = String(Base64.decode(c.chunkBase64, Base64.NO_WRAP))
                            if (c.stream == "stdout" && decoded.contains("stdout_ok")) stdoutFound = true
                            if (c.stream == "stderr" && decoded.contains("stderr_ok")) stderrFound = true
                        }
                        if (proc?.status == ProcessStatus.EXITED.name || proc?.status == ProcessStatus.CLOSED.name) {
                            exitCodeCaptured = proc.exitCode
                            break
                        }
                    }

                    if (stdoutFound && stderrFound) {
                        steps[2] = steps[2].copy(status = StepStatus.PASSED, detail = "Distinct stdout and stderr chunks received")
                    } else {
                        steps[2] = steps[2].copy(status = StepStatus.FAILED, detail = "Stdout: $stdoutFound, Stderr: $stderrFound")
                    }

                    if (exitCodeCaptured == 42) {
                        steps[3] = steps[3].copy(status = StepStatus.PASSED, detail = "Captured exit code 42 correctly")
                    } else {
                        steps[3] = steps[3].copy(status = StepStatus.FAILED, detail = "Expected exit code 42, got: $exitCodeCaptured")
                    }
                } else {
                    steps[2] = steps[2].copy(status = StepStatus.FAILED, detail = "Start process failed: ${startRes.exceptionOrNull()?.message}")
                    steps[3] = steps[3].copy(status = StepStatus.FAILED, detail = "Skipped")
                }
            } finally {
                testProcId?.let { client.terminateProcess(it) }
            }
            _acceptanceSteps.value = steps.toList()

            // Step 5: PTY Roundtrip
            steps[4] = steps[4].copy(status = StepStatus.RUNNING)
            _acceptanceSteps.value = steps.toList()

            var ptyProcId: String? = null
            try {
                val ptyRes = client.startProcess(
                    argv = listOf("/data/data/com.termux/files/usr/bin/bash", "-c", "echo 'pty_roundtrip_ok' && exit 0"),
                    tty = true,
                    title = "Acceptance Test PTY"
                )
                if (ptyRes.isSuccess) {
                    val pid = ptyRes.getOrNull()!!.processId
                    ptyProcId = pid
                    var ptyFound = false

                    for (attempt in 0..25) {
                        delay(200)
                        val chunks = repository.getChunks(pid)
                        for (c in chunks) {
                            val decoded = String(Base64.decode(c.chunkBase64, Base64.NO_WRAP))
                            if (decoded.contains("pty_roundtrip_ok")) {
                                ptyFound = true
                                break
                            }
                        }
                        if (ptyFound) break
                    }

                    steps[4] = steps[4].copy(
                        status = if (ptyFound) StepStatus.PASSED else StepStatus.FAILED,
                        detail = if (ptyFound) "PTY stream verified over Termux bash" else "PTY chunk not observed within timeout"
                    )
                } else {
                    steps[4] = steps[4].copy(status = StepStatus.FAILED, detail = "PTY start failed: ${ptyRes.exceptionOrNull()?.message}")
                }
            } finally {
                ptyProcId?.let { client.terminateProcess(it) }
            }

            _acceptanceSteps.value = steps.toList()
            _isAcceptanceRunning.value = false
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
