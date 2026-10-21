package com.example.console

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsoleViewModelTest {
    private lateinit var application: Application
    private val models = ViewModelStore()
    private lateinit var server: MockWebServer
    private val offline = AtomicBoolean(false)
    private val discovery = AtomicReference(infrastructureFixture())

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
                    "/.well-known/goal-infrastructure.json" -> ConsoleJson.writeIncludingNulls(discovery.get())
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
        withTimeout(10_000) { model.refreshInfrastructureAndAwait() }
        assertFalse(model.state.value.infrastructure.stale)
        assertFalse(model.state.value.infrastructure.loading)
        val fetchedAt = model.state.value.infrastructure.fetchedAtEpochMs
        assertNotNull(fetchedAt)
        assertEquals(endpoint, model.state.value.endpoint)
        assertEquals("discovery-time", model.state.value.infrastructure.descriptor["observedAt"])
        offline.set(true)
        withTimeout(10_000) { model.refreshInfrastructureAndAwait() }
        assertNotNull(model.state.value.infrastructure.error)
        assertTrue(model.state.value.infrastructure.stale)
        assertEquals(fetchedAt, model.state.value.infrastructure.fetchedAtEpochMs)
        assertEquals("0.1.19", model.state.value.infrastructure.descriptor.objects("components").first().obj("source").text("version"))
        assertEquals(2, server.requestCount)
    }

    @Test fun malformedDiscoveryRetainsLastValidCacheAndNeverAppliesItsBinding() = runBlocking {
        val model = model()
        until { model.state.value.initialized }
        withTimeout(10_000) { model.refreshInfrastructureAndAwait() }
        assertFalse(model.state.value.infrastructure.stale)
        assertFalse(model.state.value.infrastructure.loading)
        assertEquals(1, server.requestCount)
        val good = model.state.value.infrastructure
        val endpoint = model.state.value.endpoint
        val unsupported = infrastructureFixture("http://127.0.0.1:9876").let { value ->
            value + ("connections" to (value.obj("connections") + ("execServer" to mapOf(
                "url" to "ws://127.0.0.1:9877", "protocol" to "future-exec-server-incompatible"))))
        }
        for ((index, case) in listOf(
            (infrastructureFixture() + ("components" to emptyList<JsonObject>())) to "Missing canonical component IDs",
            unsupported to "execServer protocol mismatch"
        ).withIndex()) {
            val (bad, expectedError) = case
            discovery.set(bad)
            withTimeout(10_000) { model.refreshInfrastructureAndAwait() }
            assertEquals(index + 2, server.requestCount)
            assertTrue(model.state.value.infrastructure.stale)
            assertFalse(model.state.value.infrastructure.loading)
            assertTrue(model.state.value.infrastructure.error?.contains(expectedError) == true)
            assertNotNull(model.state.value.infrastructure.error)
            assertEquals(good.descriptor, model.state.value.infrastructure.descriptor)
            assertEquals(good.fetchedAtEpochMs, model.state.value.infrastructure.fetchedAtEpochMs)
            assertEquals(endpoint, model.state.value.endpoint)
            ConsoleStorage(application).use {
                assertEquals(good.descriptor, it.infrastructure().descriptor)
                assertEquals(good.fetchedAtEpochMs, it.infrastructure().fetchedAtEpochMs)
                assertEquals(endpoint, it.endpoint)
            }
        }
    }

    @Test fun obsoleteDiscoveredArchiveCannotPublishAfterReplacementKeepsVisibleEndpoint() = runBlocking {
        val oldEndpoint = server.url("/").toString().trimEnd('/')
        val obsoleteEndpoint = "http://127.0.0.1:9876"
        application.getSharedPreferences("goal-console", 0).edit().remove("endpoint")
            .putString("discoveredConsoleDefault", oldEndpoint).commit()
        ConsoleStorage(application).use {
            for ((endpoint, instance, seq) in listOf(
                Triple(oldEndpoint, "visible-store", 31L), Triple(obsoleteEndpoint, "obsolete-store", 41L)
            )) {
                it.registerArchive(endpoint, instance)
                it.append(ConsoleStorage.archiveKey(endpoint, instance),
                    EventPage(listOf(mapOf("seq" to seq, "data" to instance)), seq, false))
            }
        }
        val enteredArchiveRead = CompletableDeferred<Job>()
        val releaseArchiveRead = CompletableDeferred<Unit>()
        val model = ConsoleViewModel(application) { storage, endpoint ->
            if (endpoint == obsoleteEndpoint) {
                enteredArchiveRead.complete(checkNotNull(currentCoroutineContext()[Job]))
                releaseArchiveRead.await()
            }
            readConsoleEndpointArchive(storage, endpoint)
        }.also { models.put("console", it) }
        val replacement = MockWebServer()
        replacement.enqueue(MockResponse().setBody(ConsoleJson.writeIncludingNulls(
            infrastructureFixture(oldEndpoint) + ("observedAt" to "B"))))
        replacement.start()
        val publishedEndpoints = mutableListOf<String>()
        val collector = launch {
            model.state.map { it.endpoint }.distinctUntilChanged().collect { publishedEndpoints.add(it) }
        }
        try {
            until { model.state.value.initialized }
            discovery.set(infrastructureFixture(obsoleteEndpoint) + ("observedAt" to "A"))
            model.refreshInfrastructureNow()
            val adoption = withTimeout(10_000) { enteredArchiveRead.await() }
            assertTrue(model.state.value.infrastructure.loading)
            assertEquals(oldEndpoint, model.state.value.endpoint)
            ConsoleStorage(application).use { assertEquals(obsoleteEndpoint, it.endpoint) }
            withTimeout(10_000) { model.saveInfrastructureUrl(replacement.url("/").toString()).join() }
            assertEquals(1, replacement.requestCount)
            assertEquals("B", model.state.value.infrastructure.descriptor.text("observedAt"))
            assertFalse(model.state.value.infrastructure.stale)
            assertFalse(model.state.value.infrastructure.loading)
            val accepted = model.state.value.infrastructure
            ConsoleStorage(application).use {
                assertEquals(oldEndpoint, it.endpoint)
                assertEquals("B", it.infrastructure().descriptor.text("observedAt"))
            }
            releaseArchiveRead.complete(Unit)
            withTimeout(10_000) { adoption.join() }
            assertEquals(oldEndpoint, model.state.value.endpoint)
            assertEquals("visible-store", model.state.value.eventArchive)
            assertEquals(31L, model.state.value.cursor)
            assertEquals("visible-store", model.state.value.events.single()["data"])
            assertEquals(accepted, model.state.value.infrastructure)
            assertFalse(publishedEndpoints.contains(obsoleteEndpoint))
            ConsoleStorage(application).use { assertEquals(model.state.value.endpoint, it.endpoint) }
        } finally {
            releaseArchiveRead.complete(Unit)
            collector.cancelAndJoin()
            replacement.shutdown()
        }
    }

    @Test fun discoveredDefaultArchiveLoadSurvivesObserverCancellationAndRestartsObservation() = runBlocking {
        val target = MockWebServer()
        target.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                "/v1/health" -> """{"ok":true,"protocol":"goal-console/1","instanceId":"target-store"}"""
                "/v1/console" -> """{"ok":true,"observedAt":"target-live","errors":[]}"""
                else -> """{"ok":true,"tasks":[],"events":[],"cursor":41,"hasMore":false,"errors":[]}"""
            })
        }
        target.start()
        val enteredArchiveRead = CompletableDeferred<Unit>()
        val releaseArchiveRead = CompletableDeferred<Unit>()
        val oldEndpoint = server.url("/").toString().trimEnd('/')
        val newEndpoint = target.url("/").toString().trimEnd('/')
        application.getSharedPreferences("goal-console", 0).edit().remove("endpoint")
            .putString("discoveredConsoleDefault", oldEndpoint).commit()
        ConsoleStorage(application).use {
            it.registerArchive(newEndpoint, "target-store")
            it.append(ConsoleStorage.archiveKey(newEndpoint, "target-store"),
                EventPage(listOf(mapOf("seq" to 41, "data" to "target-retained")), 41, false))
        }
        discovery.set(infrastructureFixture(newEndpoint))
        val model = ConsoleViewModel(application) { storage, endpoint ->
            if (endpoint == newEndpoint) {
                enteredArchiveRead.complete(Unit)
                releaseArchiveRead.await()
            }
            readConsoleEndpointArchive(storage, endpoint)
        }.also { models.put("console", it) }
        // Same endpoint/initialized keys and cancellation behavior as the screen's LaunchedEffect.
        var observer = launch {
            model.state.map { it.endpoint to it.initialized }.distinctUntilChanged().collectLatest { (_, initialized) ->
                if (initialized) model.observe()
            }
        }
        try {
            until { enteredArchiveRead.isCompleted }
            assertEquals(oldEndpoint, model.state.value.endpoint)
            assertTrue(model.state.value.initialized)
            observer.cancelAndJoin()
            releaseArchiveRead.complete(Unit)
            until { model.state.value.endpoint == newEndpoint && model.state.value.initialized }
            assertEquals("target-store", model.state.value.eventArchive)
            assertEquals(41L, model.state.value.cursor)
            assertEquals("target-retained", model.state.value.events.single()["data"])
            observer = launch { model.observe() }
            until { model.state.value.ready && model.state.value.snapshot.text("observedAt") == "target-live" }
            assertTrue(target.requestCount > 0)
            ConsoleStorage(application).use { assertEquals(newEndpoint, it.endpoint) }
        } finally {
            releaseArchiveRead.complete(Unit)
            observer.cancelAndJoin()
            target.shutdown()
        }
    }

    @Test fun savingDiscoveryUrlBReplacesDelayedAWithoutAnotherRefreshOrTimer() = runBlocking {
        val arrivedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivedA.countDown()
                releaseA.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody(ConsoleJson.writeIncludingNulls(infrastructureFixture() + ("observedAt" to "A")))
            }
        }
        val target = MockWebServer()
        target.enqueue(MockResponse().setBody(ConsoleJson.writeIncludingNulls(infrastructureFixture() + ("observedAt" to "B"))))
        target.start()
        val model = model()
        try {
            until { model.state.value.initialized }
            model.refreshInfrastructureNow()
            until { arrivedA.count == 0L }
            model.saveInfrastructureUrl(target.url("/").toString())
            until { model.state.value.infrastructure.descriptor.text("observedAt") == "B" }
            assertEquals(1L, releaseA.count) // A has not responded; B was actually fetched immediately.
            assertEquals(1, target.requestCount)
            assertFalse(model.state.value.infrastructure.stale)
            val urlB = InfrastructureClient.discoveryUrl(target.url("/").toString())
            assertEquals(urlB, model.state.value.infrastructure.url)
            ConsoleStorage(application).use {
                assertEquals(urlB, it.infrastructureUrl)
                assertEquals("B", it.infrastructure().descriptor.text("observedAt"))
            }
            releaseA.countDown()
            delay(100)
            assertEquals("B", model.state.value.infrastructure.descriptor.text("observedAt"))
        } finally {
            releaseA.countDown()
            target.shutdown()
        }
    }
}
