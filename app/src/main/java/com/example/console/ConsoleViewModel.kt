package com.example.console

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CodexGoalApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ConsoleState(
    val endpoint: String = ConsoleClient.DEFAULT_ENDPOINT,
    val initialized: Boolean = false,
    val ready: Boolean = false,
    val bootstrap: String = "Not dispatched",
    val snapshot: JsonObject = emptyMap(),
    val snapshotStale: Boolean = true,
    val nativeTasks: List<JsonObject> = emptyList(),
    val ledger: JsonObject = emptyMap(),
    val connections: JsonObject = emptyMap(),
    val events: List<JsonObject> = emptyList(),
    val cursor: Long = 0,
    val instanceId: String = "",
    val eventArchive: String = "",
    val archives: List<String> = emptyList(),
    val archiveNotice: String = "Waiting for the console store identity",
    val hasMore: Boolean = false,
    val canLoadOlder: Boolean = false,
    val actions: List<JsonObject> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val busy: String? = null,
    val actionResponse: JsonObject? = null,
    val ceoDraft: JsonObject = emptyMap(),
    val ceoReceipt: JsonObject = emptyMap(),
    val infrastructure: InfrastructureObservation = InfrastructureObservation(),
    val infrastructureDetails: JsonObject = emptyMap()
)

internal suspend fun readConsoleEndpointArchive(storage: ConsoleStorage, endpoint: String): JsonObject =
    withContext(Dispatchers.IO) {
        val instance = storage.lastInstance(endpoint)
        val key = if (instance.isBlank()) endpoint else ConsoleStorage.archiveKey(endpoint, instance)
        mapOf(
            "instance" to instance, "archives" to storage.archives(endpoint),
            "cursor" to storage.cursor(key), "events" to storage.events(key), "actions" to storage.actions(endpoint)
        )
    }

