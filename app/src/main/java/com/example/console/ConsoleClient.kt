package com.example.console

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

typealias JsonObject = Map<String, Any?>

object ConsoleJson {
    private val adapter = Moshi.Builder().build().adapter<JsonObject>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    fun read(text: String): JsonObject = adapter.fromJson(text)
        ?: throw IOException("Expected a JSON object")
    fun write(value: JsonObject): String = adapter.toJson(value)
    fun writeIncludingNulls(value: JsonObject): String = adapter.serializeNulls().toJson(value)
}

@Suppress("UNCHECKED_CAST")
fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: emptyMap()
fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? List<*>)?.mapNotNull { @Suppress("UNCHECKED_CAST") (it as? JsonObject) } ?: emptyList()
fun JsonObject.text(key: String): String = this[key]?.toString().orEmpty()
fun JsonObject.number(key: String): Long? = (this[key] as? Number)?.toLong()

class ConsoleHttpException(
    val status: Int,
    val payload: JsonObject,
    message: String
) : IOException(message)

data class Assignment(
    val role: String,
    val taskId: String,
    val scope: List<String>,
    val acceptance: String,
    val instruction: String,
    val dependencies: List<String>
) {
    fun payload(): JsonObject {
        require(role.isNotBlank() && taskId.isNotBlank()) { "Role and task ID are required" }
        require(scope.isNotEmpty() && scope.all { it.isNotBlank() }) { "Enter at least one scope item" }
        require(acceptance.isNotBlank() && instruction.isNotBlank()) { "Acceptance and instruction are required" }
        return linkedMapOf(
            "role" to role, "taskId" to taskId, "owner" to role,
            "scope" to scope, "acceptance" to acceptance, "nextAction" to instruction,
            "dependencies" to dependencies
        )
    }
}

data class EventPage(val events: List<JsonObject>, val cursor: Long, val hasMore: Boolean, val errors: List<JsonObject> = emptyList())

/** A fresh HTTP call per explicit operation. Mutations are never retried or redirected. */
class ConsoleClient(
    endpoint: String,
    private val http: OkHttpClient = defaultHttp
) {
    val endpoint: String = normalizeEndpoint(endpoint)
    private val base = this.endpoint.toHttpUrl()

    companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:8792"
        private val defaultHttp = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
        fun normalizeEndpoint(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty()) { "Console URL cannot contain credentials" }
            require(url.query == null && url.fragment == null) { "Console URL cannot contain query or fragment" }
            return url.toString().trimEnd('/')
        }
    }

    private suspend fun request(
        method: String, path: List<String>, body: JsonObject? = null,
        query: Map<String, String> = emptyMap(), operationId: String? = null
    ): JsonObject {
        val url = base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val jsonBody = body?.let { ConsoleJson.write(it).toRequestBody("application/json; charset=utf-8".toMediaType()) }
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .apply { operationId?.let { header("Idempotency-Key", it) } }
            .method(method, jsonBody).build())
        return withContext(Dispatchers.IO) {
            call.await().use { response ->
                val raw = response.body?.string() ?: throw IOException("HTTP ${response.code}: empty response")
                val parsed = try {
                    ConsoleJson.read(raw)
                } catch (e: Exception) {
                    throw IOException("HTTP ${response.code}: invalid JSON response: $raw", e)
                }
                if (!response.isSuccessful || parsed["ok"] == false) {
                    val detail = parsed.obj("error").text("message").ifBlank { parsed.text("error") }
                    throw ConsoleHttpException(response.code, parsed, "HTTP ${response.code}: $detail")
                }
                parsed
            }
        }
    }

    suspend fun health(): JsonObject {
        val response = request("GET", listOf("v1", "health"))
        require(response.text("protocol") == "goal-console/1") { "Endpoint is not a goal-console/1 service" }
        return response
    }
    suspend fun sendCeo(messageId: String, threadId: String, text: String, operationId: String? = null): JsonObject =
        request("POST", listOf("v1", "ceo", "inbox"), mapOf(
            "messageId" to messageId, "threadId" to threadId, "text" to text, "source" to "apk.text"
        ), operationId = operationId)
    suspend fun ceoReceipt(messageId: String): JsonObject =
        request("GET", listOf("v1", "ceo", "inbox", messageId))
    suspend fun actionReceipt(operationId: String): JsonObject =
        request("GET", listOf("v1", "actions", operationId))
    suspend fun console(): JsonObject = request("GET", listOf("v1", "console"))
    suspend fun nativeTasks(taskId: String? = null): JsonObject =
        request("GET", listOf("v1", "team", "tasks"), query = taskId?.let { mapOf("taskId" to it) } ?: emptyMap())
    suspend fun ledger(): JsonObject = request("GET", listOf("v1", "tasks"))
    suspend fun assign(value: Assignment, operationId: String? = null): JsonObject =
        request("POST", listOf("v1", "team", "assign"), value.payload(), operationId = operationId)
    suspend fun goal(threadId: String, status: String, operationId: String? = null): JsonObject {
        require(status in listOf("active", "paused"))
        return request("POST", listOf("v1", "goals", "action"), mapOf("threadId" to threadId, "status" to status), operationId = operationId)
    }
    suspend fun runtime(role: String, action: String, operationId: String? = null): JsonObject {
        require(action in listOf("up", "restart", "stop"))
        return request("POST", listOf("v1", "team", role, "runtime"), mapOf("action" to action), operationId = operationId)
    }
    suspend fun connections(): JsonObject = request("GET", listOf("v1", "connections"))
    suspend fun addConnection(value: JsonObject, operationId: String? = null): JsonObject = request("POST", listOf("v1", "connections"), value, operationId = operationId)
    suspend fun testConnection(id: String, operationId: String? = null): JsonObject = request("POST", listOf("v1", "connections", id, "test"), emptyMap(), operationId = operationId)
    suspend fun removeConnection(id: String, operationId: String? = null): JsonObject = request("DELETE", listOf("v1", "connections", id), operationId = operationId)
    suspend fun events(after: Long, wait: Int = 0, limit: Int = 100): EventPage {
        val value = request("GET", listOf("v1", "events"), query = mapOf(
            "after" to after.toString(), "wait" to wait.toString(), "limit" to limit.toString()
        ))
        val cursor = value.number("cursor") ?: throw IOException("Event response is missing cursor")
        val events = value.objects("events")
        require(cursor >= after) { "Event cursor moved backwards; retained cursor was not changed" }
        require(events.all { (it.number("seq") ?: -1) in (after + 1)..cursor }) { "Invalid event sequence" }
        require(events.zipWithNext().all { (a, b) -> a.number("seq")!! < b.number("seq")!! }) { "Events are not ordered" }
        require(events.isNotEmpty() || cursor == after) { "Event page skipped unseen records" }
        return EventPage(events, cursor, value["hasMore"] == true, value.objects("errors"))
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, closed, _ -> closed.close() }
        }
    })
}
