package com.example.files

data class FileOperationObservation(val observedAt: Long, val text: String)
data class FileOperationResolution(val recordedAt: Long, val note: String)
data class FileMutationIntent(val requestedAt: Long, val method: String, val source: String, val destination: String)
data class FilesOperation(
    val id: String, val endpoint: String, val source: String, val destination: String,
    val phase: String, val requiresInspection: Boolean,
    val verified: Boolean = false,
    val lastError: String? = null,
    val requests: List<FileMutationIntent> = emptyList(),
    val observations: List<FileOperationObservation> = emptyList(),
    val resolution: FileOperationResolution? = null
)

/** One atomic stored value contains receipts keyed by ID and the last-operation pointer. */
data class FileOperationHistory(
    val lastId: String? = null,
    val receipts: Map<String, FilesOperation> = emptyMap()
)

class FileOperationJournal(
    private val load: suspend () -> FileOperationHistory,
    private val save: suspend (FileOperationHistory) -> Unit
) {
    var history = FileOperationHistory()
        private set

    suspend fun restore() { history = load() }

    fun requireMutationAllowed() {
        check(history.receipts.values.none { it.requiresInspection }) {
            "An earlier operation remains unresolved. Inspect it and record a manual resolution before another write."
        }
    }

    suspend fun record(operation: FilesOperation, makeLast: Boolean = true) {
        val next = FileOperationHistory(
            if (makeLast) operation.id else history.lastId,
            history.receipts + (operation.id to operation)
        )
        save(next) // durable keyed receipt and pointer precede dispatch or UI acknowledgement
        history = next
    }

    suspend fun observe(id: String, text: String, observedAt: Long) {
        val operation = history.receipts.getValue(id)
        record(operation.copy(observations = operation.observations + FileOperationObservation(observedAt, text)), false)
    }

    suspend fun resolve(id: String, note: String, recordedAt: Long) {
        val operation = history.receipts.getValue(id)
        check(operation.requiresInspection && operation.observations.isNotEmpty()) {
            "Inspect this unresolved operation before recording a manual resolution"
        }
        require(note.isNotBlank()) { "Describe the manual resolution" }
        record(operation.copy(
            requiresInspection = false,
            resolution = FileOperationResolution(recordedAt, note)
        ), false)
        // Acknowledgement does not change phase, errors, requests or verified outcome.
    }
}
