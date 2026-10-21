package com.example.console

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsoleStorageTest {
    private lateinit var context: Context
    private lateinit var storage: ConsoleStorage
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("goal-console.db")
        context.getSharedPreferences("goal-console", Context.MODE_PRIVATE).edit().clear().commit()
        storage = ConsoleStorage(context)
    }
    @After fun cleanup() { storage.close(); context.deleteDatabase("goal-console.db") }

    @Test fun discoveredDefaultCannotOverwriteAnExplicitConsoleSetting() {
        storage.applyConsoleDefault("http://127.0.0.1:9876")
        assertEquals("http://127.0.0.1:9876", storage.endpoint)
        storage.endpoint = "https://custom.example/console"
        storage.applyConsoleDefault("http://127.0.0.1:8792")
        storage.close()
        storage = ConsoleStorage(context)
        assertEquals("https://custom.example/console", storage.endpoint)
    }

    @Test fun discoveryCacheKeepsItsOwnTimestampAndDoesNotCrossUrls() {
        val descriptor = mapOf("schema" to "goal-infrastructure/1", "connections" to emptyMap<String, Any?>(), "components" to emptyList<Any>())
        storage.saveInfrastructure(InfrastructureObservation(descriptor = descriptor, fetchedAtEpochMs = 1234))
        storage.close()
        storage = ConsoleStorage(context)
        assertEquals(descriptor, storage.infrastructure().descriptor)
        assertEquals(1234L, storage.infrastructure().fetchedAtEpochMs)
        assertTrue(storage.infrastructure().stale)
        storage.infrastructureUrl = "https://custom.example/v1/infrastructure"
        assertTrue(storage.infrastructure().descriptor.isEmpty())
        assertNull(storage.infrastructure().fetchedAtEpochMs)
    }

    @Test fun cursorAndFullEventSurviveReopeningAndFailedAppend() {
        val endpoint = ConsoleClient.DEFAULT_ENDPOINT
        val key = ConsoleStorage.archiveKey(endpoint, "instance-a")
        val evidence = "output".repeat(10_000)
        storage.append(key, EventPage(listOf(mapOf("seq" to 1, "data" to evidence)), 1, false))
        try {
            storage.append(key, EventPage(listOf(mapOf("seq" to 2), mapOf("seq" to null)), 3, false))
            fail("Expected transaction rollback")
        } catch (_: IllegalArgumentException) {}
        storage.close()
        storage = ConsoleStorage(context)
        assertEquals(1L, storage.cursor(key))
        assertEquals(evidence, storage.events(key).single()["data"])
    }

    @Test fun replacedStoreHasNewCursorAndOldArchiveIsStillReadable() {
        val endpoint = ConsoleClient.DEFAULT_ENDPOINT
        storage.registerArchive(endpoint, "old")
        val oldKey = ConsoleStorage.archiveKey(endpoint, "old")
        storage.append(oldKey, EventPage(listOf(mapOf("seq" to 888, "data" to "old evidence")), 888, false))
        val archives = storage.registerArchive(endpoint, "new")
        val newKey = ConsoleStorage.archiveKey(endpoint, "new")
        assertEquals(0L, storage.cursor(newKey))
        storage.append(newKey, EventPage(listOf(mapOf("seq" to 1, "data" to "new evidence")), 1, false))
        assertEquals(888L, storage.cursor(oldKey))
        assertEquals("old evidence", storage.events(oldKey).single()["data"])
        assertEquals("new evidence", storage.events(newKey).single()["data"])
        assertTrue(archives.containsAll(listOf("old", "new")))
    }

    @Test fun pagesRemainAccessibleAndConsoleEndpointDoesNotTouchExecutorPreferences() {
        context.getSharedPreferences("executor", Context.MODE_PRIVATE).edit().putString("endpoint", "ws://exec:33995").commit()
        storage.endpoint = "http://console:8792"
        val events = (1..135).map { mapOf<String, Any?>("seq" to it) }
        storage.append("key", EventPage(events, 135, false))
        val recent = storage.events("key")
        assertEquals(100, recent.size)
        assertEquals(36L, recent.first().number("seq"))
        assertEquals(35, storage.events("key", before = 36).size)
        assertEquals("ws://exec:33995", context.getSharedPreferences("executor", Context.MODE_PRIVATE).getString("endpoint", null))
    }

    @Test fun ceoDraftAndUnknownActionAreRetainedWithoutDispatch() {
        val original = storage.ceoDraft()
        val submitted = original + mapOf("text" to "send once", "submitted" to true, "threadId" to "original")
        storage.saveCeoDraft(submitted)
        storage.beginAction(ConsoleClient.DEFAULT_ENDPOINT, "Send CEO message", submitted)
        storage.close()
        storage = ConsoleStorage(context)
        assertEquals(submitted, storage.ceoDraft())
        assertTrue(storage.actions(ConsoleClient.DEFAULT_ENDPOINT).single().text("outcome").contains("unknown"))
    }

    @Test fun offlineRecreationEnumeratesNonLegacyArchiveAndRestoresItsCursor() {
        val endpoint = ConsoleClient.DEFAULT_ENDPOINT
        storage.registerArchive(endpoint, "instance-retained")
        val key = ConsoleStorage.archiveKey(endpoint, "instance-retained")
        storage.append(key, EventPage(listOf(mapOf("seq" to 42, "data" to "retained")), 42, false))
        storage.close()
        storage = ConsoleStorage(context)
        val instance = storage.lastInstance(endpoint)
        assertEquals("instance-retained", instance)
        assertEquals(listOf(instance), storage.archives(endpoint))
        assertEquals(42L, storage.cursor(ConsoleStorage.archiveKey(endpoint, instance)))
        assertEquals("retained", storage.events(ConsoleStorage.archiveKey(endpoint, instance)).single()["data"])
    }

    @Test fun operationUuidIsDurableBeforeAnyHttpDispatch() {
        storage.beginAction(ConsoleClient.DEFAULT_ENDPOINT, "Pause mocked goal", mapOf("status" to "paused"), "persisted-operation")
        storage.close()
        storage = ConsoleStorage(context)
        val action = storage.actions(ConsoleClient.DEFAULT_ENDPOINT).single()
        assertEquals("persisted-operation", action["operationId"])
        assertTrue(action.text("outcome").contains("unknown"))
    }
}
