package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.console.ConsoleScreen
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
    val connectionError by viewModel.lastConnectionError.collectAsState()
    val actionStatus by viewModel.lastWriteStatus.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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
                            if (selectedTab > 0) ConnectionBadge(connectionState)
                        }
                        Text(
                            text = if (selectedTab == 0) "Native Goal Console" else "Exec-Server Native Client",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (selectedTab > 0) IconButton(
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
            if (selectedTab > 0) {
                // Connection & Endpoint Control Bar
                ConnectionControlBar(
                    endpoint = endpointInput,
                    onEndpointChange = { endpointInput = it },
                    onSaveAndConnect = {
                        viewModel.connect(context, endpointInput)
                    },
                    onReconnect = { viewModel.reconnect(context) },
                    onFreshSession = {
                        viewModel.connectFreshSession(context, endpointInput)
                    },
                    onStop = { viewModel.stop(context) },
                    onCheckHealth = { viewModel.checkHealth() },
                    healthStatus = healthStatus,
                    connectionState = connectionState
                )

                // Session Lost Action Banner
                connectionError?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
                if (actionStatus?.startsWith("Error") == true) {
                    Text(actionStatus ?: "", modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                }
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
            }

            // Navigation Tabs
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Console") },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    modifier = Modifier.testTag("tab_console")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Processes") },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    modifier = Modifier.testTag("tab_processes")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Run Command") },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    modifier = Modifier.testTag("tab_run_command")
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Protocol Log") },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    modifier = Modifier.testTag("tab_protocol_log")
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("Diagnostics") },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    modifier = Modifier.testTag("tab_diagnostics")
                )
                Tab(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    text = { Text("Permissions") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.testTag("tab_permissions")
                )
                Tab(
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 },
                    text = { Text("Workspace") },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    modifier = Modifier.testTag("tab_workspace")
                )
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> ConsoleScreen()
                    1 -> ProcessesTab(viewModel)
                    2 -> RunCommandTab(viewModel)
                    3 -> ProtocolLogTab(viewModel)
                    4 -> DiagnosticsTab(viewModel, onRequestTermuxPermission)
                    5 -> PermissionsTab()
                    6 -> WorkspaceTab(viewModel)
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
                Text("Observed Executor Environment", fontWeight = FontWeight.Bold)
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
                Text("Open the connected executor shell or run commands.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.launchBashShell() },
                    modifier = Modifier.testTag("launch_bash_shell_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Launch Interactive Shell")
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
    val workspace by viewModel.workspace.collectAsState()
    var command by remember { mutableStateOf("pwd && ls -la") }
    var cwd by remember(workspace.workingUri) { mutableStateOf(workspace.workingUri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Run One-Shot Shell Command", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Executes commands with the connected executor’s reported shell without PTY. Standard error is preserved separately.",
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
fun WorkspaceTab(viewModel: MainViewModel) {
    val state by viewModel.workspace.collectAsState()
    var path by remember(state.directoryUri) { mutableStateOf(viewModel.workspacePath(state.directoryUri)) }
    var createKind by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Executor Workspace", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Working folder: ${viewModel.workspacePath(state.workingUri)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(state.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true,
            label = { Text("Executor folder") }, modifier = Modifier.fillMaxWidth().testTag("workspace_path"),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { viewModel.browseWorkspace(path) }, enabled = !state.busy && state.loaded) { Text("Open folder") }
            OutlinedButton(onClick = { viewModel.workspaceUp() }, enabled = !state.busy && state.loaded) { Text("Up") }
            OutlinedButton(onClick = { viewModel.loadWorkspace() }, enabled = !state.busy) { Text("Reload workspace") }
            Button(onClick = { viewModel.useWorkspaceDirectory() }, enabled = !state.busy && state.loaded,
                modifier = Modifier.testTag("workspace_use_folder")) { Text("Use as working folder") }
            OutlinedButton(onClick = { newName = ""; createKind = "file" }, enabled = !state.busy && state.loaded) { Text("New file") }
            OutlinedButton(onClick = { newName = ""; createKind = "folder" }, enabled = !state.busy && state.loaded) { Text("New folder") }
        }
        if (state.configUri.isNotBlank()) Text("Setting: ${viewModel.workspacePath(state.configUri)}", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        if (state.fileUri != null) {
            Text(viewModel.workspacePath(state.fileUri!!) + if (state.dirty) "  • unsaved" else "", fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveWorkspaceFile() }, enabled = !state.busy && state.dirty,
                    modifier = Modifier.testTag("workspace_save_file")) { Text("Save to executor") }
                OutlinedButton(onClick = { viewModel.openWorkspaceFile(state.fileUri!!) }, enabled = !state.busy) { Text("Reload file") }
                TextButton(onClick = { viewModel.closeWorkspaceFile() }, enabled = !state.busy) { Text(if (state.dirty) "Close / discard edits" else "Close file") }
            }
            OutlinedTextField(value = state.text, onValueChange = { viewModel.editWorkspaceText(it) },
                readOnly = state.busy, modifier = Modifier.fillMaxWidth().weight(1f).testTag("workspace_editor"),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(state.entries, key = { it.uri }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                        if (entry.directory) viewModel.browseWorkspace(entry.uri) else viewModel.openWorkspaceFile(entry.uri)
                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.Description, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(entry.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
    createKind?.let { kind ->
        AlertDialog(onDismissRequest = { createKind = null }, title = { Text("New $kind on executor") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                label = { Text("Name") }, modifier = Modifier.testTag("workspace_new_name")) },
            confirmButton = { TextButton(onClick = { viewModel.createWorkspaceEntry(newName, kind == "folder"); createKind = null },
                enabled = newName.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { createKind = null }) { Text("Cancel") } })
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

private data class PermissionEntry(
    val name: String,
    val label: String,
    val granted: Boolean,
    val runtime: Boolean,
    val special: Boolean,
    val restricted: Boolean,
    val status: String
)

private val specialPermissionNames = setOf(
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_MEDIA",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.ACCESS_NOTIFICATION_POLICY",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.USE_FULL_SCREEN_INTENT"
)

private fun specialPermissionGranted(context: Context, name: String): Boolean = when (name) {
    "android.permission.MANAGE_EXTERNAL_STORAGE" -> Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    "android.permission.MANAGE_MEDIA" -> Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
    "android.permission.SYSTEM_ALERT_WINDOW" -> Settings.canDrawOverlays(context)
    "android.permission.WRITE_SETTINGS" -> Settings.System.canWrite(context)
    "android.permission.REQUEST_INSTALL_PACKAGES" -> Build.VERSION.SDK_INT >= 26 && context.packageManager.canRequestPackageInstalls()
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    "android.permission.ACCESS_NOTIFICATION_POLICY" -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    "android.permission.SCHEDULE_EXACT_ALARM" -> Build.VERSION.SDK_INT >= 31 && context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    "android.permission.USE_FULL_SCREEN_INTENT" -> if (Build.VERSION.SDK_INT >= 34) context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    "android.permission.PACKAGE_USAGE_STATS" -> {
        val manager = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            else manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }
    else -> false
}

@Suppress("DEPRECATION")
private fun readPermissions(context: Context): List<PermissionEntry> {
    val pm = context.packageManager
    val pkg = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    return (pkg.requestedPermissions ?: emptyArray()).distinct().map { name ->
        val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
        val base = info?.protectionLevel?.and(PermissionInfo.PROTECTION_MASK_BASE)
        val special = name in specialPermissionNames
        val granted = if (special) runCatching { specialPermissionGranted(context, name) }.getOrDefault(false)
            else ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        val restricted = Build.VERSION.SDK_INT >= 29 && info != null && (info.flags and PermissionInfo.FLAG_HARD_RESTRICTED) != 0
        val status = when {
            info == null -> "Not provided by this Android build or installed apps"
            special -> if (granted) "Enabled in Android special access" else "Special access not enabled"
            granted -> "Granted"
            restricted -> "Not granted · Android classifies this permission as restricted"
            base == PermissionInfo.PROTECTION_DANGEROUS -> "Runtime permission not granted"
            base == PermissionInfo.PROTECTION_SIGNATURE -> "Requires matching signer, role or system grant"
            else -> "Declared · not granted"
        }
        PermissionEntry(name, info?.loadLabel(pm)?.toString() ?: name.substringAfterLast('.'), granted,
            base == PermissionInfo.PROTECTION_DANGEROUS, special, restricted, status)
    }.sortedWith(compareBy<PermissionEntry> { if (it.special) 0 else if (it.runtime) 1 else 2 }.thenBy { it.name })
}

private fun permissionSettingsIntent(context: Context, name: String): Intent {
    val uri = Uri.parse("package:" + context.packageName)
    val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    return when (name) {
        "android.permission.MANAGE_EXTERNAL_STORAGE" -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri) else app
        "android.permission.MANAGE_MEDIA" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, uri) else app
        "android.permission.SYSTEM_ALERT_WINDOW" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
        "android.permission.WRITE_SETTINGS" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, uri)
        "android.permission.REQUEST_INSTALL_PACKAGES" -> if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri) else app
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        "android.permission.ACCESS_NOTIFICATION_POLICY" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "android.permission.SCHEDULE_EXACT_ALARM" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri) else app
        "android.permission.USE_FULL_SCREEN_INTENT" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, uri) else app
        "android.permission.PACKAGE_USAGE_STATS" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, uri)
        else -> app
    }
}

