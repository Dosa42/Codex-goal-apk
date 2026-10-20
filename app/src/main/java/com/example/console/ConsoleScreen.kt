package com.example.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.TermuxLauncher
import com.example.voice.VoiceConsole

private val sections = listOf("Overview", "Team", "Assign", "Results", "Ledger", "Connections", "Events", "Voice", "Settings")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConsoleScreen(model: ConsoleViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startConsole(context)
        else model.report("Start Console", IllegalStateException("Termux RUN_COMMAND permission was not granted."))
    }
    LaunchedEffect(model, lifecycle, state.endpoint, state.initialized) {
        if (state.initialized) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observe() }
    }
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("console_root")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (context.checkSelfPermission(TermuxLauncher.PERMISSION_RUN_COMMAND) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        model.startConsole(context)
                    else permission.launch(TermuxLauncher.PERMISSION_RUN_COMMAND)
                },
                enabled = state.busy == null && state.initialized,
                modifier = Modifier.testTag("console_start")
            ) { Text("Start Console") }
            OutlinedButton(onClick = model::refresh, enabled = state.initialized, modifier = Modifier.testTag("console_refresh")) {
                Text("Refresh")
            }
        }
        Text(
            "${state.endpoint} · ${if (state.ready) "HTTP ready" else "Readiness unverified"}",
            Modifier.padding(horizontal = 12.dp).testTag("console_readiness"), style = MaterialTheme.typography.labelSmall
        )
        Text(state.bootstrap, Modifier.padding(horizontal = 12.dp).testTag("console_bootstrap"), style = MaterialTheme.typography.labelSmall)
        state.busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("console_busy"))
            Text(it, Modifier.padding(horizontal = 12.dp))
        }
        if (state.errors.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("console_errors")) {
                state.errors.forEach { (source, message) ->
                    Text("$source: $message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp) {
            sections.forEachIndexed { index, label ->
                Tab(
                    selected = section == index, onClick = { section = index },
                    text = { Text(label) }, modifier = Modifier.testTag("console_section_${label.lowercase()}")
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                0 -> Overview(state, model)
                1 -> Team(state, model)
                2 -> Assign(state, model)
                3 -> Results(state, model)
                4 -> Ledger(state)
                5 -> Connections(state, model)
                6 -> Events(state, model)
                7 -> VoiceConsole()
                8 -> ConsoleSettings(state, model)
            }
        }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun JsonDetails(title: String, value: JsonObject, initiallyOpen: Boolean = false) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide $title" else "Show $title") }
        TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(title, ConsoleJson.write(value)))
        }) { Text("Copy") }
    }
    if (open) SelectionContainer {
        Text(ConsoleJson.write(value), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun GoalButtons(thread: String, goal: JsonObject, enabled: Boolean, prefix: String, model: ConsoleViewModel) {
    Text("Goal: ${goal.text("status").ifBlank { "unavailable" }}")
    if (thread.isNotBlank()) {
        SelectionContainer { Text(thread, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.goal(thread, "paused") },
                enabled = enabled && goal.text("status") == "active", modifier = Modifier.testTag("${prefix}_pause")
            ) { Text("Pause goal") }
            OutlinedButton(
                onClick = { model.goal(thread, "active") },
                enabled = enabled && goal.text("status") == "paused", modifier = Modifier.testTag("${prefix}_resume")
            ) { Text("Resume goal") }
        }
    }
}

@Composable
private fun Overview(state: ConsoleState, model: ConsoleViewModel) {
    val snapshot = state.snapshot
    val ceo = snapshot.obj("ceo")
    LazyColumn(Modifier.fillMaxSize().testTag("console_overview"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel("CEO") {
                Text("Observed: ${snapshot.text("observedAt").ifBlank { "No observation yet" }}")
                Text(
                    if (state.snapshotStale || !state.ready) "Retained observation · stale / refresh unverified" else "Current observation",
                    modifier = Modifier.testTag("console_snapshot_freshness"),
                    color = if (state.snapshotStale || !state.ready) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Source: ${ceo.text("source").ifBlank { "unavailable" }} · live: ${ceo["live"] ?: false}")
                Text(ceo.obj("goal").text("objective"))
                GoalButtons(ceo.text("threadId"), ceo.obj("goal"), state.busy == null, "console_ceo", model)
                JsonDetails("CEO observation", ceo)
            }
        }
        item { Panel("Message the original CEO") { CeoMessageForm(state, model) } }
        item {
            Panel("Team and assignments") {
                Text("${snapshot.obj("team").objects("agents").size} reported specialists")
                Text("${state.nativeTasks.size} native task records · cursor ${state.cursor}")
                JsonDetails("Task counts", snapshot.obj("tasks").obj("counts"))
                Text("Runtime state, native goal status, delivery, acknowledgement and result are separate observations.")
            }
        }
        if ((snapshot["errors"] as? List<*>)?.isNotEmpty() == true) {
            item {
                Panel("Subsystem errors") {
                    SelectionContainer { Text(ConsoleJson.write(mapOf("errors" to snapshot["errors"])), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Panel("Observed snapshot") { JsonDetails("complete snapshot", snapshot) } }
        item { Panel("Last operator response") { state.actionResponse?.let { JsonDetails("response", it, true) } ?: Text("No operator response in this session.") } }
    }
}

@Composable
private fun CeoMessageForm(state: ConsoleState, model: ConsoleViewModel) {
    val draft = state.ceoDraft
    val submitted = draft["submitted"] == true
    var lookup by rememberSaveable(draft.text("messageId")) { mutableStateOf(draft.text("messageId")) }
    Text("Tool-mediated inbox · queue acceptance and original-thread receipt are separate.")
    OutlinedTextField(
        value = draft.text("messageId"), onValueChange = {}, readOnly = true, label = { Text("Generated message ID") },
        modifier = Modifier.fillMaxWidth().testTag("console_ceo_message_id")
    )
    OutlinedTextField(
        value = draft.text("text"), onValueChange = model::editCeoText, readOnly = submitted,
        label = { Text("Message") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("console_ceo_message_text")
    )
    Text("Target: ${draft.text("threadId").ifBlank { state.snapshot.obj("ceo").text("threadId") }}", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = model::sendCeo,
            enabled = state.busy == null && !submitted && draft.text("messageId").isNotBlank() &&
                draft.text("text").isNotBlank() && state.snapshot.obj("ceo").text("threadId").isNotBlank(),
            modifier = Modifier.testTag("console_ceo_send")
        ) { Text("Send message") }
        OutlinedButton(onClick = model::newCeoMessage, enabled = state.busy == null, modifier = Modifier.testTag("console_ceo_new")) { Text("New message") }
    }
    if (submitted) Text("Dispatch recorded. Reconcile this ID to check receipt; it will not be resubmitted automatically.")
    Field("Reconcile message ID", lookup, { lookup = it }, "console_ceo_lookup_id", true)
    OutlinedButton(
        onClick = { model.reconcileCeo(lookup.trim()) }, enabled = lookup.isNotBlank(),
        modifier = Modifier.testTag("console_ceo_reconcile")
    ) { Text("Reconcile receipt") }
    val status = state.ceoReceipt.text("status")
    Text(
        when (status) {
            "queued" -> "Queued · original CEO receipt not yet reported"
            "received" -> "Received by original CEO · execution is not established by this receipt"
            else -> "Receipt unverified"
        },
        modifier = Modifier.testTag("console_ceo_receipt")
    )
    if (state.ceoReceipt.isNotEmpty()) JsonDetails("inbox record", state.ceoReceipt)
}

@Composable
private fun Team(state: ConsoleState, model: ConsoleViewModel) {
    val agents = state.snapshot.obj("team").objects("agents")
    LazyColumn(Modifier.fillMaxSize().testTag("console_team"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (agents.isEmpty()) item { Text("No team observations yet. Check the shared errors and Refresh.") }
        items(agents, key = { it.text("role") }) { agent ->
            val role = agent.text("role")
            Panel(role) {
                Text("Runtime: ${agent.text("state")} · native alive: ${agent["native_alive"] ?: "unverified"}")
                Text("Source: ${agent.text("source").ifBlank { "unavailable" }} · cached: ${agent["cached"] ?: "unknown"}")
                Text("Active assignment: ${agent["active_task_id"] ?: "none reported"}")
                GoalButtons(agent.text("thread_id"), agent.obj("goal"), state.busy == null, "console_team_$role", model)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("up", "restart", "stop").forEach { action ->
                        OutlinedButton(
                            onClick = { model.runtime(role, action) }, enabled = state.busy == null,
                            modifier = Modifier.testTag("console_runtime_${role}_$action")
                        ) { Text("${action.replaceFirstChar { it.uppercase() }} runtime") }
                    }
                }
                JsonDetails("native state", agent)
            }
        }
    }
}

@Composable
private fun Assign(state: ConsoleState, model: ConsoleViewModel) {
    var role by rememberSaveable { mutableStateOf("coder") }
    var task by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("") }
    var acceptance by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("") }
    var dependencies by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().testTag("console_assignment_form"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Assign to a native specialist", style = MaterialTheme.typography.titleMedium)
            Text("Use a unique task ID. After an uncertain response, look up that same ID before another dispatch.")
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("console_role_chooser"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("planner", "coder", "debugger", "tracker", "browser").forEach { value ->
                    FilterChip(
                        selected = role == value, onClick = { role = value }, label = { Text(value) },
                        modifier = Modifier.testTag("console_role_$value")
                    )
                }
            }
        }
        item { Field("Task ID", task, { task = it }, "console_task_id", singleLine = true) }
        item { Field("Scope · one item per line", scope, { scope = it }, "console_scope") }
        item { Field("Acceptance", acceptance, { acceptance = it }, "console_acceptance") }
        item { Field("Instruction", instruction, { instruction = it }, "console_instruction") }
        item { Field("Dependencies · one task ID per line", dependencies, { dependencies = it }, "console_dependencies") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.assign(Assignment(role, task.trim(), lines(scope), acceptance.trim(), instruction.trim(), lines(dependencies))) },
                    enabled = state.busy == null && task.isNotBlank() && scope.isNotBlank() && acceptance.isNotBlank() && instruction.isNotBlank(),
                    modifier = Modifier.testTag("console_dispatch")
                ) { Text("Dispatch assignment") }
                OutlinedButton(onClick = { model.lookupTask(task.trim()) }, enabled = task.isNotBlank(), modifier = Modifier.testTag("console_task_lookup")) {
                    Text("Look up task ID")
                }
            }
        }
        item { state.actionResponse?.let { Panel("Assignment / lookup response") { JsonDetails("complete response", it, true) } } }
    }
}

