package com.example.files

import java.net.URI

/** Executor paths only: no Android storage identity or workspace boundary. */
object FilePaths {
    fun uri(value: String): String {
        val parsed = if (value.startsWith('/')) URI("file", "", value, null) else URI(value)
        require(parsed.scheme == "file" && parsed.host.orEmpty() in listOf("", "localhost") &&
            parsed.path?.startsWith('/') == true && parsed.query == null && parsed.fragment == null && '\u0000' !in parsed.path.orEmpty()) {
            "Enter an absolute executor path or file URI"
        }
        return URI("file", "", parsed.path, null).toASCIIString()
    }
    fun path(value: String): String = URI(uri(value)).path
    fun name(value: String): String = path(value).trimEnd('/').substringAfterLast('/')
    fun child(parent: String, name: String): String {
        require(name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name && '\u0000' !in name) { "Enter one file or folder name" }
        return uri(path(parent).trimEnd('/') + "/" + name)
    }
    fun parent(value: String): String = uri(path(value).trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" })
    fun isWithin(candidate: String, directory: String): Boolean {
        val root = path(directory).trimEnd('/')
        val path = path(candidate).trimEnd('/')
        return path == root || path.startsWith("$root/")
    }
}

data class FileSession(val endpoint: String, val sessionId: String, val generation: Long)
data class FileEntry(val name: String, val uri: String, val directory: Boolean, val file: Boolean)
data class FileClipboard(val entry: FileEntry, val cut: Boolean, val session: FileSession)
data class FsResponse(
    val path: String? = null, val entries: List<FileEntry> = emptyList(), val dataBase64: String? = null,
    val directory: Boolean = false, val file: Boolean = false, val symlink: Boolean = false
)
fun interface FileRpc { suspend fun request(method: String, params: Map<String, Any>): FsResponse }

/** One captured connection, no retry/fallback, and no source deletion before verification. */
class ExecutorFileOps(
    private val session: FileSession,
    private val currentSession: () -> FileSession?,
    private val rpc: FileRpc
) {
    private suspend fun call(method: String, params: Map<String, Any>): FsResponse {
        check(currentSession() == session) { "Executor session changed; inspect the operation before retrying" }
        val response = rpc.request(method, params)
        check(currentSession() == session) { "Executor session changed; inspect the operation before retrying" }
        return response
    }
    suspend fun canonical(path: String): String = FilePaths.uri(requireNotNull(call("fs/canonicalize", mapOf("path" to FilePaths.uri(path))).path))
    suspend fun list(path: String): List<FileEntry> = call("fs/readDirectory", mapOf("path" to FilePaths.uri(path))).entries
        .sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
    private suspend fun metadata(path: String) = call("fs/getMetadata", mapOf("path" to path))
    suspend fun resolve(entry: FileEntry): FileEntry {
        val info = metadata(FilePaths.uri(entry.uri))
        return entry.copy(directory = info.directory, file = info.file)
    }
    /** Walk an existing settings path without creating missing parents or files. */
    suspend fun readOptionalFile(root: String, components: List<String>): String? {
        require(components.isNotEmpty())
        var parent = FilePaths.uri(root)
        components.forEachIndexed { index, name ->
            FilePaths.child(parent, name) // validate a single component
            val entry = list(parent).find { it.name == name } ?: return null
            val actual = resolve(entry)
            if (index == components.lastIndex) {
                check(actual.file) { "Workspace setting is not a regular file" }
                return requireNotNull(call("fs/readFile", mapOf("path" to actual.uri)).dataBase64)
            }
            check(actual.directory) { "Workspace settings parent is not a folder: $name" }
            parent = actual.uri
        }
        return null
    }
    private suspend fun missing(parent: String, name: String) {
        check(list(parent).none { it.name == name }) { "Destination already exists: $name. Choose another name; existing entries are not replaced." }
    }
    suspend fun create(parent: String, name: String, directory: Boolean): String {
        val destination = FilePaths.child(parent, name)
        missing(parent, name)
        if (directory) call("fs/createDirectory", mapOf("path" to destination, "recursive" to false))
        else call("fs/writeFile", mapOf("path" to destination, "dataBase64" to ""))
        val info = metadata(destination)
        check(if (directory) info.directory else info.file) { "Created entry could not be verified; inspect it before retrying" }
        return destination
    }
    suspend fun transfer(clipboard: FileClipboard, parent: String, name: String = clipboard.entry.name): String {
        check(clipboard.session == session) { "Clipboard belongs to another executor connection. Select Copy or Cut again." }
        val parentCanonical = canonical(parent)
        val destination = FilePaths.child(parentCanonical, name)
        val source = FilePaths.uri(clipboard.entry.uri)
        val sourceCanonical = canonical(source)
        val info = metadata(source)
        check(info.file || info.directory) { "Selected entry is not a regular file or folder" }
        check(sourceCanonical != destination && (!info.directory || !FilePaths.isWithin(destination, sourceCanonical))) {
            "A folder cannot be copied or moved into itself or a descendant"
        }
        missing(parentCanonical, name)
        call("fs/copy", mapOf("sourcePath" to source, "destinationPath" to destination, "recursive" to info.directory))
        verify(source, destination, mutableSetOf())
        if (clipboard.cut) call("fs/remove", mapOf("path" to source, "recursive" to info.directory))
        if (clipboard.cut) check(list(FilePaths.parent(source)).none { it.name == FilePaths.name(source) }) {
            "Destination verified, but source removal was not verified; inspect both locations"
        }
        return destination
    }
    private suspend fun verify(source: String, destination: String, ancestors: MutableSet<String>) {
        val from = metadata(source)
        val to = metadata(destination)
        check(from.file == to.file && from.directory == to.directory) { "Copied entry type differs; source retained" }
        if (from.file) {
            check(requireNotNull(call("fs/readFile", mapOf("path" to source)).dataBase64) == requireNotNull(call("fs/readFile", mapOf("path" to destination)).dataBase64)) {
                "Copied bytes differ; source retained"
            }
        } else if (from.directory) {
            val canonical = canonical(source)
            check(ancestors.add(canonical)) { "Cannot verify a directory link cycle; source retained" }
            try {
                val sourceEntries = list(source)
                val targetEntries = list(destination)
                check(sourceEntries.map { it.name }.toSet() == targetEntries.map { it.name }.toSet()) { "Copied directory entries differ; source retained" }
                sourceEntries.forEach { verify(it.uri, FilePaths.child(destination, it.name), ancestors) }
            } finally { ancestors.remove(canonical) }
        } else error("Cannot verify this entry type; source retained")
    }
    suspend fun delete(entry: FileEntry) {
        val actual = resolve(entry)
        call("fs/remove", mapOf("path" to FilePaths.uri(entry.uri), "recursive" to actual.directory))
        check(list(FilePaths.parent(entry.uri)).none { it.name == entry.name }) { "Removal was not verified; inspect the folder" }
    }
}