@Composable
private fun PermissionsTab() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<PermissionEntry>()) }
    var query by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        feedback = "Android returned " + results.count { it.value } + " granted / " + results.size + " requested. Current state is refreshed below."
        revision++
    }
    LaunchedEffect(revision) {
        try { entries = readPermissions(context) }
        catch (e: Exception) { feedback = "Permission inspection failed: " + e.message }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun openSettings(name: String) {
        try { context.startActivity(permissionSettingsIntent(context, name)) }
        catch (e: Exception) { feedback = "Android could not open this settings page: " + e.message }
    }
    fun request(entry: PermissionEntry) {
        try {
            when {
                entry.special || entry.granted || !entry.runtime -> openSettings(entry.name)
                entry.name == "android.permission.ACCESS_BACKGROUND_LOCATION" -> {
                    if (ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                        feedback = "Grant foreground location first, then use Background location to select Allow all the time."
                        launcher.launch(arrayOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"))
                    } else if (Build.VERSION.SDK_INT >= 30) {
                        feedback = "In Android App permissions → Location, select Allow all the time."
                        openSettings(entry.name)
                    } else launcher.launch(arrayOf(entry.name))
                }
                entry.name == "android.permission.BODY_SENSORS_BACKGROUND" -> {
                    if (ContextCompat.checkSelfPermission(context, "android.permission.BODY_SENSORS") != PackageManager.PERMISSION_GRANTED) {
                        feedback = "Grant body sensors first, then request background body sensors separately."
                        launcher.launch(arrayOf("android.permission.BODY_SENSORS"))
                    } else launcher.launch(arrayOf(entry.name))
                }
                entry.name == "android.permission.ACCESS_FINE_LOCATION" -> launcher.launch(arrayOf("android.permission.ACCESS_COARSE_LOCATION", entry.name))
                Build.VERSION.SDK_INT >= 34 && entry.name in setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") ->
                    launcher.launch(arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
                else -> launcher.launch(arrayOf(entry.name))
            }
        } catch (e: Exception) { feedback = "Permission request failed: " + e.message }
    }
    val visible = entries.filter { query.isBlank() || it.name.contains(query, true) || it.label.contains(query, true) || it.status.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize().testTag("permissions_list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Android capabilities", style = MaterialTheme.typography.titleMedium)
            Text(entries.size.toString() + " declared · " + entries.count { it.granted } + " granted or enabled · Android " + Build.VERSION.SDK_INT,
                style = MaterialTheme.typography.bodySmall)
            Text("This reads the installed manifest and Android's current grants. Special access and runtime grants are separate from declarations.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { revision++ }) { Text("Refresh") }
                TextButton(onClick = { openSettings("") }) { Text("App settings") }
                TextButton(onClick = {
                    val report = entries.joinToString("\n") { it.name + " | " + it.status }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Codex Goal permissions", report))
                    feedback = "Copied actual permission states."
                }) { Text("Copy report") }
            }
            OutlinedButton(onClick = {
                val names = entries.filter { it.runtime && !it.special && !it.granted &&
                    it.name !in setOf("android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.BODY_SENSORS_BACKGROUND") }.map { it.name }.toMutableList()
                if ("android.permission.ACCESS_FINE_LOCATION" in names && "android.permission.ACCESS_COARSE_LOCATION" !in names) names.add("android.permission.ACCESS_COARSE_LOCATION")
                if (names.isEmpty()) feedback = "No ordinary runtime grants remain. Review special access and restricted entries below."
                else try { launcher.launch(names.toTypedArray()) } catch (e: Exception) { feedback = "Permission request failed: " + e.message }
            }, modifier = Modifier.testTag("request_runtime_permissions")) { Text("Request runtime permissions") }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter permissions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        items(visible, key = { it.name }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(entry.label, fontWeight = FontWeight.SemiBold)
                    Text(entry.name, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text(entry.status, color = if (entry.granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    TextButton(onClick = { request(entry) }, modifier = Modifier.testTag("permission_" + entry.name)) {
                        Text(if (entry.special) "Android special access" else if (entry.granted || !entry.runtime) "Android app settings" else "Request grant")
                    }
                }
            }
        }
    }
}
