package com.example.files

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileOperationJournalTest {
    private fun operation(id: String) = FilesOperation(id, "ws://native", "/source", "/destination", "Copy requested", true)

    @Test fun inspectingAnEarlierOperationDoesNotChangeTheLastPointerOrClearUncertainty() = runBlocking {
        var stored = FileOperationHistory()
        val journal = FileOperationJournal({ stored }, { stored = it })
        journal.restore()
        journal.record(operation("older"))
        journal.record(operation("newer").copy(phase = "Completed and verified", verified = true, requiresInspection = false))
        journal.observe("older", "source present, destination present", 10)
        assertEquals("newer", stored.lastId)
        assertTrue(stored.receipts.getValue("older").requiresInspection)
        try { journal.requireMutationAllowed(); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun manualResolutionRequiresObservationAndRetainsUnknownOutcomeAndError() = runBlocking {
        var stored = FileOperationHistory()
        val journal = FileOperationJournal({ stored }, { stored = it })
        journal.restore()
        journal.record(operation("unknown").copy(lastError = "reply lost"))
        try { journal.resolve("unknown", "acknowledged", 1); fail() } catch (_: IllegalStateException) {}
        journal.observe("unknown", "both entries present; completeness unknown", 2)
        try { journal.resolve("unknown", " ", 3); fail() } catch (_: IllegalArgumentException) {}
        journal.resolve("unknown", "Inspected and will keep both entries for manual repair", 4)
        val restored = FileOperationJournal({ stored }, { stored = it })
        restored.restore()
        restored.requireMutationAllowed()
        val receipt = restored.history.receipts.getValue("unknown")
        assertEquals("Copy requested", receipt.phase)
        assertEquals("reply lost", receipt.lastError)
        assertFalse(receipt.verified)
        assertFalse(receipt.requiresInspection)
        assertEquals(4L, receipt.resolution!!.recordedAt)
    }

    @Test fun failedPersistenceDoesNotPublishANewReceiptOrAdvancePointer() = runBlocking {
        val initial = FileOperationHistory("existing", mapOf("existing" to operation("existing")))
        val journal = FileOperationJournal({ initial }, { throw IOException("storage unavailable") })
        journal.restore()
        try { journal.record(operation("not-saved")); fail() } catch (_: IOException) {}
        assertEquals(initial, journal.history)
    }
}
