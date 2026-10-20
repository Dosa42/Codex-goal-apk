package com.example.console

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ConsoleClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ConsoleClient
    @Before fun setup() {
        server = MockWebServer()
        server.start()
        client = ConsoleClient(server.url("/").toString())
    }
    @After fun cleanup() { server.shutdown() }
    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    @Test fun assignmentHasExactContractAndExplicitImmutableId() = runBlocking {
        reply("""{"ok":true,"task":{"state":"queued"},"ledger":{"revision":1}}""")
        client.assign(Assignment("coder", "phone-42", listOf("owned/file"), "hash recorded", "execute nonce", listOf("parent-1")))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/team/assign", request.path)
        val body = ConsoleJson.read(request.body.readUtf8())
        assertEquals(setOf("role", "taskId", "owner", "scope", "acceptance", "nextAction", "dependencies"), body.keys)
        assertEquals("phone-42", body["taskId"])
        assertEquals(listOf("parent-1"), body["dependencies"])
        assertEquals("execute nonce", body["nextAction"])
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidAssignmentCannotReachTheServer() = runBlocking {
        try {
            client.assign(Assignment("coder", "id", emptyList(), "accept", "run", emptyList()))
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun disconnectedMutationIsNeverAutomaticallyRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try {
            client.assign(Assignment("coder", "uncertain", listOf("file"), "check", "run", emptyList()))
            fail("Expected unknown transport outcome")
        } catch (_: IOException) {}
        assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsDoNotReplayAnOperatorMutation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other"))
            .setBody("""{"ok":false,"error":{"code":"redirect","message":"manual reconciliation"}}"""))
        try { client.testConnection("id"); fail("Expected HTTP 307") }
        catch (e: ConsoleHttpException) { assertEquals(307, e.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun conflictAndLongEvidenceAreRetainedWithoutTruncation() = runBlocking {
        val detail = "e".repeat(24_000)
        reply(ConsoleJson.write(mapOf("ok" to false, "error" to mapOf("code" to "conflict", "message" to detail))), 409)
        try { client.assign(Assignment("coder", "same-id", listOf("scope"), "accept", "instruction", emptyList())); fail("Expected conflict") }
        catch (e: ConsoleHttpException) {
            assertEquals(409, e.status)
            assertEquals(detail, e.payload.obj("error")["message"])
            assertTrue(e.message!!.endsWith(detail))
        }
    }

    @Test fun snapshotPreservesPartialFailuresAndObservedSource() = runBlocking {
        reply("""{"ok":true,"observedAt":"original-time","ceo":{"threadId":"original","source":"persisted","live":false},"errors":[{"source":"native","message":"offline"}]}""")
        val snapshot = client.console()
        assertEquals("original-time", snapshot["observedAt"])
        assertEquals(false, snapshot.obj("ceo")["live"])
        assertEquals("persisted", snapshot.obj("ceo")["source"])
        assertEquals("offline", snapshot.objects("errors").single()["message"])
    }

    @Test fun eventPagingUsesReturnedCursorAndKeepsCompleteData() = runBlocking {
        val data = "full-output".repeat(3_000)
        reply(ConsoleJson.write(mapOf("ok" to true, "events" to listOf(mapOf("seq" to 124, "type" to "task.result", "data" to data)), "cursor" to 124, "hasMore" to true)))
        reply("""{"ok":true,"events":[{"seq":125,"type":"next","data":{}}],"cursor":125,"hasMore":false}""")
        val first = client.events(123, limit = 1)
        assertEquals(data, first.events.single()["data"])
        assertEquals(124L, first.cursor)
        assertTrue(first.hasMore)
        val second = client.events(first.cursor, limit = 1)
        assertEquals(125L, second.cursor)
        assertFalse(second.hasMore)
        assertEquals("123", server.takeRequest().requestUrl!!.queryParameter("after"))
        assertEquals("124", server.takeRequest().requestUrl!!.queryParameter("after"))
    }

    @Test fun invalidEventPageCannotAdvanceTheCursor() = runBlocking {
        reply("""{"ok":true,"events":[],"cursor":900,"hasMore":false}""")
        try { client.events(12); fail("Expected skipped page rejection") }
        catch (_: IllegalArgumentException) {}
        reply("""{"ok":true,"events":[{"seq":13},{"seq":12}],"cursor":13,"hasMore":false}""")
        try { client.events(11); fail("Expected order rejection") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun connectionIdIsEncodedAsOnePathSegmentAndSchemaPassesUnchanged() = runBlocking {
        reply("""{"ok":true}""")
        val payload = mapOf("id" to "mcp-local", "kind" to "mcp-stdio", "enabled" to true,
            "options" to mapOf("command" to "node", "args" to listOf("server.mjs"), "cwd" to "/owned"))
        client.addConnection(payload)
        assertEquals(payload, ConsoleJson.read(server.takeRequest().body.readUtf8()))
        reply("""{"ok":true,"available":false,"error":"down"}""")
        val result = client.testConnection("id with/slash")
        val request = server.takeRequest()
        assertEquals("/v1/connections/id%20with%2Fslash/test", request.path)
        assertEquals(false, result["available"])
    }

    @Test fun ceoInboxUsesOriginalTargetAndReceiptIsReadOnly() = runBlocking {
        reply("""{"ok":true,"message":{"messageId":"apk-message","status":"queued"}}""")
        val queued = client.sendCeo("apk-message", "original-ceo", "hello")
        assertEquals("queued", queued.obj("message")["status"])
        val body = ConsoleJson.read(server.takeRequest().body.readUtf8())
        assertEquals(mapOf("messageId" to "apk-message", "threadId" to "original-ceo", "text" to "hello", "source" to "apk.text"), body)
        reply("""{"ok":true,"message":{"messageId":"apk-message","status":"received"}}""")
        assertEquals("received", client.ceoReceipt("apk-message").obj("message")["status"])
        val receipt = server.takeRequest()
        assertEquals("GET", receipt.method)
        assertEquals("/v1/ceo/inbox/apk-message", receipt.path)
        assertEquals(2, server.requestCount)
    }

    @Test fun wrongServiceCannotCountAsConsoleReadiness() = runBlocking {
        reply("""{"ok":true,"protocol":"exec-server"}""")
        try { client.health(); fail("Expected protocol mismatch") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun http200EventSubsystemErrorsAreRetainedWithThePage() = runBlocking {
        reply("""{"ok":true,"events":[{"seq":1}],"cursor":1,"hasMore":false,"errors":[{"source":"ledger.sync","message":"revision conflict"}]}""")
        val page = client.events(0)
        assertEquals(1L, page.cursor)
        assertEquals("ledger.sync", page.errors.single()["source"])
        assertEquals("revision conflict", page.errors.single()["message"])
    }

    @Test fun controlOperationUuidIsSentWithoutChangingNativeBody() = runBlocking {
        reply("""{"ok":true,"actionId":"persisted-op-1","result":{}}""")
        client.runtime("coder", "restart", "persisted-op-1")
        val request = server.takeRequest()
        assertEquals("persisted-op-1", request.getHeader("Idempotency-Key"))
        assertEquals(mapOf("action" to "restart"), ConsoleJson.read(request.body.readUtf8()))
        reply("""{"ok":true,"actionId":"persisted-op-2","result":{}}""")
        client.goal("mock-specialist", "paused", "persisted-op-2")
        assertEquals("persisted-op-2", server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals(2, server.requestCount)
    }

    @Test fun operationReceiptLookupNeverReissuesTheSavedControl() = runBlocking {
        reply("""{"ok":true,"source":"console_action_receipt","action":{"actionId":"saved-op","phase":"finished","status":200,"response":{"ok":true}},"outcomeUnknown":false}""")
        val receipt = client.actionReceipt("saved-op")
        assertEquals("finished", receipt.obj("action")["phase"])
        assertEquals(false, receipt["outcomeUnknown"])
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/actions/saved-op", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun lifecycleCancellationCancelsAnOutstandingRead() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async(Dispatchers.Default) { client.events(0, wait = 15) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        delay(30)
        assertEquals(1, server.requestCount)
    }
}