class ConsoleViewModel @JvmOverloads constructor(
    application: Application,
    private val readEndpointArchive: suspend (ConsoleStorage, String) -> JsonObject = ::readConsoleEndpointArchive
) : AndroidViewModel(application) {
    private val storage = ConsoleStorage(application)
    private val mutable = MutableStateFlow(ConsoleState())
    val state = mutable.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private var fetchingEvents = false
    private var infrastructureJob: Job? = null
    private var infrastructureJobUrl: String? = null
    private var infrastructureGeneration = 0L
    private var endpointLoadGeneration = 0L
    private var pendingEndpointLoad: Long? = null
    private val archiveSelection = ArchiveSelection()

    init {
        viewModelScope.launch {
            try {
                val infrastructure = withContext(Dispatchers.IO) { storage.infrastructure() }
                mutable.update { it.copy(infrastructure = infrastructure) }
                val endpoint = withContext(Dispatchers.IO) { storage.endpoint }
                loadEndpoint(endpoint)
                val draft = withContext(Dispatchers.IO) { storage.ceoDraft() }
                mutable.update { it.copy(ceoDraft = draft) }
            } catch (e: Exception) {
                report("storage", e)
            } finally {
                mutable.update { it.copy(initialized = true) }
                initialized.complete(Unit)
            }
        }
    }

    private suspend fun loadEndpoint(endpoint: String, isCurrent: () -> Boolean = { true }) {
        val normalized = ConsoleClient.normalizeEndpoint(endpoint)
        val generation = ++endpointLoadGeneration
        pendingEndpointLoad = generation
        val token = archiveSelection.begin(normalized, "loading")
        try {
            // Publish endpoint and its retained archive together, after IO; never strand initialized=false.
            val local = readEndpointArchive(storage, normalized)
            // A newer discovery may keep the visible endpoint and therefore need no replacement load.
            if (generation != endpointLoadGeneration || !archiveSelection.matches(token) || !isCurrent()) return
            val instance = local.text("instance")
            archiveSelection.begin(normalized, instance)
            mutable.update { previous -> ConsoleState(
                endpoint = normalized, initialized = true, cursor = local.number("cursor") ?: 0,
                events = local.objects("events"), actions = local.objects("actions"),
                eventArchive = instance, archives = (local["archives"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                canLoadOlder = local.objects("events").isNotEmpty(), ceoDraft = previous.ceoDraft,
                infrastructure = previous.infrastructure, infrastructureDetails = previous.infrastructureDetails,
                archiveNotice = if (instance.isBlank()) "No saved event store; waiting for live identity"
                    else "Retained store $instance · live identity unverified"
            ) }
        } finally { if (pendingEndpointLoad == generation) pendingEndpointLoad = null }
    }

    fun report(label: String, error: Throwable) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(errors = it.errors + (label to (error.message ?: error.toString()))) }
    }

    private fun clearError(label: String) { mutable.update { it.copy(errors = it.errors - label) } }
    private fun upstreamIssues(label: String, response: JsonObject) {
        if ((response["errors"] as? List<*>)?.isNotEmpty() == true)
            mutable.update { it.copy(errors = it.errors + ("$label subsystems" to ConsoleJson.write(mapOf("errors" to response["errors"])))) }
        else clearError("$label subsystems")
    }

    private suspend fun identifyStore(endpoint: String, instance: String) {
        require(instance.isNotBlank()) { "Health response has no instanceId; event cursor cannot be scoped safely" }
        if (pendingEndpointLoad != null) return
        if (endpoint != state.value.endpoint || instance == state.value.instanceId) return
        val previous = state.value.instanceId.ifBlank { state.value.eventArchive }
        val token = archiveSelection.begin(endpoint, instance)
        val key = ConsoleStorage.archiveKey(endpoint, instance)
        val local = withContext(Dispatchers.IO) {
            val archives = storage.registerArchive(endpoint, instance)
            Triple(storage.cursor(key), storage.events(key), archives)
        }
        if (endpoint == state.value.endpoint && archiveSelection.matches(token)) mutable.update { it.copy(
            instanceId = instance, eventArchive = instance, archives = local.third,
            events = local.second, cursor = local.first, canLoadOlder = local.second.isNotEmpty(), hasMore = false,
            snapshotStale = it.snapshotStale || (previous.isNotBlank() && previous != instance),
            archiveNotice = if (previous.isBlank()) "Event store $instance · saved cursor ${local.first}"
                else "Store changed $previous → $instance. Following new store from cursor ${local.first}; previous history is retained."
        ) }
    }

    /** Called only from repeatOnLifecycle; leaving Console cancels these reads and long polls. */
    suspend fun observe() {
        initialized.await()
        coroutineScope {
            launch {
                while (isActive) {
                    refreshInfrastructure()
                    delay(60_000)
                }
            }
            launch {
                while (isActive) {
                    refreshBundle()
                    delay(8_000)
                }
            }
            launch {
                while (isActive) {
                    fetchEvents(wait = 15)
                    delay(if (state.value.hasMore) 50 else 1_000)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { initialized.await(); refreshBundle() }
        refreshInfrastructureNow()
    }

    fun refreshInfrastructureNow() {
        viewModelScope.launch { refreshInfrastructureAndAwait() }
    }

    internal suspend fun refreshInfrastructureAndAwait() {
        initialized.await()
        refreshInfrastructure()
    }

    private suspend fun refreshInfrastructure(replace: Boolean = false) {
        val url = state.value.infrastructure.url
        val active = infrastructureJob?.takeIf { it.isActive && infrastructureJobUrl == url && !replace }
        val job = active ?: run {
            val generation = ++infrastructureGeneration
            infrastructureJob?.cancel()
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                fetchInfrastructure(url, generation)
            }.also { infrastructureJob = it; infrastructureJobUrl = url; it.start() }
        }
        try { job.join() }
        finally { if (!currentCoroutineContext().isActive) job.cancel() }
    }

    private fun currentInfrastructure(url: String, generation: Long) =
        generation == infrastructureGeneration && url == state.value.infrastructure.url

    private suspend fun fetchInfrastructure(url: String, generation: Long) {
        mutable.update { it.copy(infrastructure = it.infrastructure.copy(
            loading = true, attemptedAtEpochMs = System.currentTimeMillis())) }
        try {
            val descriptor = InfrastructureClient().discover(url)
            // Binding validation is part of descriptor acceptance, before saving a fresh observation.
            val consoleDefault = InfrastructureDescriptor.defaultBinding(descriptor, "goalConsole")
            val execDefault = InfrastructureDescriptor.defaultBinding(descriptor, "execServer")
            if (!currentInfrastructure(url, generation)) return
            val observation = InfrastructureObservation(
                url, descriptor, System.currentTimeMillis(), state.value.infrastructure.attemptedAtEpochMs,
                stale = false, loading = true
            )
            withContext(Dispatchers.IO) { storage.saveInfrastructure(observation) }
            if (!currentInfrastructure(url, generation)) return
            mutable.update { it.copy(infrastructure = observation) }
            clearError("infrastructure")
            // Adoption survives cancellation of the lifecycle observer that the new endpoint replaces.
            viewModelScope.launch {
                try {
                    if (!currentInfrastructure(url, generation)) return@launch
                    if (state.value.busy == null) {
                        val endpoint = withContext(Dispatchers.IO) {
                            if (currentInfrastructure(url, generation)) consoleDefault?.let { storage.applyConsoleDefault(it) }
                            storage.endpoint
                        }
                        if (currentInfrastructure(url, generation) && endpoint != state.value.endpoint)
                            loadEndpoint(endpoint) { currentInfrastructure(url, generation) }
                    }
                    execDefault?.takeIf { currentInfrastructure(url, generation) }?.let {
                        (getApplication<Application>() as? CodexGoalApplication)?.repository
                            ?.applyInfrastructureDefaultEndpoint(it)
                    }
                    if (currentInfrastructure(url, generation)) clearError("infrastructure bindings")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (currentInfrastructure(url, generation)) report("infrastructure bindings", e)
                }
            }.join()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (currentInfrastructure(url, generation)) {
                mutable.update { it.copy(infrastructure = it.infrastructure.copy(
                    stale = true, error = e.message ?: e.toString())) }
                report("infrastructure", e)
            }
        } finally {
            if (currentInfrastructure(url, generation)) {
                infrastructureJob = null
                infrastructureJobUrl = null
                mutable.update { it.copy(infrastructure = it.infrastructure.copy(loading = false)) }
            }
        }
    }

    fun saveInfrastructureUrl(value: String): Job =
        viewModelScope.launch {
            try {
                val url = InfrastructureClient.discoveryUrl(value)
                withContext(Dispatchers.IO) { storage.infrastructureUrl = url }
                mutable.update { it.copy(infrastructure = InfrastructureObservation(url)) }
                clearError("infrastructure settings")
                refreshInfrastructure(replace = true)
            } catch (e: Exception) { report("infrastructure settings", e) }
        }

    fun inspectInfrastructureLink(url: String) {
        viewModelScope.launch {
            mutable.update { it.copy(infrastructureDetails = mapOf("url" to url, "loading" to true)) }
            try {
                val response = InfrastructureClient().readJson(url)
                mutable.update { it.copy(infrastructureDetails = mapOf(
                    "url" to url, "observedAtEpochMs" to System.currentTimeMillis(), "response" to response)) }
                clearError("infrastructure link")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(infrastructureDetails = mapOf(
                    "url" to url, "observedAtEpochMs" to System.currentTimeMillis(),
                    "error" to (e.message ?: e.toString()),
                    "response" to ((e as? ConsoleHttpException)?.payload ?: emptyMap<String, Any?>()))) }
                report("infrastructure link", e)
            }
        }
    }

    private suspend fun observed(label: String, get: suspend (ConsoleClient) -> Unit) {
        val endpoint = state.value.endpoint
        try {
            get(ConsoleClient(endpoint))
            if (endpoint == state.value.endpoint) {
                clearError(label)
                if (label == "health" || label == "console") persistSnapshot()
            }
        } catch (e: Exception) {
            if (endpoint == state.value.endpoint) {
                if (e is CancellationException) throw e
                if (label == "health") mutable.update { it.copy(ready = false, snapshotStale = true) }
                if (label == "console") mutable.update { it.copy(snapshotStale = true) }
                report(label, e)
                if (label == "health" || label == "console") persistSnapshot()
            }
            else if (e is CancellationException) throw e
        }
    }

    private suspend fun persistSnapshot() {
        try {
            withContext(Dispatchers.IO) {
                val current = state.value
                storage.saveSnapshot(current.endpoint, current.snapshot, current.snapshotStale || !current.ready, current.errors)
            }
        } catch (e: Exception) { report("snapshot file", e) }
    }

    private suspend fun refreshBundle() = coroutineScope {
        launch { observed("health") { client ->
            val health = client.health()
            identifyStore(client.endpoint, health.text("instanceId"))
            if (client.endpoint == state.value.endpoint) {
                mutable.update { it.copy(ready = true) }
                persistSnapshot()
            }
        } }
        launch { observed("console") { client ->
            val response = client.console()
            if (client.endpoint == state.value.endpoint) {
                upstreamIssues("console", response)
                mutable.update { it.copy(snapshot = response, snapshotStale = false) }
                persistSnapshot()
            }
        } }
        launch { observed("native tasks") { client ->
            val response = client.nativeTasks()
            if (client.endpoint == state.value.endpoint) {
                upstreamIssues("native tasks", response)
                mutable.update { it.copy(nativeTasks = response.objects("tasks")) }
            }
        } }
        launch { observed("ledger") { client ->
            val response = client.ledger()
            if (client.endpoint == state.value.endpoint) {
                upstreamIssues("ledger", response)
                mutable.update { it.copy(ledger = response) }
            }
        } }
        launch { observed("connections") { client ->
            val response = client.connections()
            if (client.endpoint == state.value.endpoint) {
                upstreamIssues("connections", response)
                mutable.update { it.copy(connections = response) }
            }
        } }
    }

    private suspend fun fetchEvents(wait: Int) {
        if (fetchingEvents) return
        fetchingEvents = true
        val endpoint = state.value.endpoint
        val instance = state.value.instanceId
        try {
            if (instance.isBlank() || state.value.eventArchive != instance) return
            val key = ConsoleStorage.archiveKey(endpoint, instance)
            val cursor = withContext(Dispatchers.IO) { storage.cursor(key) }
            if (cursor > state.value.cursor && endpoint == state.value.endpoint && instance == state.value.eventArchive) {
                val retained = withContext(Dispatchers.IO) { storage.events(key) }
                mutable.update { it.copy(
                    cursor = cursor,
                    events = (it.events + retained).distinctBy { event -> event.number("seq") }.sortedBy { event -> event.number("seq") }
                ) }
            }
            val page = ConsoleClient(endpoint).events(cursor, wait)
            // Check identity after the long poll before committing a cursor. A replaced
            // store may have restarted its sequence while the request was outstanding.
            val health = ConsoleClient(endpoint).health()
            if (health.text("instanceId") != instance) {
                identifyStore(endpoint, health.text("instanceId"))
                return
            }
            withContext(Dispatchers.IO) { storage.append(key, page) }
            if (endpoint == state.value.endpoint && state.value.eventArchive == instance) {
                val retained = (state.value.events + page.events).distinctBy { it.number("seq") }.sortedBy { it.number("seq") }
                mutable.update { it.copy(events = retained, cursor = page.cursor, hasMore = page.hasMore) }
                clearError("events")
                upstreamIssues("events", mapOf("errors" to page.errors))
            }
        } catch (e: Exception) {
            if (endpoint == state.value.endpoint) report("events", e)
            else if (e is CancellationException) throw e
            delay(5_000)
        } finally { fetchingEvents = false }
    }

    fun nextEventPage() { viewModelScope.launch { fetchEvents(0) } }
    fun olderEvents() {
        val endpoint = state.value.endpoint
        val archive = state.value.eventArchive
        val token = archiveSelection.current()
        val before = state.value.events.firstOrNull()?.number("seq")
        viewModelScope.launch {
            try {
                val key = ConsoleStorage.archiveKey(endpoint, archive)
                val page = withContext(Dispatchers.IO) { storage.events(key, before) }
                if (endpoint == state.value.endpoint && archive == state.value.eventArchive && archiveSelection.matches(token)) mutable.update {
                    it.copy(events = (page + it.events).distinctBy { event -> event.number("seq") }, canLoadOlder = page.isNotEmpty())
                }
            } catch (e: Exception) { report("local events", e) }
        }
    }

    fun selectArchive(instance: String) {
        val endpoint = state.value.endpoint
        val token = archiveSelection.begin(endpoint, instance)
        viewModelScope.launch {
            try {
                val key = ConsoleStorage.archiveKey(endpoint, instance)
                val local = withContext(Dispatchers.IO) { storage.cursor(key) to storage.events(key) }
                if (endpoint == state.value.endpoint && archiveSelection.matches(token)) mutable.update { it.copy(
                    eventArchive = instance, cursor = local.first, events = local.second,
                    canLoadOlder = local.second.isNotEmpty(), hasMore = false
                ) }
            } catch (e: Exception) { report("archive", e) }
        }
    }

    fun olderActions() {
        viewModelScope.launch {
            val endpoint = state.value.endpoint
            try {
                val page = withContext(Dispatchers.IO) { storage.actions(endpoint, state.value.actions.lastOrNull()?.number("id")) }
                if (endpoint == state.value.endpoint) mutable.update {
                    it.copy(actions = (it.actions + page).distinctBy { item -> item.number("id") })
                }
            } catch (e: Exception) { report("action history", e) }
        }
    }

    fun saveEndpoint(value: String) {
        if (state.value.busy != null) return
        viewModelScope.launch {
            try {
                val normalized = ConsoleClient.normalizeEndpoint(value)
                withContext(Dispatchers.IO) { storage.endpoint = normalized }
                loadEndpoint(normalized)
            } catch (e: Exception) { report("settings", e) }
        }
    }

    fun startConsole(context: Context) {
        if (state.value.busy != null) return
        mutable.update { it.copy(busy = "Start Console", bootstrap = "Dispatching Termux command…") }
        viewModelScope.launch {
            try {
                ConsoleBootstrap.dispatch(context.applicationContext)
                mutable.update { it.copy(bootstrap = "RUN_COMMAND dispatched. Waiting for HTTP readiness…") }
                clearError("Start Console")
                val client = ConsoleClient(state.value.endpoint)
                var last: Exception? = null
                repeat(12) {
                    try {
                        client.health()
                        mutable.update { it.copy(ready = true, bootstrap = "Dispatched; goal-console/1 HTTP readiness verified.") }
                        clearError("health")
                        refreshBundle()
                        return@launch
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { last = e; delay(1_000) }
                }
                throw java.io.IOException("Command dispatched, readiness not verified: ${last?.message}", last)
            } catch (e: Exception) {
                mutable.update { it.copy(bootstrap = "Console readiness not verified. ${e.message}") }
                report("Start Console", e)
            } finally { mutable.update { it.copy(busy = null) } }
        }
    }

    /** Intent is durable before HTTP dispatch. Recreation loads outcomes; it never reissues them. */
    private fun action(label: String, request: JsonObject, operation: suspend (ConsoleClient, String) -> JsonObject) {
        if (state.value.busy != null) return
        val endpoint = state.value.endpoint
        val operationId = UUID.randomUUID().toString()
        mutable.update { it.copy(busy = label, actionResponse = null) }
        viewModelScope.launch {
            var id: Long? = null
            try {
                val actionId = withContext(Dispatchers.IO) { storage.beginAction(endpoint, label, request, operationId) }
                id = actionId
                val response = operation(ConsoleClient(endpoint), operationId)
                withContext(Dispatchers.IO) { storage.finishAction(actionId, ConsoleJson.write(response)) }
                mutable.update { it.copy(actionResponse = response) }
                clearError(label)
            } catch (e: Exception) {
                val detail = when (e) {
                    is ConsoleHttpException -> ConsoleJson.write(e.payload)
                    else -> "Outcome unknown. Reconcile the same immutable task ID or inspect current state before another action. ${e.message}"
                }
                id?.let { actionId -> withContext(Dispatchers.IO) { storage.finishAction(actionId, detail) } }
                report(label, e)
            } finally {
                try {
                    val actions = withContext(Dispatchers.IO) { storage.actions(endpoint) }
                    mutable.update { it.copy(actions = actions) }
                } catch (e: Exception) { report("action history", e) }
                mutable.update { it.copy(busy = null) }
            }
            refreshBundle()
        }
    }

    fun assign(value: Assignment) {
        try { action("Assign ${value.taskId}", value.payload()) { client, id -> client.assign(value, id) } }
        catch (e: Exception) { report("assignment", e) }
    }
    fun goal(thread: String, status: String) =
        action("Goal $status", mapOf("threadId" to thread, "status" to status)) { client, id -> client.goal(thread, status, id) }
    fun runtime(role: String, action: String) =
        action("Runtime $action: $role", mapOf("role" to role, "action" to action)) { client, id -> client.runtime(role, action, id) }
    fun addConnection(value: JsonObject) = action("Add connection", value) { client, id -> client.addConnection(value, id) }
    fun testConnection(id: String) = action("Test connection: $id", mapOf("id" to id)) { client, operationId -> client.testConnection(id, operationId) }
    fun removeConnection(id: String) = action("Remove connection: $id", mapOf("id" to id)) { client, operationId -> client.removeConnection(id, operationId) }
    fun lookupTask(id: String) {
        viewModelScope.launch {
            observed("task lookup") { client ->
                val value = client.nativeTasks(id)
                mutable.update { it.copy(actionResponse = value) }
            }
        }
    }

    fun editCeoText(text: String) {
        if (state.value.ceoDraft["submitted"] == true) return
        val draft = state.value.ceoDraft + ("text" to text)
        mutable.update { it.copy(ceoDraft = draft) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { storage.saveCeoDraft(state.value.ceoDraft) } }
            catch (e: Exception) { report("CEO draft", e) }
        }
    }

    fun newCeoMessage() {
        if (state.value.busy != null) return
        val draft = mapOf("messageId" to "apk-${UUID.randomUUID()}", "text" to "", "submitted" to false)
        mutable.update { it.copy(ceoDraft = draft, ceoReceipt = emptyMap()) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { storage.saveCeoDraft(state.value.ceoDraft) } }
            catch (e: Exception) { report("CEO draft", e) }
        }
    }

    fun sendCeo() {
        val draft = state.value.ceoDraft
        val thread = state.value.snapshot.obj("ceo").text("threadId")
        if (draft["submitted"] == true || thread.isBlank() || draft.text("text").isBlank() || state.value.busy != null) return
        val submitted = draft + mapOf("submitted" to true, "threadId" to thread)
        mutable.update { it.copy(ceoDraft = submitted) }
        val payload = mapOf("messageId" to draft.text("messageId"), "threadId" to thread, "text" to draft.text("text"), "source" to "apk.text")
        action("Send CEO message ${draft.text("messageId")}", payload) { client, operationId ->
            withContext(Dispatchers.IO) { storage.saveCeoDraft(submitted) }
            val response = client.sendCeo(draft.text("messageId"), thread, draft.text("text"), operationId)
            mutable.update { it.copy(ceoReceipt = response.obj("message")) }
            response
        }
    }

    fun reconcileCeo(messageId: String) {
        viewModelScope.launch {
            observed("CEO receipt") { client ->
                val response = client.ceoReceipt(messageId)
                mutable.update { it.copy(ceoReceipt = response.obj("message"), actionResponse = response) }
            }
        }
    }

    fun reconcileOperation(operationId: String) {
        viewModelScope.launch {
            observed("operation receipt") { client ->
                val response = client.actionReceipt(operationId)
                val actions = withContext(Dispatchers.IO) {
                    storage.recordReceipt(client.endpoint, operationId, response)
                    storage.actions(client.endpoint)
                }
                if (client.endpoint == state.value.endpoint)
                    mutable.update { it.copy(actionResponse = response, actions = actions) }
            }
        }
    }
}
