package com.example.ui

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.OutputChunkEntity
import com.example.data.db.ProcessEntity
import com.example.data.db.ProtocolEventEntity
import com.example.data.model.ConnectionState
import com.example.data.model.EnvironmentInfo
import com.example.data.model.ProcessStatus
import com.example.ui.terminal.TerminalView
import com.example.ui.viewmodel.AcceptanceCheckStep
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.StepStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestTermuxPermission: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val endpoint by viewModel.endpoint.collectAsState()
    val observedEnv by viewModel.observedEnvironment.collectAsState()
    val healthStatus by viewModel.healthStatus.collectAsState()
    val termuxMsg by viewModel.termuxLaunchMessage.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var endpointInput by remember(endpoint) { mutableStateOf(endpoint) }
    var showEnvCard by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Codex Goal", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            ConnectionBadge(connectionState)
                        }
                        Text(
                            text = "Termux Exec-Server Native Client",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showEnvCard = !showEnvCard },
                        modifier = Modifier.testTag("toggle_env_btn")
                    ) {
                        Icon(
                            if (showEnvCard) Icons.Default.Info else Icons.Default.Info,
                            contentDescription = "Server Environment"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Connection & Endpoint Control Bar
            ConnectionControlBar(
                endpoint = endpointInput,
                onEndpointChange = { endpointInput = it },
                onSaveAndConnect = {
                    viewModel.updateEndpoint(endpointInput)
                    viewModel.connect(context)
                },
                onReconnect = { viewModel.reconnect(context) },
                onFreshSession = {
                    viewModel.updateEndpoint(endpointInput)
                    viewModel.connectFreshSession(context)
                },
                onStop = { viewModel.stop(context) },
                onCheckHealth = { viewModel.checkHealth() },
                healthStatus = healthStatus,
                connectionState = connectionState
            )

            // Session Lost Action Banner
            if (connectionState == ConnectionState.SESSION_LOST) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Session Lost / Expired",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 13.sp
                            )
                            Text(
                                "The server expired or rejected the previous session ID. Old transcripts are preserved; past commands are not replayed.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.connectFreshSession(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("New Session", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Collapsible Observed Environment Info
            AnimatedVisibility(visible = showEnvCard) {
                EnvironmentCard(env = observedEnv, onClose = { showEnvCard = false })
            }

            // Termux Launch Feedback Dialog / Banner
            termuxMsg?.let { msg ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissTermuxMessage() },
                    title = { Text("Termux Executor Launch") },
                    text = { Text(msg) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissTermuxMessage() }) {
                            Text("OK")
                        }
                    }
                )
            }

            // Navigation Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Processes") },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    modifier = Modifier.testTag("tab_processes")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Run Command") },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    modifier = Modifier.testTag("tab_run_command")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Protocol Log") },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    modifier = Modifier.testTag("tab_protocol_log")
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Diagnostics") },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    modifier = Modifier.testTag("tab_diagnostics")
                )
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> ProcessesTab(viewModel)
                    1 -> RunCommandTab(viewModel)
                    2 -> ProtocolLogTab(viewModel)
                    3 -> DiagnosticsTab(viewModel, onRequestTermuxPermission)
                }
            }
        }
    }
}