private fun lines(text: String): List<String> = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

@Composable
private fun Field(label: String, value: String, changed: (String) -> Unit, tag: String, singleLine: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = changed, label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2, modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun Results(state: ConsoleState, model: ConsoleViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var operationId by rememberSaveable { mutableStateOf("") }
    val records = state.nativeTasks.filter { query.isBlank() || it.text("taskId").contains(query, true) }
    LazyColumn(Modifier.fillMaxSize().testTag("console_results"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Field("Find task ID", query, { query = it }, "console_results_task_id", true) }
        item {
            OutlinedButton(onClick = { model.lookupTask(query.trim()) }, enabled = query.isNotBlank(), modifier = Modifier.testTag("console_results_lookup")) {
                Text("Query exact native task")
            }
        }
        item {
            Field("Reconcile operation UUID", operationId, { operationId = it }, "console_operation_id", true)
            OutlinedButton(
                onClick = { model.reconcileOperation(operationId.trim()) }, enabled = operationId.isNotBlank(),
                modifier = Modifier.testTag("console_operation_reconcile")
            ) { Text("Look up operation receipt") }
        }
        item { state.actionResponse?.let { response ->
            Panel("Latest operator / lookup response") {
                if (response.obj("action").isNotEmpty()) {
                    Text("Operation ${response.obj("action").text("actionId")} · phase ${response.obj("action").text("phase")}")
                    Text("Outcome unknown: ${response["outcomeUnknown"] ?: "not reported"}")
                }
                JsonDetails("response", response, true)
            }
        } }
        if (records.isEmpty()) item { Text("No matching native task records observed.") }
        items(records, key = { it.text("taskId") }) { task ->
            Panel(task.text("taskId")) {
                Text("${task.text("role")} · ${task.text("state")}", fontWeight = FontWeight.Bold)
                Text("Delivery attempts: ${task["deliveryAttempts"] ?: 0}")
                Text("Delivered: ${task["deliveredAt"] ?: "not observed"}")
                Text("Acknowledged: ${task["ackedAt"] ?: "not observed"}")
                Text("Finished: ${task["finishedAt"] ?: "not observed"}")
                val result = task.obj("result")
                if (result.isNotEmpty()) {
                    Text(result.text("summary"))
                    SelectionContainer { Text(ConsoleJson.write(mapOf("evidence" to result["evidence"], "changes" to result["changes"], "remaining" to result["remaining"]))) }
                    JsonDetails("actual result", result)
                }
                JsonDetails("native task record", task)
            }
        }
        item { Text("Local operator actions", style = MaterialTheme.typography.titleMedium) }
        items(state.actions, key = { it.number("id") ?: it.hashCode().toLong() }) { action ->
            Panel(action.text("label")) {
                Text("Operation UUID: ${action.text("operationId").ifBlank { "legacy record without UUID" }}")
                OutlinedButton(
                    onClick = { operationId = action.text("operationId"); model.reconcileOperation(operationId) },
                    enabled = action.text("operationId").isNotBlank(),
                    modifier = Modifier.testTag("console_action_reconcile_${action["id"]}")
                ) { Text("Reconcile saved operation") }
                JsonDetails("action ${action["id"]}", action, true)
            }
        }
        item { OutlinedButton(onClick = model::olderActions, modifier = Modifier.testTag("console_actions_older")) { Text("Load older operator actions") } }
    }
}

@Composable
private fun Ledger(state: ConsoleState) {
    val tasks = state.ledger.objects("tasks")
    LazyColumn(Modifier.fillMaxSize().testTag("console_ledger"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Original goal ledger", style = MaterialTheme.typography.titleMedium) }
        if (tasks.isEmpty()) item { Text("No ledger task records reported. Native results are shown in Results.") }
        items(tasks) { task ->
            Panel(task.text("taskId").ifBlank { task.text("id").ifBlank { "Ledger record" } }) {
                Text("State: ${task.text("state").ifBlank { task.text("status") }} · revision ${task["revision"] ?: "unavailable"}")
                JsonDetails("ledger task", task, true)
            }
        }
        item { Panel("Complete ledger response") { JsonDetails("ledger", state.ledger) } }
    }
}

@Composable
private fun Connections(state: ConsoleState, model: ConsoleViewModel) {
    var id by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("http") }
    var url by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf("{}") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var pairLocalKey by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("console_connections"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Configured connections and live discovery", style = MaterialTheme.typography.titleMedium) }
        items(state.connections.objects("backends"), key = { it.text("id") }) { backend ->
            val backendId = backend.text("id")
            Panel(backend.text("label").ifBlank { backendId }) {
                Text("$backendId · ${backend.text("kind")} · enabled: ${backend["enabled"] ?: true}")
                Text(backend.text("baseUrl"))
                Text("Reachable: ${backend["available"] ?: "not established"} · ${backend.text("status")}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { model.testConnection(backendId) }, enabled = state.busy == null,
                        modifier = Modifier.testTag("console_connection_test_$backendId")
                    ) { Text("Test") }
                    OutlinedButton(
                        onClick = { model.removeConnection(backendId) }, enabled = state.busy == null,
                        modifier = Modifier.testTag("console_connection_remove_$backendId")
                    ) { Text("Remove connection") }
                }
                JsonDetails("connection observation", backend)
            }
        }
        item { Panel("Discovery response") { JsonDetails("discovery", state.connections) } }
        item { Text("Add connection", style = MaterialTheme.typography.titleMedium) }
        item { Field("Connection ID", id, { id = it }, "console_connection_id", true) }
        item { Field("Label", label, { label = it }, "console_connection_label", true) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("console_connection_kind"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("http", "codex-proxy", "codex-app-server", "mcp-http", "mcp-stdio").forEach { value ->
                    FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(value) }, modifier = Modifier.testTag("console_connection_kind_$value"))
                }
            }
        }
        item { Field("Base URL", url, { url = it }, "console_connection_url", true) }
        item {
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key · optional") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("console_connection_api_key")
            )
        }
        item { Field("Options JSON · MCP command, args, env, cwd, headers, timeout", options, { options = it }, "console_connection_options") }
        item {
            Row { Checkbox(checked = enabled, onCheckedChange = { enabled = it }, modifier = Modifier.testTag("console_connection_enabled")); Text("Enabled") }
            Row { Checkbox(checked = pairLocalKey, onCheckedChange = { pairLocalKey = it }, modifier = Modifier.testTag("console_connection_pair_local")); Text("Pair local proxy key") }
        }
        item {
            Button(
                onClick = {
                    try {
                        val value = linkedMapOf<String, Any?>("id" to id.trim(), "kind" to kind, "baseUrl" to url.trim(),
                            "label" to label.trim(), "enabled" to enabled, "pairLocalKey" to pairLocalKey,
                            "options" to ConsoleJson.read(options))
                        if (apiKey.isNotBlank()) value["apiKey"] = apiKey
                        model.addConnection(value)
                    } catch (e: Exception) { model.report("connection form", e) }
                },
                enabled = state.busy == null && id.isNotBlank() && (kind == "mcp-stdio" || url.isNotBlank()),
                modifier = Modifier.testTag("console_connection_add")
            ) { Text("Add connection") }
        }
        item { state.actionResponse?.let { Panel("Connection action response") { JsonDetails("upstream response", it, true) } } }
    }
}

