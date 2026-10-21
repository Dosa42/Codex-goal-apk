package com.example.console

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsoleViewModelTest {
    private lateinit var application: Application
    private val models = ViewModelStore()
    private lateinit var server: MockWebServer
    private val offline = AtomicBoolean(false)

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        application = ApplicationProvider.getApplicationContext()
        application.deleteDatabase("goal-console.db")
        application.getSharedPreferences("goal-console", 0).edit().clear().commit()
        File(application.filesDir, "goal-console-last-snapshot.json").delete()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (offline.get()) return MockResponse().setResponseCode(503)
                    .setBody("""{"ok":false,"error":{"code":"offline","message":"fixture offline"}}""")
                val body = when (request.requestUrl!!.encodedPath) {
                    "/v1/health" -> """{"ok":true,"protocol":"goal-console/1","instanceId":"fixture-store"}"""
                    "/v1/console" -> """{"ok":true,"observedAt":"retained-observation-time","ceo":{},"team":{"agents":[]},"errors":[]}"""
                    "/v1/connections" -> """{"ok":true,"backends":[],"errors":[]}"""
                    "/.well-known/goal-infrastructure.json" -> """{"schema":"goal-infrastructure/1","observedAt":"discovery-time","components":[{"id":"apk","source":{"version":"0.1.18"},"installed":null}],"connections":{"goalConsole":{"url":"http://127.0.0.1:8792","protocol":"goal-console/1"}},"startupKits":[]}"""
                    else -> """{"ok":true,"tasks":[],"errors":[]}"""
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        ConsoleStorage(application).use {
            it.endpoint = server.url("/").toString().trimEnd('/')
            it.infrastructureUrl = InfrastructureClient.discoveryUrl(server.url("/").toString())
        }
    }

    @After fun cleanup() {
        models.clear()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private suspend fun until(check: () -> Boolean) {
        withTimeout(10_000) { while (!check()) delay(10) }
    }
    private fun model(): ConsoleViewModel = ConsoleViewModel(application).also { models.put("console", it) }

    @Test fun offlineRecreationLoadsRetainedInstanceWithoutAHealthRequest() = runBlocking {
        val endpoint = server.url("/").toString().trimEnd('/')
        ConsoleStorage(application).use {
            it.registerArchive(endpoint, "retained-store")
            it.append(ConsoleStorage.archiveKey(endpoint, "retained-store"),
                EventPage(listOf(mapOf("seq" to 31, "data" to "saved")), 31, false))
        }
        offline.set(true)
        val model = model()
        until { model.state.value.initialized && model.state.value.ceoDraft.isNotEmpty() }
        val state = model.state.value
        assertFalse(state.ready)
        assertEquals("", state.instanceId)
        assertEquals("retained-store", state.eventArchive)
        assertEquals(31L, state.cursor)
        assertEquals("saved", state.events.single()["data"])
        assertTrue(state.archives.contains("retained-store"))
        assertEquals(0, server.requestCount)
    }

    @Test fun failedRefreshClearsReadinessAndMarksTheRetainedSnapshotStale() = runBlocking {
        val model = model()
        until { model.state.value.initialized }
        model.refresh()
        until { model.state.value.ready && model.state.value.snapshot.text("observedAt") == "retained-observation-time" }
        offline.set(true)
        model.refresh()
        until { !model.state.value.ready && model.state.value.snapshotStale && "health" in model.state.value.errors }
        assertEquals("retained-observation-time", model.state.value.snapshot["observedAt"])
        until {
            val file = File(application.filesDir, "goal-console-last-snapshot.json")
            file.exists() && runCatching { ConsoleJson.read(file.readText())["stale"] == true }.getOrDefault(false)
        }
        assertTrue(model.state.value.errors.values.any { it.contains("fixture offline") })
    }

    @Test fun discoveryFailureRetainsTimestampedCatalogAndCustomConsoleEndpoint() = runBlocking {
        val model = model()
        until { model.state.value.initialized }
        val endpoint = model.state.value.endpoint
        model.refreshInfrastructureNow()
        until { !model.state.value.infrastructure.stale }
        val fetchedAt = model.state.value.infrastructure.fetchedAtEpochMs
        assertNotNull(fetchedAt)
        assertEquals(endpoint, model.state.value.endpoint)
        assertEquals("discovery-time", model.state.value.infrastructure.descriptor["observedAt"])
        offline.set(true)
        model.refreshInfrastructureNow()
        until { model.state.value.infrastructure.error != null }
        assertTrue(model.state.value.infrastructure.stale)
        assertEquals(fetchedAt, model.state.value.infrastructure.fetchedAtEpochMs)
        assertEquals("0.1.18", model.state.value.infrastructure.descriptor.objects("components").single().obj("source").text("version"))
        assertEquals(2, server.requestCount)
    }
}
