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
import com.example.data.model.ExecutorEnvironment
import com.example.data.model.ProcessStatus
import com.example.data.repository.ExecRepository
import com.example.service.ExecServerService
import com.example.service.TermuxLauncher
import com.example.transport.CodexWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

data class WorkspaceEntry(val name: String, val uri: String, val directory: Boolean, val file: Boolean)

data class WorkspaceState(
    val workingUri: String = "",
    val directoryUri: String = "",
    val configUri: String = "",
    val entries: List<WorkspaceEntry> = emptyList(),
    val fileUri: String? = null,
    val fileSessionId: String? = null,
    val text: String = "",
    val originalBase64: String? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val sessionId: String? = null,
    val status: String = "Connect to load the executor workspace",
    val error: String? = null
)

class MainViewModel(
    private val repository: ExecRepository
) : ViewModel() {

    private val TAG = "MainViewModel"

    private val workspaceMutex = Mutex()
    private var operationClient: CodexWebSocketClient? = null
    private var operationGeneration: Long = -1
    private val _workspace = MutableStateFlow(WorkspaceState())
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()

    init {
        viewModelScope.launch {
            ExecServerService.activeClientFlow.flatMapLatest { client ->
                client?.connectionState ?: flowOf(ConnectionState.DISCONNECTED)
            }.collect { state ->
                if (state == ConnectionState.INITIALIZED) loadWorkspace()
                else _workspace.update { it.copy(loaded = false, status = "Workspace connection: $state") }
            }
        }
    }

    private fun workspaceClient(): CodexWebSocketClient = ExecServerService.activeClient
        ?.takeIf { it.connectionState.value == ConnectionState.INITIALIZED }
        ?: throw IOException("Executor is not initialized")

    private suspend fun CodexWebSocketClient.workspaceRpc(method: String, params: org.json.JSONObject): org.json.JSONObject {
        check(this === operationClient && this === ExecServerService.activeClient) {
            "Executor changed during operation; reload before retrying"
        }
        return fileSystemRequest(method, params, operationGeneration)
    }

    private fun childUri(parent: String, name: String): String = ExecutorEnvironment.childUri(parent, name)

    fun workspacePath(uri: String): String = android.net.Uri.parse(uri).path ?: uri

    private fun pathUri(value: String): String = ExecutorEnvironment.fileUri(value.trim())

    private fun workspaceAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            workspaceMutex.withLock {
                _workspace.update { it.copy(busy = true, error = null) }
                try {
                    operationClient = workspaceClient()
                    operationGeneration = operationClient!!.socketGeneration
                    action()
                }
                catch (e: TimeoutCancellationException) { _workspace.update { it.copy(error = "Filesystem request timed out. A write may have reached the executor; reload to inspect its result.") } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _workspace.update { it.copy(error = e.message ?: e.javaClass.name) } }
                finally {
                    operationClient = null
                    operationGeneration = -1
                    _workspace.update { it.copy(busy = false) }
                    if (currentCoroutineContext().isActive) {
                        val state = _workspace.value
                        val client = ExecServerService.activeClient
                        repository.putSetting("lastWorkspaceSnapshot", org.json.JSONObject().apply {
                            put("timestamp", System.currentTimeMillis())
                            put("sessionId", client?.currentSessionId?.value ?: org.json.JSONObject.NULL)
                            put("socketGeneration", client?.socketGeneration ?: -1)
                            put("loaded", state.loaded); put("workingUri", state.workingUri)
                            put("directoryUri", state.directoryUri); put("configUri", state.configUri)
                            put("fileUri", state.fileUri ?: org.json.JSONObject.NULL)
                            put("dirty", state.dirty); put("status", state.status)
                            put("error", state.error ?: org.json.JSONObject.NULL)
                            put("entryCount", state.entries.size)
                        }.toString())
                    }
                }
            }
        }
    }

    private suspend fun listWorkspace(client: CodexWebSocketClient, uri: String): List<WorkspaceEntry> {
        val response = client.workspaceRpc("fs/readDirectory", org.json.JSONObject().put("path", uri))
        val array = response.getJSONArray("entries")
        return (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val name = entry.getString("fileName")
            WorkspaceEntry(name, childUri(uri, name), entry.getBoolean("isDirectory"), entry.getBoolean("isFile"))
        }.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun loadWorkspace() = workspaceAction {
        val client = workspaceClient()
        val generation = client.socketGeneration
        val executor = ExecutorEnvironment.from(client.observedEnvironment.value)
        val settingsDir = executor.configDirectoryUri
        val configUri = executor.configUri
        check(!_workspace.value.dirty || _workspace.value.fileSessionId == client.currentSessionId.value) {
            "Pending edits belong to another executor session; copy or close that file before loading this workspace"
        }
        var working = executor.defaultWorkingUri
        var status = "Default working folder; no workspace setting saved yet"
        var foundConfig = false
        client.workspaceRpc("fs/createDirectory", org.json.JSONObject().put("path", settingsDir).put("recursive", true))
        if (listWorkspace(client, settingsDir).any { it.name == "workspace.json" && it.file }) {
            val response = client.workspaceRpc("fs/readFile", org.json.JSONObject().put("path", configUri))
            val config = org.json.JSONObject(String(Base64.decode(response.getString("dataBase64"), Base64.NO_WRAP), Charsets.UTF_8))
            check(config.getInt("formatVersion") == 1) { "Unsupported workspace setting format" }
            working = pathUri(config.getString("workspaceUri"))
            status = "Working folder loaded from executor (${executor.platformOs})"
            foundConfig = true
        }
        val canonical = client.workspaceRpc("fs/canonicalize", org.json.JSONObject().put("path", working)).getString("path")
        val entries = listWorkspace(client, canonical)
        if (!foundConfig) {
            client.workspaceRpc("fs/createDirectory", org.json.JSONObject().put("path", settingsDir).put("recursive", true))
            val config = org.json.JSONObject().put("formatVersion", 1).put("workspaceUri", canonical).toString(2) + "\n"
            val encoded = Base64.encodeToString(config.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            client.workspaceRpc("fs/writeFile", org.json.JSONObject().put("path", configUri).put("dataBase64", encoded))
            check(client.workspaceRpc("fs/readFile", org.json.JSONObject().put("path", configUri)).getString("dataBase64") == encoded) {
                "Default workspace setting readback differs; reload"
            }
            status = "Default workspace setting created and read back on executor (${executor.platformOs})"
        }
        check(client.socketGeneration == generation) { "Connection changed while loading workspace; reload" }
        _workspace.update { it.copy(workingUri = canonical, directoryUri = canonical, configUri = configUri, sessionId = client.currentSessionId.value,
            entries = entries, loaded = true, status = status) }
    }

    fun browseWorkspace(path: String) = workspaceAction {
        check(!_workspace.value.dirty) { "Save the open file or close it before changing folders" }
        val client = workspaceClient()
        val canonical = client.workspaceRpc("fs/canonicalize", org.json.JSONObject().put("path", pathUri(path))).getString("path")
        val entries = listWorkspace(client, canonical)
        _workspace.update { it.copy(directoryUri = canonical, entries = entries, fileUri = null, fileSessionId = null,
            text = "", originalBase64 = null, dirty = false, status = "Folder read from executor") }
    }

    fun workspaceUp() {
        val parent = java.io.File(workspacePath(_workspace.value.directoryUri)).parent ?: "/"
        browseWorkspace(parent)
    }

    fun useWorkspaceDirectory() = workspaceAction {
        val client = workspaceClient()
        val state = _workspace.value
        check(state.loaded && state.configUri.isNotEmpty()) { "Load the workspace first" }
        val parent = state.configUri.substringBeforeLast('/')
        client.workspaceRpc("fs/createDirectory", org.json.JSONObject().put("path", parent).put("recursive", true))
        val text = org.json.JSONObject().put("formatVersion", 1).put("workspaceUri", state.directoryUri).toString(2) + "\n"
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        client.workspaceRpc("fs/writeFile", org.json.JSONObject().put("path", state.configUri).put("dataBase64", encoded))
        val readback = client.workspaceRpc("fs/readFile", org.json.JSONObject().put("path", state.configUri)).getString("dataBase64")
        check(readback == encoded) { "Workspace setting readback differs; reload before retrying" }
        _workspace.update { it.copy(workingUri = state.directoryUri, status = "Working folder saved and read back on executor") }
    }

    fun openWorkspaceFile(uri: String) = workspaceAction {
        val client = workspaceClient()
        val response = client.workspaceRpc("fs/readFile", org.json.JSONObject().put("path", pathUri(uri)))
        val encoded = response.getString("dataBase64")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        _workspace.update { it.copy(fileUri = uri, fileSessionId = client.currentSessionId.value,
            text = text, originalBase64 = encoded, dirty = false, status = "UTF-8 file read from executor") }
    }

    fun editWorkspaceText(text: String) { _workspace.update { it.copy(text = text, dirty = true) } }

    fun closeWorkspaceFile() { _workspace.update { it.copy(fileUri = null, fileSessionId = null, text = "", originalBase64 = null, dirty = false) } }

    fun createWorkspaceEntry(name: String, directory: Boolean) = workspaceAction {
        require(name.isNotBlank() && name !in listOf(".", "..") && '/' !in name && '\u0000' !in name) { "Enter one file or folder name" }
        val client = workspaceClient()
        val state = _workspace.value
        check(state.loaded) { "Load the workspace first" }
        check(listWorkspace(client, state.directoryUri).none { it.name == name }) { "An entry with that name already exists" }
        val uri = childUri(state.directoryUri, name)
        if (directory) client.workspaceRpc("fs/createDirectory", org.json.JSONObject().put("path", uri).put("recursive", false))
        else client.workspaceRpc("fs/writeFile", org.json.JSONObject().put("path", uri).put("dataBase64", ""))
        val entries = listWorkspace(client, state.directoryUri)
        _workspace.update { it.copy(entries = entries, status = "Created on executor: $name") }
    }

    fun saveWorkspaceFile() = workspaceAction {
        val client = workspaceClient()
        val state = _workspace.value
        val uri = state.fileUri ?: throw IOException("No file selected")
        check(state.fileSessionId != null && state.fileSessionId == client.currentSessionId.value) {
            "Executor session changed since opening this file; copy pending edits and reload the file before saving"
        }
        val current = client.workspaceRpc("fs/readFile", org.json.JSONObject().put("path", pathUri(uri))).getString("dataBase64")
        check(current == state.originalBase64) { "File changed on executor since opening; reload before saving" }
        val encoded = Base64.encodeToString(state.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        client.workspaceRpc("fs/writeFile", org.json.JSONObject().put("path", pathUri(uri)).put("dataBase64", encoded))
        val readback = client.workspaceRpc("fs/readFile", org.json.JSONObject().put("path", pathUri(uri))).getString("dataBase64")
        check(readback == encoded) { "File readback differs; inspect the file before retrying" }
        _workspace.update { it.copy(originalBase64 = encoded, dirty = false, status = "Saved and read back on executor") }
    }

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
            val client = ExecServerService.activeClient
            val ep = client?.currentEndpoint ?: repository.getEndpoint()

            try {
                val req = Request.Builder().url(ExecutorEnvironment.healthUrl(ep)).build()
                httpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string()?.trim() ?: ""
                    _healthStatus.value = if (resp.isSuccessful) "HTTP $code OK ($body)" else "HTTP $code ($body)"
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
            val executor = try { ExecutorEnvironment.from(client.observedEnvironment.value) }
                catch (e: Exception) { _lastWriteStatus.value = "Error: ${e.message}"; return@launch }
            val defaultCwd = cwd?.takeIf { it.isNotBlank() } ?: _workspace.value.takeIf { it.loaded && it.sessionId == client.currentSessionId.value }?.workingUri
                ?: run { _lastWriteStatus.value = "Error: Load the connected executor workspace first"; return@launch }
            val result = client.startProcess(
                argv = executor.interactiveArgv(),
                cwd = defaultCwd,
                env = executor.environment(tty = true),
                tty = true,
                title = "Interactive ${executor.shellName} (${executor.platformOs})"
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
            val executor = try { ExecutorEnvironment.from(client.observedEnvironment.value) }
                catch (e: Exception) { _lastWriteStatus.value = "Error: ${e.message}"; return@launch }
            val defaultCwd = cwd?.takeIf { it.isNotBlank() } ?: _workspace.value.takeIf { it.loaded && it.sessionId == client.currentSessionId.value }?.workingUri
                ?: run { _lastWriteStatus.value = "Error: Load the connected executor workspace first"; return@launch }
            val result = client.startProcess(
                argv = executor.commandArgv(command),
                cwd = defaultCwd,
                env = executor.environment(tty = false),
                tty = false,
                title = "${executor.shellName} -c: $command"
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
                AcceptanceCheckStep("1. Health Endpoint", "GET connected executor health endpoint", StepStatus.PENDING),
                AcceptanceCheckStep("2. Initialize Handshake", "Observed initialized session", StepStatus.PENDING),
                AcceptanceCheckStep("3. One-Shot Command", "Distinct stdout/stderr and executor UID", StepStatus.PENDING),
                AcceptanceCheckStep("4. Exit Code Handling", "Observed exit 42", StepStatus.PENDING),
                AcceptanceCheckStep("5. Interactive PTY Roundtrip", "Write input and verify reply + exit 7", StepStatus.PENDING)
            )
            fun step(i: Int, status: StepStatus, detail: String) {
                steps[i] = steps[i].copy(status = status, detail = detail)
                _acceptanceSteps.value = steps.toList()
            }
            val client = ExecServerService.activeClient
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
                        // Exit may precede the final PTY bytes. Closed confirms drain.
                        if (read.closed && read.chunks.isEmpty()) break
                        delay(50)
                    }
                }
            }
            try {
                withTimeout(30000) {
                    check(client != null && client.connectionState.value == ConnectionState.INITIALIZED) { "Transport not initialized" }
                    val executor = ExecutorEnvironment.from(client.observedEnvironment.value)
                    val cwd = _workspace.value.takeIf { it.loaded && it.sessionId == client.currentSessionId.value }?.workingUri
                        ?: executor.advertisedCwdUri
                    step(0, StepStatus.RUNNING, "Probing")
                    val ep = client.currentEndpoint ?: repository.getEndpoint()
                    val url = ExecutorEnvironment.healthUrl(ep)
                    withContext(Dispatchers.IO) {
                        httpClient.newCall(Request.Builder().url(url).build()).execute().use {
                            check(it.isSuccessful) { "Health HTTP " + it.code }
                        }
                    }
                    step(0, StepStatus.PASSED, "Executor health reachable")
                    step(1, StepStatus.PASSED, "Session=" + client.currentSessionId.value + "; OS=" + client.observedEnvironment.value?.platformOs)
                    val marker = java.util.UUID.randomUUID().toString().replace("-", "")
                    val oneId = java.util.UUID.randomUUID().toString()
                    owned.add(oneId)
                    step(2, StepStatus.RUNNING, "Executing owned process")
                    client.startProcess(
                        argv = executor.commandArgv("printf 'OUT:$marker\\n'; printf 'ERR:$marker\\n' >&2; printf 'UID:'; id -u; exit 42"),
                        cwd = cwd, env = executor.environment(tty = false), tty = false, title = "Acceptance One-Shot", processId = oneId
                    ).getOrThrow()
                    drainUntilExit(oneId)
                    val stdout = output(oneId, "stdout")
                    val stderr = output(oneId, "stderr")
                    check(stdout.contains("OUT:$marker") && stderr.contains("ERR:$marker")) { "Distinct markers missing" }
                    val uid = Regex("UID:(\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()
                    check(uid != null) { "Executor UID missing" }
                    if (executor.termux) check(uid != android.os.Process.myUid()) { "Termux executor must use its own UID, observed $uid" }
                    step(2, StepStatus.PASSED, "Distinct markers; executor UID=$uid; APK UID=" + android.os.Process.myUid())
                    check(repository.getProcess(oneId)?.exitCode == 42) { "Expected exit 42" }
                    step(3, StepStatus.PASSED, "Observed exit 42")
                    val ptyId = java.util.UUID.randomUUID().toString()
                    owned.add(ptyId)
                    step(4, StepStatus.RUNNING, "Starting owned PTY")
                    client.startProcess(
                        argv = executor.commandArgv("read -r line; printf 'PTY_REPLY:%s\\n' \"\$line\"; printf 'UID:'; id -u; exit 7"),
                        cwd = cwd, env = executor.environment(tty = true), tty = true, title = "Acceptance PTY", processId = ptyId
                    ).getOrThrow()
                    val write = client.writeProcess(ptyId, "INPUT_$marker\n".toByteArray(Charsets.UTF_8))
                    check(write is com.example.transport.WriteStatus.Accepted) { "PTY write not accepted: $write" }
                    drainUntilExit(ptyId)
                    val ptyOut = output(ptyId, "pty") + output(ptyId, "stdout") + output(ptyId, "stderr")
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
                        put("endpoint", client?.currentEndpoint ?: org.json.JSONObject.NULL)
                        put("executorOs", client?.observedEnvironment?.value?.platformOs ?: org.json.JSONObject.NULL)
                        put("executorShell", client?.observedEnvironment?.value?.shell?.path ?: org.json.JSONObject.NULL)
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
