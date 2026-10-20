package com.example.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.InitializeResult
import com.example.data.model.OutputChunkDto
import com.example.data.model.ProcessStatus
import com.example.data.repository.ExecRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExecRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ExecRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testContiguousCursorGapAndDuplicateScenarios() = runBlocking {
        val processId = "proc-contiguous-1"
        repository.createProcess(
            processId = processId,
            sessionId = "sess-1",
            title = "Contiguous Test",
            argv = listOf("/bin/sh"),
            cwd = null,
            env = null,
            tty = false
        )

        // Case 1: Arrival [1, 3] -> Contiguous cursor MUST be 1 (seq 2 is missing)
        repository.recordChunk(processId, 1L, "stdout", "Y2h1bmsx")
        repository.recordChunk(processId, 3L, "stdout", "Y2h1bmsz")
        var proc = repository.getProcess(processId)
        assertEquals("Cursor must be 1 when seq 2 is missing in arrival [1, 3]", 1L, proc?.lastConsumedSeq)

        // Case 2: Arrival [2] with prior [1, 3] -> Contiguous cursor MUST advance to 3
        repository.recordChunk(processId, 2L, "stdout", "Y2h1bmsy")
        proc = repository.getProcess(processId)
        assertEquals("Cursor must advance to 3 after missing seq 2 arrives", 3L, proc?.lastConsumedSeq)

        // Verify out-of-order bytes are all retained in order
        val chunks = repository.getChunks(processId)
        assertEquals(3, chunks.size)
        assertEquals(listOf(1L, 2L, 3L), chunks.map { it.seq })
    }

    @Test
    fun testDuplicateArrivalWithPrior() = runBlocking {
        val processId = "proc-contiguous-2"
        repository.createProcess(
            processId = processId,
            sessionId = "sess-1",
            title = "Dup Test",
            argv = listOf("/bin/sh"),
            cwd = null,
            env = null,
            tty = false
        )

        // Prior [1] -> cursor 1
        repository.recordChunk(processId, 1L, "stdout", "Y2h1bmsx")
        assertEquals(1L, repository.getProcess(processId)?.lastConsumedSeq)

        // Arrival [2, 2] -> cursor MUST be 2, duplicate seq 2 discarded
        val firstInsert = repository.recordChunk(processId, 2L, "stdout", "Y2h1bmsy")
        assertTrue(firstInsert)
        val dupInsert = repository.recordChunk(processId, 2L, "stdout", "Y2h1bmsy")
        assertFalse(dupInsert)

        val proc = repository.getProcess(processId)
        assertEquals("Cursor must be 2 after arrival [2, 2] with prior [1]", 2L, proc?.lastConsumedSeq)
        assertEquals(2, repository.getChunks(processId).size)
    }

    @Test
    fun testCrashReopenPreservesContiguousCursor() {
        runBlocking {
            // Use a named in-memory database to simulate process restart / reopen
            val dbFile = context.getDatabasePath("test_crash_db")
            dbFile.delete()

            val db1 = Room.databaseBuilder(context, AppDatabase::class.java, "test_crash_db")
                .allowMainThreadQueries()
                .build()
            val repo1 = ExecRepository(db1)

            val pid = "proc-crash-1"
            repo1.createProcess(pid, "sess-crash", "Crash Test", listOf("sh"), null, null, false)
            repo1.recordChunk(pid, 1L, "stdout", "c3RyMQ==")
            repo1.recordChunk(pid, 2L, "stdout", "c3RyMg==")
            repo1.recordChunk(pid, 4L, "stdout", "c3RyNA==") // gap: 3 is missing

            assertEquals(2L, repo1.getProcess(pid)?.lastConsumedSeq)
            db1.close()

            // Reopen database
            val db2 = Room.databaseBuilder(context, AppDatabase::class.java, "test_crash_db")
                .allowMainThreadQueries()
                .build()
            val repo2 = ExecRepository(db2)

            val procReopened = repo2.getProcess(pid)
            assertNotNull(procReopened)
            assertEquals("Reopened database must preserve contiguous cursor 2", 2L, procReopened?.lastConsumedSeq)

            // Now supply missing chunk 3 -> cursor must jump to 4
            repo2.recordChunk(pid, 3L, "stdout", "c3RyMw==")
            val procRepaired = repo2.getProcess(pid)
            assertEquals("Cursor must advance to 4 once missing chunk 3 is inserted", 4L, procRepaired?.lastConsumedSeq)

            db2.close()
            dbFile.delete()
        }
    }

    @Test
    fun testEndpointChangeDoesNotResumeUnrelatedSession() = runBlocking {
        // Save session on port 33995
        repository.saveSession(InitializeResult("sess-port-33995"), "ws://127.0.0.1:33995")
        val latest = repository.getLatestSession()
        assertNotNull(latest)
        assertEquals("sess-port-33995", latest?.sessionId)
        assertEquals("ws://127.0.0.1:33995", latest?.endpoint)

        // If endpoint is changed to port 4500, previous session endpoint does not match
        val isSameEndpoint = (latest?.endpoint == "ws://127.0.0.1:4500")
        assertFalse("Unrelated endpoint must not resume previous session", isSameEndpoint)
    }

    @Test
    fun testProcessStatusMonotonicity() = runBlocking {
        val pid = "proc-mono-1"
        repository.createProcess(pid, "s1", "Title", listOf("sh"), null, null, false)

        repository.setProcessRunning(pid)
        assertEquals(ProcessStatus.RUNNING.name, repository.getProcess(pid)?.status)

        repository.setProcessExited(pid, 0)
        assertEquals(ProcessStatus.EXITED.name, repository.getProcess(pid)?.status)

        // Late running signal must NOT regress EXITED to RUNNING
        repository.setProcessRunning(pid)
        assertEquals(ProcessStatus.EXITED.name, repository.getProcess(pid)?.status)

        repository.setProcessClosed(pid)
        assertEquals(ProcessStatus.CLOSED.name, repository.getProcess(pid)?.status)

        // Late running signal must NOT regress CLOSED to RUNNING
        repository.setProcessRunning(pid)
        assertEquals(ProcessStatus.CLOSED.name, repository.getProcess(pid)?.status)
    }

    @Test
    fun testExitAndCloseKeepExitCodeInEitherArrivalOrder() = runBlocking {
        for (code in listOf(0, 7)) for (closeFirst in listOf(false, true)) {
            val pid = "exit-close-$code-$closeFirst"
            repository.createProcess(pid, "s1", "Exit race", listOf("sh"), null, null, false)
            if (closeFirst) repository.setProcessClosed(pid)
            repository.setProcessExited(pid, code)
            repository.setProcessClosed(pid)
            // Reproduce a stale close callback that had observed a null exit code.
            db.processDao().updateStatusAndExit(pid, ProcessStatus.CLOSED.name, null, System.currentTimeMillis())
            // A late read with no exit field must not reopen CLOSED or erase a known code.
            repository.setProcessExited(pid, null)
            val process = repository.getProcess(pid)
            assertEquals(ProcessStatus.CLOSED.name, process?.status)
            assertEquals(code, process?.exitCode)
        }
    }
}