@Composable
fun ConnectionBadge(state: ConnectionState) {
    val (color, text) = when (state) {
        ConnectionState.INITIALIZED -> Color(0xFF2E7D32) to "INITIALIZED"
        ConnectionState.INITIALIZING -> Color(0xFFF57C00) to "INITIALIZING"
        ConnectionState.CONNECTING -> Color(0xFF1976D2) to "CONNECTING"
        ConnectionState.SESSION_LOST -> Color(0xFFD32F2F) to "SESSION LOST"
        ConnectionState.ERROR -> Color(0xFFC2185B) to "ERROR"
        ConnectionState.DISCONNECTED -> Color(0xFF616161) to "DISCONNECTED"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun ConnectionControlBar(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    onSaveAndConnect: () -> Unit,
    onReconnect: () -> Unit,
    onFreshSession: () -> Unit,
    onStop: () -> Unit,
    onCheckHealth: () -> Unit,
    healthStatus: String?,
    connectionState: ConnectionState
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("Server Endpoint") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("endpoint_input"),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSaveAndConnect,
                    enabled = connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR,
                    modifier = Modifier.testTag("connect_btn")
                ) {
                    Text("Connect")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onCheckHealth,
                        modifier = Modifier.testTag("probe_readyz_btn")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Probe /readyz", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = healthStatus ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (healthStatus?.contains("OK") == true) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    if (connectionState == ConnectionState.SESSION_LOST) {
                        Button(
                            onClick = onFreshSession,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("new_session_btn")
                        ) {
                            Text("New Session", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    FilledTonalButton(
                        onClick = onReconnect,
                        modifier = Modifier.testTag("reconnect_btn")
                    ) {
                        Text("Reconnect", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    FilledTonalButton(
                        onClick = onStop,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.testTag("stop_btn")
                    ) {
                        Text("Stop", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun EnvironmentCard(env: EnvironmentInfo?, onClose: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Observed Termux Environment", fontWeight = FontWeight.Bold)
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(6.dp))
            if (env != null) {
                Text("Working Dir: ${env.cwd ?: "unknown"}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("Home Dir: ${env.userHomeDir ?: "unknown"}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("Shell: ${env.shell?.path ?: env.shell?.name ?: "sh"}", fontSize = 12.sp)
                Text("Platform OS: ${env.platformOs ?: "android"}", fontSize = 12.sp)
                val caps = env.capabilities
                if (!caps.isNullOrEmpty()) {
                    Text("Capabilities: ${caps.keys.joinToString(", ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    "No environment info received yet. (Requires successful initialize handshake)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ProcessesTab(viewModel: MainViewModel) {
    val processes by viewModel.processes.collectAsState()
    val selectedPid by viewModel.selectedProcessId.collectAsState()
    val selectedProcess by viewModel.selectedProcess.collectAsState()
    val chunks by viewModel.selectedProcessChunks.collectAsState()

    var viewMode by remember { mutableStateOf("TERMINAL") } // "TERMINAL" or "RAW"
    var inputCommand by remember { mutableStateOf("") }

    if (processes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("No Active or Past Processes", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Spawn an interactive Termux bash shell or run commands.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.launchBashShell() },
                    modifier = Modifier.testTag("launch_bash_shell_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Launch Interactive Bash Shell")
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Process Selector Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Process:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                ScrollableTabRow(
                    selectedTabIndex = processes.indexOfFirst { it.processId == selectedPid }.coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                    edgePadding = 0.dp
                ) {
                    processes.forEach { proc ->
                        val isSelected = proc.processId == selectedPid
                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.selectProcess(proc.processId) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val statusColor = when (proc.status) {
                                        ProcessStatus.RUNNING.name -> Color(0xFF2E7D32)
                                        ProcessStatus.STARTING.name -> Color(0xFFF57C00)
                                        ProcessStatus.EXITED.name -> Color(0xFF616161)
                                        else -> Color(0xFFD32F2F)
                                    }
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = proc.title.take(18),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { viewModel.launchBashShell() },
                    modifier = Modifier.size(36.dp).testTag("new_shell_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Shell")
                }
            }

            // Current Process Header Bar
            selectedProcess?.let { proc ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "PID: ${proc.processId.take(8)}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Status: ${proc.status}", fontSize = 12.sp)
                                if (proc.exitCode != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("Exit: ${proc.exitCode}", fontSize = 12.sp, color = if (proc.exitCode == 0) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                                }
                            }
                            if (proc.deliveryUncertain) {
                                Text("Warning: Delivery uncertain (network drop during start)", color = Color(0xFFD32F2F), fontSize = 11.sp)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewMode = if (viewMode == "TERMINAL") "RAW" else "TERMINAL" }) {
                                Text(if (viewMode == "TERMINAL") "Raw Log" else "PTY View", fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            FilledTonalButton(
                                onClick = { viewModel.terminateProcess(proc.processId) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).testTag("terminate_btn")
                            ) {
                                Text("Kill", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Output View (PTY Terminal or Raw Stream Log)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (viewMode == "TERMINAL") {
                        TerminalView(
                            processId = proc.processId,
                            chunks = chunks,
                            onInput = { b64Input ->
                                viewModel.writeRawBase64(proc.processId, b64Input)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        RawStreamLogView(chunks)
                    }
                }

                // Input Bar for Stdin
                val lastWriteStatus by viewModel.lastWriteStatus.collectAsState()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputCommand,
                                onValueChange = { inputCommand = it },
                                placeholder = { Text("Send stdin input...", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("stdin_input_field"),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    if (inputCommand.isNotEmpty()) {
                                        viewModel.writeInput(proc.processId, inputCommand, enter = true)
                                        inputCommand = ""
                                    }
                                },
                                modifier = Modifier.testTag("stdin_send_btn")
                            ) {
                                Text("Send")
                            }
                        }

                        if (!lastWriteStatus.isNullOrEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Write Status: $lastWriteStatus",
                                fontSize = 11.sp,
                                color = if (lastWriteStatus!!.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // Quick Control Signals Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row {
                                FilledTonalButton(
                                    onClick = { viewModel.sendCtrlC(proc.processId) },
                                    modifier = Modifier.height(30.dp).testTag("ctrl_c_btn"),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Ctrl-C", fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = { viewModel.sendEof(proc.processId) },
                                    modifier = Modifier.height(30.dp).testTag("ctrl_d_btn"),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Ctrl-D (EOF)", fontSize = 11.sp)
                                }
                            }

                            Row {
                                OutlinedButton(
                                    onClick = { viewModel.writeInput(proc.processId, "\r", enter = false) },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Enter", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } ?: run {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a process above to view console.")
                }
            }
        }
    }
}

@Composable
fun RawStreamLogView(chunks: List<OutputChunkEntity>) {
    val listState = rememberLazyListState()

    LaunchedEffect(chunks.size) {
        if (chunks.isNotEmpty()) {
            listState.animateScrollToItem(chunks.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(8.dp)
    ) {
        items(chunks, key = { it.id }) { chunk ->
            val decodedText = remember(chunk.chunkBase64) {
                try {
                    String(Base64.decode(chunk.chunkBase64, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (e: Exception) {
                    "<Decoding error>"
                }
            }
            val color = if (chunk.stream == "stderr") Color(0xFFFF7B72) else Color(0xFFC9D1D9)
            Text(
                text = "[${chunk.stream} seq=${chunk.seq}] $decodedText",
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun RunCommandTab(viewModel: MainViewModel) {
    var command by remember { mutableStateOf("ls -la /data/data/com.termux/files/home") }
    var cwd by remember { mutableStateOf("file:///data/data/com.termux/files/home") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Run One-Shot Shell Command", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Executes commands under Termux executor via /system/bin/sh -c without PTY. Standard error is preserved separately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Command Line") },
            modifier = Modifier.fillMaxWidth().testTag("oneshot_cmd_input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = cwd,
            onValueChange = { cwd = it },
            label = { Text("Working Directory (CWD)") },
            modifier = Modifier.fillMaxWidth().testTag("oneshot_cwd_input"),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        Spacer(Modifier.height(16.dp))

        // Quick Presets
        Text("Quick Probes:", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { command = "id && uname -a" }, label = { Text("id & uname") })
            SuggestionChip(onClick = { command = "pwd && ls -la" }, label = { Text("pwd & ls") })
            SuggestionChip(onClick = { command = "echo \$PATH" }, label = { Text("env PATH") })
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (command.isNotBlank()) {
                    viewModel.runOneShotCommand(command, cwd)
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("execute_oneshot_btn")
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Execute Command")
        }
    }
}

@Composable
fun ProtocolLogTab(viewModel: MainViewModel) {
    val events by viewModel.protocolEvents.collectAsState()
    val listState = rememberLazyListState()
    var selectedEvent by remember { mutableStateOf<ProtocolEventEntity?>(null) }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Protocol WebSocket Frames (${events.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            TextButton(onClick = { /* Clear is optional */ }) {
                Text("Auto-refreshing", fontSize = 11.sp)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(events, key = { it.id }) { event ->
                val dirColor = if (event.direction == "OUT") Color(0xFF1976D2) else Color(0xFF2E7D32)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { selectedEvent = event },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = dirColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = event.direction,
                                    color = dirColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = event.method,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = dateFormat.format(Date(event.timestamp)),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = event.summary,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Full Payload Inspector Dialog
        selectedEvent?.let { ev ->
            AlertDialog(
                onDismissRequest = { selectedEvent = null },
                title = { Text("${ev.direction}: ${ev.method}") },
                text = {
                    Column {
                        Text(ev.summary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF0D1117),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        ) {
                            Text(
                                text = ev.fullPayload,
                                color = Color(0xFFC9D1D9),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedEvent = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
fun DiagnosticsTab(
    viewModel: MainViewModel,
    onRequestTermuxPermission: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val steps by viewModel.acceptanceSteps.collectAsState()
    val isRunning by viewModel.isAcceptanceRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Backend Acceptance Suite", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Verifies real initialize handshake, stdout/stderr isolation, exit code handling, and PTY stream against phone loopback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.runAcceptanceChecks() },
                enabled = !isRunning,
                modifier = Modifier.weight(1f).testTag("run_acceptance_btn")
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Running...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Run Checks")
                }
            }

            OutlinedButton(
                onClick = {
                    val report = viewModel.buildDiagnosticReport()
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Codex Goal Diagnostics Report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Report"))
                },
                modifier = Modifier.testTag("share_report_btn")
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Share Report")
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Termux Executor Supervisor Recovery", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "If the executor is not running, send a RUN_COMMAND intent to launch codex-exec-service inside Termux.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.startTermuxExecutor(context, onRequestTermuxPermission) },
                    modifier = Modifier.testTag("start_termux_btn")
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start Termux Executor", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Acceptance Test Steps:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        if (steps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Click 'Run Checks' to execute live verification tests against phone loopback.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(steps) { step ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (badgeColor, badgeText) = when (step.status) {
                                StepStatus.PASSED -> Color(0xFF2E7D32) to "PASS"
                                StepStatus.FAILED -> Color(0xFFD32F2F) to "FAIL"
                                StepStatus.RUNNING -> Color(0xFF1976D2) to "RUNNING"
                                StepStatus.PENDING -> Color(0xFF757575) to "PENDING"
                            }
                            Surface(
                                color = badgeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    badgeText,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(step.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(step.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                step.detail?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(it, fontSize = 11.sp, color = badgeColor, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
