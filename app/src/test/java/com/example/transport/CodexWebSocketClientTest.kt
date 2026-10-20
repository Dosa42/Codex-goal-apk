package com.example.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.InitializeResult
import com.example.data.repository.ExecRepository
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodexWebSocketClientTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repository: ExecRepository
    private lateinit var client: CodexWebSocketClient
    private val scope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ExecRepository(db)
        client = CodexWebSocketClient(repository, scope)
    }

    @After
    fun tearDown() {
        client.stop()
        scope.cancel()
        server.shutdown()
        db.close()
    }

    @Test
    fun testRealWebSocketHandshakeThenInitializedWithEmptyParams() = runBlocking {
        val serverReceivedMessages = LinkedBlockingQueue<String>()

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceivedMessages.add(text)
                val json = JSONObject(text)
                if (json.optString("method") == "initialize") {
                    val id = json.getLong("id")
                    val resp = JSONObject().apply {
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("sessionId", "session-verified-123")
                            put("environmentInfo", JSONObject().apply {
                                put("cwd", "file:///data/data/com.termux/files/home")
                                put("platformOs", "android")
                                put("executorVersion", "0.0.0")
                            })
                        })
                    }
                    webSocket.send(resp.toString())
                }
            }
        }))

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        client.start(wsUrl)

        // 1. Verify initialize frame
        val initMsg = serverReceivedMessages.poll(5, TimeUnit.SECONDS)
        assertNotNull(initMsg)
        val initJson = JSONObject(initMsg!!)
        assertEquals("initialize", initJson.getString("method"))
        assertEquals("codex-goal-android", initJson.getJSONObject("params").getString("clientName"))

        // 2. Verify initialized frame sent with params: {}
        val initializedMsg = serverReceivedMessages.poll(5, TimeUnit.SECONDS)
        assertNotNull(initializedMsg)
        val initializedJson = JSONObject(initializedMsg!!)
        assertEquals("initialized", initializedJson.getString("method"))
        assertNotNull(initializedJson.optJSONObject("params"))
        assertEquals(0, initializedJson.getJSONObject("params").length())

        // 3. Verify state transitions to INITIALIZED
        for (i in 0..20) {
            if (client.connectionState.value == ConnectionState.INITIALIZED) break
            delay(100)
        }
        assertEquals(ConnectionState.INITIALIZED, client.connectionState.value)
        assertEquals("session-verified-123", client.currentSessionId.value)
    }

    @Test
    fun testFilesystemWireBytesAndExplicitErrors() = runBlocking {
        val received = LinkedBlockingQueue<JSONObject>()
        val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text)
                val method = request.optString("method")
                if (!request.has("id")) return
                val response = JSONObject().put("id", request.getLong("id"))
                if (method == "initialize") {
                    response.put("result", JSONObject().put("sessionId", "filesystem-session"))
                } else {
                    received.add(request)
                    val params = request.getJSONObject("params")
                    when (method) {
                        "fs/writeFile" -> {
                            stored[params.getString("path")] = params.getString("dataBase64")
                            response.put("result", JSONObject())
                        }
                        "fs/readFile" -> {
                            val value = stored[params.getString("path")]
                            if (value == null) response.put("error", JSONObject().put("code", -32000).put("message", "File absent"))
                            else response.put("result", JSONObject().put("dataBase64", value))
                        }
                    }
                }
                webSocket.send(response.toString())
            }
        }))
        client.start(server.url("/").toString().replace("http://", "ws://"))
        withTimeout(5000) { while (client.connectionState.value != ConnectionState.INITIALIZED) delay(10) }
        val uri = "file:///data/data/com.termux/files/home/.codex/a%20b-%C3%BC.txt"
        val bytes = "APK ⇄ Termux\n\u0000 exact bytes".toByteArray(Charsets.UTF_8)
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        client.fileSystemRequest("fs/writeFile", JSONObject().put("path", uri).put("dataBase64", encoded))
        val read = client.fileSystemRequest("fs/readFile", JSONObject().put("path", uri))
        assertArrayEquals(bytes, android.util.Base64.decode(read.getString("dataBase64"), android.util.Base64.NO_WRAP))
        assertEquals(uri, received.poll(2, TimeUnit.SECONDS)!!.getJSONObject("params").getString("path"))
        assertEquals("fs/readFile", received.poll(2, TimeUnit.SECONDS)!!.getString("method"))
        try {
            client.fileSystemRequest("fs/readFile", JSONObject().put("path", uri + ".missing"))
            fail("Missing file must be reported")
        } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("File absent")) }
        assertEquals(0, client.pendingRpcCount)
        assertTrue(repository.getAllProcesses().isEmpty())
    }

    @Test
    fun testFilesystemMutationTimeoutIsNotReplayed() = runBlocking {
        client.stop()
        client = CodexWebSocketClient(repository, scope, rpcTimeoutMs = 250, retryBaseMs = 20)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                when (message.optString("method")) {
                    "initialize" -> webSocket.send(JSONObject().put("id", message.getLong("id"))
                        .put("result", JSONObject().put("sessionId", "fs-timeout-session")).toString())
                    "fs/writeFile" -> writes.incrementAndGet() // accepted frame, deliberately no reply
                }
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        val endpoint = server.url("/").toString().replace("http://", "ws://")
        client.start(endpoint)
        withTimeout(5000) { while (client.connectionState.value != ConnectionState.INITIALIZED) delay(10) }
        try {
            client.fileSystemRequest("fs/writeFile", JSONObject().put("path", "file:///tmp/owned").put("dataBase64", "eA=="))
            fail("A missing mutation reply must remain uncertain")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(0, client.pendingRpcCount)
        val oldGeneration = client.socketGeneration
        client.start(endpoint, isReconnect = true)
        withTimeout(5000) { while (client.connectionState.value != ConnectionState.INITIALIZED) delay(10) }
        try {
            client.fileSystemRequest("fs/writeFile", JSONObject().put("path", "file:///tmp/owned").put("dataBase64", "eA=="), oldGeneration)
            fail("An operation from the old generation must not write after reconnect")
        } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("generation")) }
        delay(100)
        assertEquals(1, writes.get())
        assertEquals(0, client.pendingRpcCount)
    }

    @Test
    fun testUnknownSessionRpcErrorTransitionsToSessionLost() = runBlocking {
        // Pre-populate old session
        repository.saveSession(InitializeResult("unknown-expired-session-999"), server.url("/").toString().replace("http://", "ws://"))

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("method") == "initialize") {
                    val id = json.getLong("id")
                    // Real executor returns JSON-RPC error for unknown session
                    val resp = JSONObject().apply {
                        put("id", id)
                        put("error", JSONObject().apply {
                            put("code", -32600)
                            put("message", "Session unknown or expired on executor")
                        })
                    }
                    webSocket.send(resp.toString())
                }
            }
        }))

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        client.start(wsUrl)

        // Verify client transitions to SESSION_LOST and halts
        for (i in 0..25) {
            if (client.connectionState.value == ConnectionState.SESSION_LOST) break
            delay(100)
        }
        assertEquals(ConnectionState.SESSION_LOST, client.connectionState.value)

        // Verify previous session is marked lost in DB
        val lastSess = repository.getLatestSession()
        assertEquals("SESSION_LOST", lastSess?.status)
    }

    @Test
    fun testExplicitFreshSessionAfterSessionLost() = runBlocking {
        // Pre-populate lost session
        repository.saveSession(InitializeResult("lost-sess"), server.url("/").toString().replace("http://", "ws://"))
        repository.markSessionLost("lost-sess")

        val serverReceivedMessages = LinkedBlockingQueue<String>()

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceivedMessages.add(text)
                val json = JSONObject(text)
                if (json.optString("method") == "initialize") {
                    val id = json.getLong("id")
                    val resp = JSONObject().apply {
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("sessionId", "brand-new-session-111")
                        })
                    }
                    webSocket.send(resp.toString())
                }
            }
        }))

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        // Trigger explicit fresh session
        client.start(wsUrl, freshSession = true)

        val initMsg = serverReceivedMessages.poll(5, TimeUnit.SECONDS)
        assertNotNull(initMsg)
        val initJson = JSONObject(initMsg!!)
        // resumeSessionId must be null for fresh session
        assertTrue(initJson.getJSONObject("params").isNull("resumeSessionId") ||
                initJson.getJSONObject("params").optString("resumeSessionId").isEmpty())

        for (i in 0..20) {
            if (client.connectionState.value == ConnectionState.INITIALIZED) break
            delay(100)
        }
        assertEquals(ConnectionState.INITIALIZED, client.connectionState.value)
        assertEquals("brand-new-session-111", client.currentSessionId.value)
    }

    @Test
    fun testSocketACallbackIgnoredAfterSocketBTurnover() = runBlocking {
        var socketA: WebSocket? = null
        var socketB: WebSocket? = null
        val serverReceivedA = LinkedBlockingQueue<String>()
        val serverReceivedB = LinkedBlockingQueue<String>()

        // Socket A upgrade
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketA = webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceivedA.add(text)
            }
        }))

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        client.start(wsUrl)

        // Wait for socket A to connect
        val initA = serverReceivedA.poll(5, TimeUnit.SECONDS)
        assertNotNull(initA)

        // Second connection (Socket B turnover)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketB = webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceivedB.add(text)
                val json = JSONObject(text)
                if (json.optString("method") == "initialize") {
                    val id = json.getLong("id")
                    webSocket.send(JSONObject().apply {
                        put("id", id)
                        put("result", JSONObject().apply { put("sessionId", "session-B") })
                    }.toString())
                }
            }
        }))

        // Turnover client to Socket B
        client.start(wsUrl)
        val initB = serverReceivedB.poll(5, TimeUnit.SECONDS)
        assertNotNull(initB)

        for (i in 0..20) {
            if (client.connectionState.value == ConnectionState.INITIALIZED) break
            delay(100)
        }
        assertEquals("session-B", client.currentSessionId.value)

        // Now Socket A tries to send a late message / notification
        socketA?.send(JSONObject().apply {
            put("method", "process/output")
            put("params", JSONObject().apply {
                put("processId", "stale-proc")
                put("seq", 999L)
                put("stream", "stdout")
                put("chunk", "c3RhbGU=")
            })
        }.toString())

        delay(300)
        // Stale chunk from Socket A must not be in DB
        val staleChunks = repository.getChunks("stale-proc")
        assertTrue("Stale socket A callbacks must be dropped", staleChunks.isEmpty())
    }

    @Test
    fun testWriteStatusParsing() = runBlocking {
        var serverWs: WebSocket? = null

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWs = webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("method") == "initialize") {
                    webSocket.send(JSONObject().apply {
                        put("id", json.getLong("id"))
                        put("result", JSONObject().apply { put("sessionId", "write-test-sess") })
                    }.toString())
                } else if (json.optString("method") == "process/write") {
                    val id = json.getLong("id")
                    val params = json.getJSONObject("params")
                    assertNotNull("writeId must be present in params", params.getString("writeId"))

                    // Return accepted status
                    webSocket.send(JSONObject().apply {
                        put("id", id)
                        put("result", JSONObject().apply { put("status", "accepted") })
                    }.toString())
                }
            }
        }))

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        client.start(wsUrl)

        for (i in 0..20) {
            if (client.connectionState.value == ConnectionState.INITIALIZED) break
            delay(100)
        }

        val writeResult = client.writeProcess("proc-1", "echo hi\n".toByteArray())
        assertTrue("Write result must be Accepted", writeResult is WriteStatus.Accepted)
    }

    @Test
    fun testReadCatchupCrossesExitSequenceBeforeFinalPtyOutput() = runBlocking {
        val cursors = LinkedBlockingQueue<Long>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                if (!j.has("id")) return
                val result = when (j.optString("method")) {
                    "initialize" -> JSONObject().put("sessionId", "lifecycle-gap-session")
                    "process/read" -> {
                        val p = j.getJSONObject("params")
                        val after = if (p.isNull("afterSeq")) 0L else p.getLong("afterSeq")
                        cursors.add(after)
                        val chunks = JSONArray()
                        if (after == 0L) {
                            chunks.put(JSONObject().put("seq", 1).put("stream", "pty").put("chunk", "aW5wdXQ="))
                            chunks.put(JSONObject().put("seq", 2).put("stream", "pty").put("chunk", "cmVwbHk="))
                        }
                        // Exit event consumes seq 3; final PTY bytes arrive at seq 4.
                        if (after == 2L) chunks.put(JSONObject().put("seq", 4).put("stream", "pty").put("chunk", "dWlk"))
                        JSONObject().put("chunks", chunks).put("nextSeq", if (after == 0L) 3 else 6)
                            .put("exited", true).put("exitCode", 7).put("closed", after != 0L)
                    }
                    else -> null
                }
                if (result != null) webSocket.send(JSONObject().put("id", j.getLong("id")).put("result", result).toString())
            }
        }))
        client.start(server.url("/").toString().replace("http://", "ws://"))
        withTimeout(5000) { while (client.connectionState.value != ConnectionState.INITIALIZED) delay(10) }
        val pid = "exit-before-tail"
        repository.createProcess(pid, "lifecycle-gap-session", "PTY", listOf("sh"), null, null, true)
        val first = client.readProcessCatchup(pid, null)
        assertTrue(first.exited)
        assertFalse(first.closed)
        assertEquals("false", repository.getSetting("outputComplete:$pid"))
        client.readProcessCatchup(pid, repository.getProcess(pid)?.lastConsumedSeq)
        assertEquals(4L, repository.getProcess(pid)?.lastConsumedSeq)
        assertEquals("false", repository.getSetting("outputComplete:$pid"))
        val end = client.readProcessCatchup(pid, repository.getProcess(pid)?.lastConsumedSeq)
        assertTrue(end.closed && end.chunks.isEmpty())
        assertEquals(listOf(0L, 2L, 4L), List(3) { cursors.poll(2, TimeUnit.SECONDS) })
        assertEquals(listOf(1L, 2L, 4L), repository.getChunks(pid).map { it.seq })
        assertEquals("true", repository.getSetting("outputComplete:$pid"))
        assertEquals(7, repository.getProcess(pid)?.exitCode)
        assertEquals(0, client.pendingRpcCount)
    }

    @Test
    fun testPaginatedCatchup() = runBlocking {
        var serverWs: WebSocket? = null
        var readCallCount = 0

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWs = webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("method") == "initialize") {
                    webSocket.send(JSONObject().apply {
                        put("id", json.getLong("id"))
                        put("result", JSONObject().apply { put("sessionId", "catchup-sess") })
                    }.toString())
                } else if (json.optString("method") == "process/read") {
                    readCallCount++
                    val id = json.getLong("id")
                    val afterSeq = if (json.getJSONObject("params").isNull("afterSeq")) null else json.getJSONObject("params").getLong("afterSeq")

                    if (afterSeq == null) {
                        // First page: seq 1, 2
                        webSocket.send(JSONObject().apply {
                            put("id", id)
                            put("result", JSONObject().apply {
                                put("chunks", JSONArray().apply {
                                    put(JSONObject().apply { put("seq", 1L); put("stream", "stdout"); put("chunk", "Y2gx") })
                                    put(JSONObject().apply { put("seq", 2L); put("stream", "stdout"); put("chunk", "Y2gy") })
                                })
                                put("nextSeq", 3L)
                                put("exited", false)
                                put("closed", false)
                            })
                        }.toString())
                    } else if (afterSeq == 2L) {
                        // Second page: seq 3
                        webSocket.send(JSONObject().apply {
                            put("id", id)
                            put("result", JSONObject().apply {
                                put("chunks", JSONArray().apply {
                                    put(JSONObject().apply { put("seq", 3L); put("stream", "stdout"); put("chunk", "Y2gz") })
                                })
                                put("nextSeq", 4L)
                                put("exited", true)
                                put("exitCode", 0)
                                put("closed", true)
                            })
                        }.toString())
                    }
                }
            }
        }))

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        client.start(wsUrl)
        for (i in 0..20) {
            if (client.connectionState.value == ConnectionState.INITIALIZED) break
            delay(100)
        }

        val pid = "proc-catchup-1"
        repository.createProcess(pid, "catchup-sess", "Catchup Proc", listOf("sh"), null, null, false)

        // Page 1
        val res1 = client.readProcessCatchup(pid, null)
        assertEquals(2, res1.chunks.size)
        assertEquals(2L, repository.getProcess(pid)?.lastConsumedSeq)

        // Page 2
        val res2 = client.readProcessCatchup(pid, 2L)
        assertEquals(1, res2.chunks.size)
        assertTrue(res2.exited)
        assertEquals(0, res2.exitCode)
        assertEquals(3L, repository.getProcess(pid)?.lastConsumedSeq)
    }

    @Test
    fun testMutationRejectedBeforeInitializeWithoutCreatingProcess() = runBlocking {
        assertTrue(client.startProcess(listOf("sh"), tty = false).isFailure)
        assertTrue(repository.getAllProcesses().isEmpty())
        assertTrue(client.writeProcess("missing", byteArrayOf(1)) is WriteStatus.Failed)
        assertEquals(0, client.pendingRpcCount)
    }

    @Test
    fun testHandshakeTimeoutRetriesWithCleanPendingMap() = runBlocking {
        client.stop()
        client = CodexWebSocketClient(repository, scope, rpcTimeoutMs = 200, retryBaseMs = 25)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                if (j.optString("method") == "initialize") {
                    webSocket.send(JSONObject().put("id", j.getLong("id"))
                        .put("result", JSONObject().put("sessionId", "retry-success")).toString())
                }
            }
        }))
        client.start(server.url("/").toString().replace("http://", "ws://"))
        withTimeout(5000) {
            while (client.connectionState.value != ConnectionState.INITIALIZED) delay(20)
        }
        assertEquals("retry-success", client.currentSessionId.value)
        assertEquals(0, client.pendingRpcCount)
    }

    @Test
    fun testAutomaticCatchupDrainsExitedOutputLargerThanPage() = runBlocking {
        val pid = "large-exited-process"
        val sid = "large-exited-session"
        val url = server.url("/").toString().replace("http://", "ws://")
        repository.saveSession(InitializeResult(sid), url)
        repository.createProcess(pid, sid, "Large", listOf("sh"), null, null, false)
        repository.setProcessExited(pid, 7)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val first = android.util.Base64.encodeToString(ByteArray(65536) { 65 }, android.util.Base64.NO_WRAP)
        val second = android.util.Base64.encodeToString(ByteArray(32768) { 66 }, android.util.Base64.NO_WRAP)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                val method = j.optString("method")
                val result = when (method) {
                    "initialize" -> JSONObject().put("sessionId", sid)
                    "process/read" -> {
                        calls.incrementAndGet()
                        val p = j.getJSONObject("params")
                        val cursor = if (p.isNull("afterSeq")) 0L else p.getLong("afterSeq")
                        val chunks = JSONArray()
                        if (cursor == 0L) chunks.put(JSONObject().put("seq", 1).put("stream", "stdout").put("chunk", first))
                        if (cursor == 1L) chunks.put(JSONObject().put("seq", 2).put("stream", "stderr").put("chunk", second))
                        JSONObject().put("chunks", chunks).put("nextSeq", 3).put("exited", true)
                            .put("exitCode", 7).put("closed", true)
                    }
                    else -> null
                }
                if (result != null) webSocket.send(JSONObject().put("id", j.getLong("id")).put("result", result).toString())
            }
        }))
        client.start(url)
        withTimeout(5000) {
            while (repository.getSetting("outputComplete:$pid") != "true") delay(20)
        }
        assertEquals(3, calls.get())
        assertEquals(2L, repository.getProcess(pid)?.lastConsumedSeq)
        val chunks = repository.getChunks(pid)
        assertEquals(listOf("stdout", "stderr"), chunks.map { it.stream })
        assertEquals(98304, chunks.sumOf { android.util.Base64.decode(it.chunkBase64, android.util.Base64.NO_WRAP).size })
        assertEquals(7, repository.getProcess(pid)?.exitCode)
    }

    @Test
    fun testMalformedWriteResponseIsFailure() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                val result = when (j.optString("method")) {
                    "initialize" -> JSONObject().put("sessionId", "malformed-write")
                    "process/write" -> JSONObject()
                    else -> null
                }
                if (result != null) webSocket.send(JSONObject().put("id", j.getLong("id")).put("result", result).toString())
            }
        }))
        client.start(server.url("/").toString().replace("http://", "ws://"))
        withTimeout(5000) { while (client.connectionState.value != ConnectionState.INITIALIZED) delay(20) }
        assertTrue(client.writeProcess("process", byteArrayOf(65)) is WriteStatus.Failed)
    }

}
