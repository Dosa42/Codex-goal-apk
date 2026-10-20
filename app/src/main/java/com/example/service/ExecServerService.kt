package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.repository.ExecRepository
import com.example.transport.CodexWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileDescriptor
import java.io.PrintWriter
import org.json.JSONArray
import org.json.JSONObject

class ExecServerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var repository: ExecRepository
    private lateinit var client: CodexWebSocketClient
    private val commandMutex = Mutex()

    companion object {
        const val ACTION_START = "com.dosa42.codexgoal.ACTION_START"
        const val ACTION_STOP = "com.dosa42.codexgoal.ACTION_STOP"
        const val ACTION_RECONNECT = "com.dosa42.codexgoal.ACTION_RECONNECT"
        const val ACTION_FRESH_SESSION = "com.dosa42.codexgoal.ACTION_FRESH_SESSION"
        const val EXTRA_ENDPOINT = "extra_endpoint"

        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "codex_exec_server_channel"

        private val _activeClient = MutableStateFlow<CodexWebSocketClient?>(null)
        val activeClientFlow: StateFlow<CodexWebSocketClient?> = _activeClient.asStateFlow()
        val activeClient: CodexWebSocketClient? get() = _activeClient.value

        @Volatile
        private var _activeRepository: ExecRepository? = null
        val activeRepository: ExecRepository? get() = _activeRepository

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun startService(context: Context, endpoint: String? = null, freshSession: Boolean = false) {
            val intent = Intent(context, ExecServerService::class.java).apply {
                action = if (freshSession) ACTION_FRESH_SESSION else ACTION_START
                endpoint?.let { putExtra(EXTRA_ENDPOINT, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ExecServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reconnectService(context: Context) {
            val intent = Intent(context, ExecServerService::class.java).apply {
                action = ACTION_RECONNECT
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = ExecRepository(db)
        client = CodexWebSocketClient(repository, serviceScope)

        _activeClient.value = client
        _activeRepository = repository
        _isRunning.value = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Service initialized"))

        // Observe connection state and update notification
        serviceScope.launch {
            client.connectionState.collect { state ->
                val text = when (state) {
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.CONNECTING -> "Connecting to loopback..."
                    ConnectionState.INITIALIZING -> "Handshaking with exec-server..."
                    ConnectionState.INITIALIZED -> "Connected (Session: ${client.currentSessionId.value?.take(8)}...)"
                    ConnectionState.SESSION_LOST -> "Session lost; fresh session needed"
                    ConnectionState.ERROR -> "Error: ${client.lastError.value ?: "Connection failed"}"
                }
                updateNotification(text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val endpointExtra = intent?.getStringExtra(EXTRA_ENDPOINT)

        serviceScope.launch {
          commandMutex.withLock {
            val endpoint = endpointExtra ?: repository.getEndpoint()

            // If intent is null (sticky restart), honor connectionDesired
            if (intent == null) {
                if (!repository.isConnectionDesired()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                client.start(endpoint, isReconnect = true)
                return@launch
            }

            when (action) {
                ACTION_START -> {
                    repository.setConnectionDesired(true)
                    client.start(endpoint)
                }
                ACTION_RECONNECT -> {
                    repository.setConnectionDesired(true)
                    client.start(endpoint, isReconnect = true)
                }
                ACTION_FRESH_SESSION -> {
                    repository.setConnectionDesired(true)
                    client.start(endpoint, freshSession = true)
                }
                ACTION_STOP -> {
                    repository.setConnectionDesired(false)
                    client.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
          }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        client.stop()
        serviceJob.cancel()
        _isRunning.value = false
        _activeClient.value = null
        _activeRepository = null
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val state = JSONObject()
        state.put("timestamp", System.currentTimeMillis())
        state.put("connectionState", client.connectionState.value.name)
        state.put("lastError", client.lastError.value ?: JSONObject.NULL)
        state.put("sessionId", client.currentSessionId.value ?: JSONObject.NULL)
        state.put("socketGeneration", client.socketGeneration)
        state.put("pendingRpcCount", client.pendingRpcCount)
        state.put("observedEnvironment", client.observedEnvironment.value?.let { env ->
            JSONObject().apply {
                put("cwd", env.cwd ?: JSONObject.NULL)
                put("userHomeDir", env.userHomeDir ?: JSONObject.NULL)
                put("platformOs", env.platformOs ?: JSONObject.NULL)
                put("executorVersion", env.executorVersion ?: JSONObject.NULL)
                put("shell", JSONObject().put("name", env.shell?.name ?: JSONObject.NULL).put("path", env.shell?.path ?: JSONObject.NULL))
                put("capabilities", JSONObject(env.capabilities ?: emptyMap<String, Boolean>()))
            }
        } ?: JSONObject.NULL)
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(2500) {
                    state.put("endpoint", repository.getEndpoint())
                    state.put("connectionDesired", repository.isConnectionDesired())
                    val report = repository.getSetting("lastAcceptanceReport")
                    state.put("lastAcceptanceReport", report?.let { JSONObject(it) } ?: JSONObject.NULL)
                    val processes = JSONArray()
                    for (p in repository.getAllProcesses()) {
                        processes.put(JSONObject().apply {
                            put("processId", p.processId); put("sessionId", p.sessionId)
                            put("title", p.title); put("status", p.status)
                            put("exitCode", p.exitCode ?: JSONObject.NULL)
                            put("lastConsumedSeq", p.lastConsumedSeq ?: JSONObject.NULL)
                            put("deliveryUncertain", p.deliveryUncertain)
                            put("lastReadResult", repository.getSetting("processResult:${p.processId}")?.let { JSONObject(it) } ?: JSONObject.NULL)
                        })
                    }
                    state.put("processes", processes)
                }
            }
        } catch (e: Exception) {
            state.put("dumpError", e.message ?: e.javaClass.name)
        }
        writer.println("CODEX_GOAL_STATE")
        writer.println(state.toString())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Codex Exec-Server Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors persistent local WebSocket connection to Codex exec-server"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reconnectIntent = Intent(this, ExecServerService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPending = PendingIntent.getService(
            this, 1, reconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ExecServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Codex Goal")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_rotate, "Reconnect", reconnectPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