@Composable
private fun Events(state: ConsoleState, model: ConsoleViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("console_events"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Durable cursor ${state.cursor}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("console_event_cursor"))
            Text(state.archiveNotice, modifier = Modifier.testTag("console_event_archive_notice"))
            Text("Viewing ${state.eventArchive.ifBlank { "unverified archive" }} · live store ${state.instanceId}")
            Text("Received pages stay in the local journal. Older pages are available after recreation.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.archives.forEach { instance ->
                    FilterChip(
                        selected = instance == state.eventArchive, onClick = { model.selectArchive(instance) },
                        label = { Text(if (instance == state.instanceId) "Live archive" else instance) },
                        modifier = Modifier.testTag("console_event_archive_$instance")
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::olderEvents, enabled = state.canLoadOlder, modifier = Modifier.testTag("console_events_older")) { Text("Older local page") }
                OutlinedButton(onClick = model::nextEventPage, modifier = Modifier.testTag("console_events_next")) { Text(if (state.hasMore) "Next service page" else "Check new events") }
            }
        }
        items(state.events, key = { it.number("seq") ?: it.hashCode().toLong() }) { event ->
            Panel("#${event.number("seq")} ${event.text("type")}") {
                Text(event.text("timestamp"))
                JsonDetails("event", event, true)
            }
        }
    }
}

@Composable
private fun ConsoleSettings(state: ConsoleState, model: ConsoleViewModel) {
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    LazyColumn(Modifier.fillMaxSize().testTag("console_settings"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Console connection", style = MaterialTheme.typography.titleMedium) }
        item { Field("Goal Console URL", endpoint, { endpoint = it }, "console_endpoint", true) }
        item {
            Button(onClick = { model.saveEndpoint(endpoint) }, enabled = state.busy == null, modifier = Modifier.testTag("console_endpoint_save")) {
                Text("Save console URL")
            }
        }
        item { Text("Default: ${ConsoleClient.DEFAULT_ENDPOINT}. This setting is saved independently from the executor WebSocket endpoint.") }
        item { Text("Start Console dispatch: ~/.local/bin/goal-console ensure. HTTP readiness is checked after dispatch. Status and events refresh while this screen is active.") }
        item { Panel("Voice observation") { JsonDetails("voice", state.snapshot.obj("voice")) } }
    }
}
