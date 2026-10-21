package com.example.console

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class InfrastructureObservation(
    val url: String = InfrastructureClient.DEFAULT_DISCOVERY,
    val descriptor: JsonObject = emptyMap(),
    val fetchedAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null,
    val stale: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null
)

object InfrastructureDescriptor {
    const val SCHEMA = "goal-infrastructure/1"
    val defaultUrls = linkedMapOf(
        "metaHub" to "http://127.0.0.1:8788",
        "proxy" to "http://127.0.0.1:8080",
        "nativeCodex" to "ws://127.0.0.1:4500",
        "execServer" to "ws://127.0.0.1:33995",
        "nativeHub" to "http://127.0.0.1:8789",
        "goalConsole" to ConsoleClient.DEFAULT_ENDPOINT,
        "voice" to "http://127.0.0.1:8790",
        "coordinator" to "http://127.0.0.1:8791"
    )

    fun validate(value: JsonObject): JsonObject {
        require(value.text("schema") == SCHEMA) { "Endpoint is not a $SCHEMA descriptor" }
        require(value["connections"] is Map<*, *>) { "Infrastructure descriptor has no connections object" }
        require(value["components"] is List<*>) { "Infrastructure descriptor has no components array" }
        return value
    }

    /** Only the named protocol binding is eligible; nativeCodex is never an executor fallback. */
    fun defaultBinding(value: JsonObject, id: String): String? {
        val connection = value.obj("connections").obj(id)
        if (connection.isEmpty()) return null
        val url = connection.text("url")
        val protocol = connection.text("protocol")
        when (id) {
            "goalConsole" -> {
                require(protocol == "goal-console/1") { "goalConsole protocol mismatch: $protocol" }
                return ConsoleClient.normalizeEndpoint(url)
            }
            "execServer" -> {
                require(protocol.contains("exec-server", ignoreCase = true)) { "execServer protocol mismatch: $protocol" }
                val uri = java.net.URI(url)
                require(uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank()) { "execServer requires a WebSocket URL" }
                return url
            }
            else -> return null
        }
    }

    /** Preserve advertised URLs, including full schema paths and query strings. */
    fun links(connection: JsonObject): Map<String, String> {
        val base = runCatching { connection.text("url").toHttpUrl() }.getOrNull()
        val result = linkedMapOf<String, String>()
        fun collect(value: JsonObject, prefix: String = "") {
            value.forEach { (key, entry) ->
                val label = if (prefix.isEmpty()) key else "$prefix.$key"
                if (entry is String && key != "protocol") {
                    val resolved = when {
                        entry.startsWith("http://") || entry.startsWith("https://") -> runCatching { entry.toHttpUrl() }.getOrNull()
                        entry.startsWith("/") -> base?.resolve(entry)
                        else -> null
                    }
                    if (resolved != null) result[label] = resolved.toString()
                } else if (entry is Map<*, *> && prefix.count { it == '.' } < 2) {
                    @Suppress("UNCHECKED_CAST")
                    collect(entry as JsonObject, label)
                }
            }
        }
        collect(connection)
        return result
    }
}

/** Descriptor and explicitly selected JSON links only; no model calls or connection fan-out. */
class InfrastructureClient(private val http: OkHttpClient = defaultHttp) {
    companion object {
        const val DEFAULT_DISCOVERY = "http://127.0.0.1:8788/.well-known/goal-infrastructure.json"
        private val defaultHttp = OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS).build()

        fun discoveryUrl(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty()) { "Discovery URL cannot contain credentials" }
            require(url.fragment == null) { "Discovery URL cannot contain a fragment" }
            return if (url.encodedPath == "/" && url.query == null)
                url.newBuilder().addPathSegments(".well-known/goal-infrastructure.json").build().toString()
            else url.toString()
        }
    }

    suspend fun discover(url: String): JsonObject =
        InfrastructureDescriptor.validate(readJson(discoveryUrl(url)))

    suspend fun readJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, resource, _ -> resource.close() }
                }
            })
        }
        response.use {
            val raw = response.body?.string() ?: throw IOException("HTTP ${response.code}: empty response")
            val parsed = try { ConsoleJson.read(raw) }
            catch (e: Exception) { throw IOException("HTTP ${response.code}: invalid JSON: $raw", e) }
            if (!response.isSuccessful || parsed["ok"] == false) {
                val detail = parsed.obj("error").text("message").ifBlank { parsed.text("error").ifBlank { raw } }
                throw ConsoleHttpException(response.code, parsed, "HTTP ${response.code}: $detail")
            }
            parsed
        }
    }
}
