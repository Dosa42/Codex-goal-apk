package com.example.console

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class InfrastructureClientTest {
    private lateinit var server: MockWebServer
    private val client = InfrastructureClient()
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }

    @Test fun discoveryUsesOneGetAndPreservesDistinctComponentEvidence() = runBlocking {
        val descriptor = mapOf("schema" to "goal-infrastructure/1", "observedAt" to "catalog-time",
            "connections" to mapOf("goalConsole" to mapOf("url" to ConsoleClient.DEFAULT_ENDPOINT, "protocol" to "goal-console/1")),
            "components" to listOf(mapOf("id" to "apk", "source" to mapOf("version" to "0.1.18"),
                "packaged" to mapOf("version" to "0.1.17", "sha256" to "packaged-hash"),
                "installed" to null)), "startupKits" to listOf(mapOf("id" to "native", "version" to "kit-version")))
        server.enqueue(MockResponse().setBody(ConsoleJson.write(descriptor)))
        val response = client.discover(server.url("/").toString())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/.well-known/goal-infrastructure.json", request.path)
        assertEquals("0.1.18", response.objects("components").single().obj("source").text("version"))
        assertEquals("0.1.17", response.objects("components").single().obj("packaged").text("version"))
        assertNull(response.objects("components").single()["installed"])
        assertEquals("catalog-time", response["observedAt"])
        assertEquals(1, server.requestCount)
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
}
