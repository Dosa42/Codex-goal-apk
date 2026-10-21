package com.example.console

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

internal fun infrastructureFixture(consoleUrl: String = ConsoleClient.DEFAULT_ENDPOINT): JsonObject = mapOf(
    "schema" to InfrastructureDescriptor.SCHEMA, "observedAt" to "discovery-time",
    "components" to listOf("codex-goal-apk", "native-hub", "meta-hub", "codex-browser-agent").map { id ->
        mapOf("id" to id, "name" to id, "source" to mapOf("version" to "0.1.19"),
            "packaged" to mapOf("version" to "0.1.18", "sha256" to "packaged-hash"), "installed" to null)
    },
    "connections" to InfrastructureDescriptor.defaultUrls.mapValues { (id, url) ->
        mapOf("url" to if (id == "goalConsole") consoleUrl else url, "protocol" to when (id) {
            "goalConsole" -> "goal-console/1"
            "execServer" -> "exec-server-jsonrpc"
            "nativeCodex" -> "codex-app-server-jsonrpc"
            else -> "$id-http"
        })
    },
    "startupKits" to listOf(mapOf("id" to "native", "version" to "kit-version"))
)

class InfrastructureClientTest {
    private lateinit var server: MockWebServer
    private val client = InfrastructureClient()
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }

    @Test fun discoveryUsesOneGetAndPreservesDistinctComponentEvidence() = runBlocking {
        val descriptor = infrastructureFixture() + ("observedAt" to "catalog-time")
        server.enqueue(MockResponse().setBody(ConsoleJson.writeIncludingNulls(descriptor)))
        val response = client.discover(server.url("/").toString())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/.well-known/goal-infrastructure.json", request.path)
        assertEquals("0.1.19", response.objects("components").first().obj("source").text("version"))
        assertEquals("0.1.18", response.objects("components").first().obj("packaged").text("version"))
        assertNull(response.objects("components").first()["installed"])
        assertEquals(InfrastructureDescriptor.requiredComponentIds, response.objects("components").map { it.text("id") }.toSet())
        assertEquals("catalog-time", response["observedAt"])
        assertEquals(1, server.requestCount)
    }

    @Test fun sameSchemaMalformedSetsAndUnsupportedExecutorCannotBecomeFreshDescriptors() = runBlocking {
        val good = infrastructureFixture()
        val components = good.objects("components")
        val invalid = listOf(
            good + ("components" to emptyList<JsonObject>()),
            good + ("components" to components.drop(1)),
            good + ("components" to (components + components.first())),
            good + ("components" to listOf(components.first() - "installed") + components.drop(1)),
            good + ("components" to listOf(components.first() + ("source" to null)) + components.drop(1)),
            good + ("components" to listOf(components.first() + ("installed" to emptyList<String>())) + components.drop(1)),
            good + ("components" to listOf(components.first() + ("packaged" to mapOf("version" to 19))) + components.drop(1)),
            good - "startupKits",
            good + ("connections" to (good.obj("connections") - "nativeCodex")),
            good + ("connections" to (good.obj("connections") + ("execServer" to mapOf(
                "url" to "ws://127.0.0.1:33995", "protocol" to "future-exec-server-incompatible")))),
            good + ("connections" to (good.obj("connections") + ("execServer" to mapOf(
                "url" to "http://127.0.0.1:33995", "protocol" to "exec-server-jsonrpc"))))
        )
        for ((index, descriptor) in invalid.withIndex()) {
            server.enqueue(MockResponse().setBody(ConsoleJson.writeIncludingNulls(descriptor)))
            try { client.discover(server.url("/").toString()); fail("Accepted malformed descriptor $index") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(invalid.size, server.requestCount)
    }

    @Test fun schemaMismatchAndProtocolMismatchCannotBecomeConnectionBindings() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"schema":"native-app-server/1","components":[],"connections":{}}"""))
        try { client.discover(server.url("/").toString()); fail("Wrong schema") }
        catch (_: IllegalArgumentException) {}
        val descriptor = mapOf("connections" to mapOf(
            "execServer" to mapOf("url" to "ws://127.0.0.1:4500", "protocol" to "codex-app-server/1"),
            "goalConsole" to mapOf("url" to "http://127.0.0.1:8788", "protocol" to "meta-hub/1")))
        for (id in listOf("execServer", "goalConsole")) {
            try { InfrastructureDescriptor.defaultBinding(descriptor, id); fail("Wrong protocol $id") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun explicitSchemaReadRetainsFullResponseAndHttpErrorsWithoutFollowingRedirects() = runBlocking {
        val schema = mapOf("tools" to listOf(mapOf("name" to "example", "inputSchema" to mapOf(
            "description" to "complete schema ".repeat(3000)))))
        val connection = mapOf("url" to server.url("/").toString(), "protocol" to "fixture/1",
            "schemas" to mapOf("tools" to "/v1/tools/schema?complete=1"))
        val url = InfrastructureDescriptor.links(connection).getValue("schemas.tools")
        server.enqueue(MockResponse().setBody(ConsoleJson.write(schema)))
        assertEquals(schema, client.readJson(url))
        assertEquals("/v1/tools/schema?complete=1", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"schema unavailable"}}"""))
        try { client.readJson(url); fail("HTTP failure") }
        catch (e: ConsoleHttpException) { assertEquals(503, e.status); assertTrue(e.message!!.contains("schema unavailable")) }
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")).setBody("{}"))
        try { client.readJson(url); fail("Redirect must remain visible") }
        catch (e: ConsoleHttpException) { assertEquals(307, e.status) }
        assertEquals(3, server.requestCount)
    }

    @Test fun cancellationAfterHeadersCancelsTheInFlightBody() = runBlocking {
        val headers = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        server.enqueue(MockResponse().setBody(ConsoleJson.writeIncludingNulls(infrastructureFixture()))
            .setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            InfrastructureClient(http).discover(server.url("/").toString())
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { headers.await(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        } finally {
            job.cancelAndJoin()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
