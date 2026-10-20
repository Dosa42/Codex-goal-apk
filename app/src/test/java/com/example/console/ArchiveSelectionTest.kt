package com.example.console

import org.junit.Assert.*
import org.junit.Test

class ArchiveSelectionTest {
    @Test fun blockedOlderReadCannotUpdateAnotherArchive() {
        val selection = ArchiveSelection()
        val blockedRead = selection.begin("endpoint", "A")
        val selectedB = selection.begin("endpoint", "B")
        assertFalse(selection.matches(blockedRead))
        assertTrue(selection.matches(selectedB))
    }
    @Test fun twoSelectionsCompletingInReverseOrderKeepLatestChoice() {
        val selection = ArchiveSelection()
        val slowA = selection.begin("endpoint", "A")
        val fastB = selection.begin("endpoint", "B")
        assertTrue(selection.matches(fastB))
        assertFalse(selection.matches(slowA))
    }
    @Test fun returningToSameArchiveDoesNotReviveAStaleRead() {
        val selection = ArchiveSelection()
        val originalA = selection.begin("endpoint", "A")
        selection.begin("endpoint", "B")
        val newA = selection.begin("endpoint", "A")
        assertFalse(selection.matches(originalA))
        assertTrue(selection.matches(newA))
    }
    @Test fun changingEndpointInvalidatesSavedViewToken() {
        val selection = ArchiveSelection()
        val first = selection.begin("first", "A")
        selection.begin("second", "A")
        assertFalse(selection.matches(first))
        assertFalse(selection.matches(null))
    }
}
