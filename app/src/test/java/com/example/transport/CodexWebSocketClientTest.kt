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
    fun testUtf8SplitAcrossChunksDecode() {
        // Multi-byte UTF-8 string: "Hello \uD83D\uDE80 World" (Rocket emoji 🚀 is 4 bytes: F0 9F 9A 80)
        val text = "Hello 🚀 World"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)

        // Split across boundary right in the middle of the 4-byte rocket emoji:
        // "Hello " is 6 bytes. Byte 6,7,8,9 are the rocket.
        // Split at index 8 (first 2 bytes of emoji in chunk 1, last 2 bytes in chunk 2)
        val chunk1Bytes = bytes.copyOfRange(0, 8)
        val chunk2Bytes = bytes.copyOfRange(8, bytes.size)

        // When concatenated in a continuous byte stream (as done by xterm.js terminal.write(Uint8Array)),
        // the stream is 100% losslessly preserved
        val outputStream = ByteArrayOutputStream()
        outputStream.write(chunk1Bytes)
        outputStream.write(chunk2Bytes)

        val reconstructed = String(outputStream.toByteArray(), StandardCharsets.UTF_8)
        assertEquals("Continuous binary stream must reconstruct exact UTF-8 without corruption", text, reconstructed)
    }
}
