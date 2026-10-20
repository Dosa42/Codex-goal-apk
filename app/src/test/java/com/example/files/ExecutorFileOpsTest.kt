package com.example.files

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExecutorFileOpsTest {
    private val session = FileSession("ws://native:33995", "one", 7)
    private data class Node(val directory: Boolean, val bytes: String = "")
    private class Tree : FileRpc {
        val nodes = linkedMapOf("/" to Node(true), "/home" to Node(true), "/Downloads" to Node(true))
        val calls = mutableListOf<Pair<String, Map<String, Any>>>()
        val aliases = mutableMapOf<String, String>()
        var failCopy = false
        var failRemove = false
        var corruptCopy = false
        var loseCopyReply = false
        var partialCopyReplyLoss = false
        var loseRemoveReply = false
        var afterCall: (() -> Unit)? = null
        fun file(path: String, bytes: ByteArray = byteArrayOf(0, -1, 10, 13, 65)) { nodes[path] = Node(false, Base64.getEncoder().encodeToString(bytes)) }
        fun directory(path: String) { nodes[path] = Node(true) }
        override suspend fun request(method: String, params: Map<String, Any>): FsResponse {
            calls += method to params
            fun path(key: String = "path") = FilePaths.path(params.getValue(key).toString())
            val result = when (method) {
                "fs/canonicalize" -> FsResponse(path = FilePaths.uri(aliases[path()] ?: path()))
                "fs/readDirectory" -> {
                    val parent = path()
                    check(nodes[parent]?.directory == true) { "Not a directory: $parent" }
                    FsResponse(entries = nodes.filter { (name, _) -> name != "/" && FilePaths.path(FilePaths.parent(name)) == parent }.map { (name, value) ->
                        FileEntry(FilePaths.name(name), FilePaths.uri(name), value.directory, !value.directory)
                    })
                }
                "fs/getMetadata" -> { val node = nodes.getValue(path()); FsResponse(directory = node.directory, file = !node.directory) }
                "fs/readFile" -> FsResponse(dataBase64 = nodes.getValue(path()).bytes)
                "fs/copy" -> {
                    if (failCopy) throw IOException("fs/copy unsupported or response lost")
                    val source = path("sourcePath"); val destination = path("destinationPath")
                    val copied = nodes.filterKeys { it == source || it.startsWith("$source/") }.toMap()
                    (if (partialCopyReplyLoss) copied.entries.take(2) else copied.entries).forEach { (name, node) ->
                        nodes[destination + name.removePrefix(source)] = if (corruptCopy && !node.directory) node.copy(bytes = "different") else node
                    }
                    if (loseCopyReply || partialCopyReplyLoss) throw IOException("copy wrote destination; reply lost")
                    FsResponse()
                }
                "fs/remove" -> {
                    if (failRemove) throw IOException("remove response lost")
                    val target = path()
                    nodes.keys.removeAll { it == target || it.startsWith("$target/") }
                    if (loseRemoveReply) throw IOException("remove applied; reply lost")
                    FsResponse()
                }
                "fs/createDirectory" -> { nodes[path()] = Node(true); FsResponse() }
                "fs/writeFile" -> { nodes[path()] = Node(false, params.getValue("dataBase64").toString()); FsResponse() }
                else -> error("Invented method $method")
            }
            afterCall?.invoke()
            return result
        }
        fun mutations() = calls.filter { it.first in setOf("fs/copy", "fs/remove", "fs/writeFile", "fs/createDirectory") }
    }
    private fun entry(path: String, directory: Boolean = false) = FileEntry(FilePaths.name(path), FilePaths.uri(path), directory, !directory)
    private fun ops(tree: Tree, current: () -> FileSession? = { session }) = ExecutorFileOps(session, current, tree)
    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block(); fail("Expected failure") } catch (e: Exception) { return e }
        error("unreachable")
    }

    @Test fun uriNamesRoundTripWithoutWorkspaceFenceOrDoubleEncoding() {
        val path = "/storage/emulated/0/Download/.hidden # ü%file.txt"
        val uri = FilePaths.uri(path)
        assertEquals(path, FilePaths.path(uri))
        assertEquals(uri, FilePaths.uri(uri))
        assertEquals(uri, FilePaths.child("/storage/emulated/0/Download", ".hidden # ü%file.txt"))
        assertEquals("/home/name ", FilePaths.path(FilePaths.uri("/home/name ")))
        assertEquals("file:///home/%20%20", FilePaths.child("/home", "  "))
        assertEquals("file:///", FilePaths.parent("/"))
        assertFalse(FilePaths.isWithin("/home/other-project", "/home/other"))
    }
    @Test fun namesCannotReplaceTheParentOrUseRelativePaths() {
        for (name in listOf(".", "..", "folder/child", "bad\u0000name")) {
            try { FilePaths.child("/home", name); fail(name) } catch (_: IllegalArgumentException) {}
        }
        try { FilePaths.uri("relative/path"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun dotSegmentsReachNativeCanonicalizationWithoutLexicalNormalization() = runBlocking {
        val tree = Tree().apply { aliases["/a/link/../x "] = "/b/x " }
        val input = "file:///a/link/%2E%2E/x%20"
        assertEquals("/a/link/../x ", FilePaths.path(FilePaths.uri(input)))
        assertEquals("file:///b/x%20", ops(tree).canonical(input))
        assertEquals("file:///a/link/../x%20", tree.calls.single().second["path"])
    }
    @Test fun missingSettingsParentsAreReadWithoutCreatingAnything() = runBlocking {
        val tree = Tree()
        assertNull(ops(tree).readOptionalFile("/home", listOf(".local", "state", "workspace.json")))
        assertTrue(tree.mutations().isEmpty())
        assertEquals(listOf("fs/readDirectory"), tree.calls.map { it.first })
    }
    @Test fun existingSettingsAreReadWithoutWritingAnything() = runBlocking {
        val tree = Tree().apply { directory("/home/.config"); file("/home/.config/workspace.json", "setting".toByteArray()) }
        assertEquals(tree.nodes["/home/.config/workspace.json"]!!.bytes,
            ops(tree).readOptionalFile("/home", listOf(".config", "workspace.json")))
        assertTrue(tree.mutations().isEmpty())
    }

    @Test fun unknownDirectoryEntryIsResolvedUsingMetadataBeforeOpen() = runBlocking {
        val tree = Tree().apply { directory("/home/storage") }
        val unknown = FileEntry("storage", "file:///home/storage", false, false)
        val resolved = ops(tree).resolve(unknown)
        assertTrue(resolved.directory)
        assertFalse(resolved.file)
        assertEquals(listOf("fs/getMetadata"), tree.calls.map { it.first })
        assertTrue(tree.mutations().isEmpty())
    }
    @Test fun binaryCopyUsesNativeSchemaAndNeverRemovesSource() = runBlocking {
        val tree = Tree().apply { file("/home/binary") }
        ops(tree).transfer(FileClipboard(entry("/home/binary"), false, session), "/Downloads")
        assertEquals(tree.nodes["/home/binary"], tree.nodes["/Downloads/binary"])
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })
        assertEquals(mapOf("sourcePath" to "file:///home/binary", "destinationPath" to "file:///Downloads/binary", "recursive" to false), tree.mutations().single().second)
    }
    @Test fun recursiveCutVerifiesHiddenFilesBeforeRemovingSource() = runBlocking {
        val tree = Tree().apply { directory("/home/folder"); directory("/home/folder/sub"); file("/home/folder/.hidden"); file("/home/folder/sub/data") }
        ops(tree).transfer(FileClipboard(entry("/home/folder", true), true, session), "/Downloads")
        assertFalse(tree.nodes.containsKey("/home/folder"))
        assertTrue(tree.nodes.containsKey("/Downloads/folder/.hidden"))
        assertTrue(tree.nodes.containsKey("/Downloads/folder/sub/data"))
        val remove = tree.calls.indexOfFirst { it.first == "fs/remove" }
        assertTrue(tree.calls.take(remove).any { it.first == "fs/readFile" && it.second["path"] == "file:///Downloads/folder/sub/data" })
        assertEquals(listOf("fs/copy", "fs/remove"), tree.mutations().map { it.first })
    }
    @Test fun corruptionPreventsSourceRemoval() = runBlocking {
        val tree = Tree().apply { file("/home/data"); corruptCopy = true }
        assertTrue(failure { ops(tree).transfer(FileClipboard(entry("/home/data"), true, session), "/Downloads") }.message!!.contains("bytes differ"))
        assertTrue(tree.nodes.containsKey("/home/data"))
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })
    }
    @Test fun appliedCopyWithLostReplyRetainsSourceAndNeverReplays() = runBlocking {
        val tree = Tree().apply { file("/home/data"); loseCopyReply = true }
        failure { ops(tree).transfer(FileClipboard(entry("/home/data"), true, session), "/Downloads") }
        assertEquals(tree.nodes["/home/data"], tree.nodes["/Downloads/data"])
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })
    }
    @Test fun partialDirectoryReplyLossSurvivesJournalRestoreAndPresenceInspection() = runBlocking {
        val tree = Tree().apply {
            directory("/home/folder"); file("/home/folder/.hidden"); file("/home/folder/remaining"); partialCopyReplyLoss = true
        }
        var stored = FileOperationHistory()
        fun journal() = FileOperationJournal({ stored }, { stored = it })
        val original = journal().apply { restore() }
        var pending = FilesOperation("partial-folder-id", session.endpoint, "file:///home/folder", "file:///Downloads/folder",
            "Copy requested · outcome not yet verified", true)
        val trackedRpc = FileRpc { method, params ->
            if (method == "fs/copy") {
                pending = pending.copy(requests = listOf(FileMutationIntent(1, method, params.getValue("sourcePath").toString(), params.getValue("destinationPath").toString())))
                original.record(pending)
            }
            tree.request(method, params)
        }
        val error = failure { ExecutorFileOps(session, { session }, trackedRpc).transfer(FileClipboard(entry("/home/folder", true), true, session), "/Downloads") }
        original.record(pending.copy(lastError = error.message))
        assertTrue(tree.nodes.containsKey("/home/folder/remaining"))
        assertTrue(tree.nodes.containsKey("/Downloads/folder/.hidden"))
        assertFalse(tree.nodes.containsKey("/Downloads/folder/remaining"))
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })

        val restored = journal().apply { restore() }
        val readOnly = ops(tree)
        val sourcePresent = readOnly.list("/home").any { it.name == "folder" }
        val destinationPresent = readOnly.list("/Downloads").any { it.name == "folder" }
        restored.observe(pending.id, "source $sourcePresent, destination $destinationPresent", 2)
        assertTrue(restored.history.receipts.getValue(pending.id).requiresInspection)
        assertFalse(restored.history.receipts.getValue(pending.id).verified)
        assertNotNull(restored.history.receipts.getValue(pending.id).lastError)
        failure { restored.requireMutationAllowed() }
        val afterInspectionRestart = journal().apply { restore() }
        failure { afterInspectionRestart.requireMutationAllowed() }
        afterInspectionRestart.resolve(pending.id, "Inspected partial destination and retained source; will repair manually.", 3)
        afterInspectionRestart.requireMutationAllowed()
        assertFalse(afterInspectionRestart.history.receipts.getValue(pending.id).verified)
        assertEquals(pending.phase, afterInspectionRestart.history.receipts.getValue(pending.id).phase)
        afterInspectionRestart.record(FilesOperation("later-id", session.endpoint, "/later", "/later", "Completed and verified", false, verified = true))
        val laterRestart = journal().apply { restore() }
        assertEquals("later-id", laterRestart.history.lastId)
        assertEquals(setOf(pending.id, "later-id"), laterRestart.history.receipts.keys)
        assertEquals(3L, laterRestart.history.receipts.getValue(pending.id).resolution!!.recordedAt)
        assertEquals(1, laterRestart.history.receipts.getValue(pending.id).observations.size)
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })
    }
    @Test fun appliedRemovalWithLostReplyLeavesDestinationAndDoesNotRepeat() = runBlocking {
        val tree = Tree().apply { file("/home/data"); loseRemoveReply = true }
        failure { ops(tree).transfer(FileClipboard(entry("/home/data"), true, session), "/Downloads") }
        assertFalse(tree.nodes.containsKey("/home/data"))
        assertTrue(tree.nodes.containsKey("/Downloads/data"))
        assertEquals(listOf("fs/copy", "fs/remove"), tree.mutations().map { it.first })
    }
    @Test fun existingDestinationIsNeverSentToOverwritingCopy() = runBlocking {
        val tree = Tree().apply { file("/home/data"); file("/Downloads/data", byteArrayOf(99)) }
        val original = tree.nodes.getValue("/Downloads/data")
        assertTrue(failure { ops(tree).transfer(FileClipboard(entry("/home/data"), false, session), "/Downloads") }.message!!.contains("already exists"))
        assertEquals(original, tree.nodes["/Downloads/data"])
        assertTrue(tree.mutations().isEmpty())
    }
    @Test fun canonicalDescendantThroughDirectoryAliasIsRejected() = runBlocking {
        val tree = Tree().apply { directory("/home/folder"); directory("/home/folder/sub"); aliases["/Downloads/link"] = "/home/folder/sub" }
        failure { ops(tree).transfer(FileClipboard(entry("/home/folder", true), true, session), "/Downloads/link") }
        assertTrue(tree.mutations().isEmpty())
    }
    @Test fun staleClipboardAcrossGenerationCannotSendARequest() = runBlocking {
        val tree = Tree().apply { file("/home/data") }
        failure { ops(tree).transfer(FileClipboard(entry("/home/data"), true, session.copy(generation = 6)), "/Downloads") }
        assertTrue(tree.calls.isEmpty())
    }
    @Test fun sessionChangesDuringCopyDoNotRemoveTheSource() = runBlocking {
        val tree = Tree().apply { file("/home/data") }
        var current = session
        tree.afterCall = { if (tree.calls.last().first == "fs/copy") current = session.copy(sessionId = "two") }
        failure { ops(tree) { current }.transfer(FileClipboard(entry("/home/data"), true, session), "/Downloads") }
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })
        assertTrue(tree.nodes.containsKey("/home/data"))
    }
    @Test fun unsupportedOrLostCopyResponseIsNotRetriedOrReplacedWithWrites() = runBlocking {
        val tree = Tree().apply { file("/home/data"); failCopy = true }
        failure { ops(tree).transfer(FileClipboard(entry("/home/data"), true, session), "/Downloads") }
        assertEquals(listOf("fs/copy"), tree.mutations().map { it.first })
        assertTrue(tree.nodes.containsKey("/home/data"))
    }
    @Test fun failedRemovalLeavesTheVerifiedCopyAndIsNotRetried() = runBlocking {
        val tree = Tree().apply { file("/home/data"); failRemove = true }
        failure { ops(tree).transfer(FileClipboard(entry("/home/data"), true, session), "/Downloads") }
        assertEquals(tree.nodes["/home/data"], tree.nodes["/Downloads/data"])
        assertEquals(listOf("fs/copy", "fs/remove"), tree.mutations().map { it.first })
    }
    @Test fun renameUsesCopyVerificationThenRemovalInSameParent() = runBlocking {
        val tree = Tree().apply { file("/home/old") }
        assertEquals("file:///home/new", ops(tree).transfer(FileClipboard(entry("/home/old"), true, session), "/home", "new"))
        assertFalse(tree.nodes.containsKey("/home/old"))
        assertTrue(tree.nodes.containsKey("/home/new"))
    }
    @Test fun deleteDirectoryUsesRecursiveRemoveAndChecksParent() = runBlocking {
        val tree = Tree().apply { directory("/home/folder"); file("/home/folder/.hidden") }
        ops(tree).delete(entry("/home/folder", true))
        assertEquals(mapOf("path" to "file:///home/folder", "recursive" to true), tree.mutations().single().second)
        assertFalse(tree.nodes.containsKey("/home/folder/.hidden"))
    }
    @Test fun createRefusesExistingNameAndVerifiesNewEntries() = runBlocking {
        val tree = Tree().apply { file("/home/existing") }
        failure { ops(tree).create("/home", "existing", false) }
        assertTrue(tree.mutations().isEmpty())
        ops(tree).create("/home", ".new", true)
        assertTrue(tree.nodes.getValue("/home/.new").directory)
        assertEquals(listOf("fs/createDirectory"), tree.mutations().map { it.first })
    }
}
