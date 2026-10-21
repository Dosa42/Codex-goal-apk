package com.example.console

import kotlinx.coroutines.suspendCancellableCoroutine
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
    val requiredComponentIds = setOf("codex-goal-apk", "native-hub", "meta-hub", "codex-browser-agent")
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
        require(value["startupKits"] is List<*>) { "Infrastructure descriptor has no startupKits array" }
        val ids = mutableSetOf<String>()
        for (entry in value["components"] as List<*>) {
            require(entry is Map<*, *>) { "Infrastructure component must be an object" }
            val id = entry["id"]
            require(id is String && id.isNotBlank()) { "Infrastructure component has no ID" }
            require(ids.add(id)) { "Duplicate infrastructure component ID: $id" }
            require(entry["name"] is String && (entry["name"] as String).isNotBlank()) { "$id has no name" }
            require(entry["source"] is Map<*, *>) { "$id.source must be an object" }
            for (state in listOf("source", "packaged", "installed")) {
                require(entry.containsKey(state)) { "$id.$state is missing" }
                val observation = entry[state]
                require(observation == null || observation is Map<*, *>) { "$id.$state must be an object or null" }
                if (observation is Map<*, *>) require(
                    observation["version"] is String && (observation["version"] as String).isNotBlank()
                ) { "$id.$state.version must be a nonempty string" }
            }
        }
        require(ids.containsAll(requiredComponentIds)) { "Missing canonical component IDs: ${requiredComponentIds - ids}" }
        for (id in defaultUrls.keys) {
            val connection = value.obj("connections")[id]
            require(connection is Map<*, *>) { "Missing infrastructure connection: $id" }
            val url = connection["url"]
            val protocol = connection["protocol"]
            require(url is String && protocol is String && protocol.isNotBlank()) { "$id requires URL and protocol strings" }
            val uri = java.net.URI(url)
            val schemes = if (id in listOf("nativeCodex", "execServer")) listOf("ws", "wss") else listOf("http", "https")
            require(uri.scheme in schemes && !uri.host.isNullOrBlank()) { "$id requires a ${schemes.joinToString("/")} URL" }
        }
        defaultBinding(value, "goalConsole")
        defaultBinding(value, "execServer")
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
                require(protocol == "exec-server-jsonrpc") { "execServer protocol mismatch: $protocol" }
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

    suspend fun readJson(url: String): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
        // Keep cancellation attached while headers AND the complete body are in flight.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val parsed = response.use {
                        val raw = response.body?.string() ?: throw IOException("HTTP ${response.code}: empty response")
                        val body = try { ConsoleJson.read(raw) }
                        catch (e: Exception) { throw IOException("HTTP ${response.code}: invalid JSON: $raw", e) }
                        if (!response.isSuccessful || body["ok"] == false) {
                            val detail = body.obj("error").text("message").ifBlank { body.text("error").ifBlank { raw } }
                            throw ConsoleHttpException(response.code, body, "HTTP ${response.code}: $detail")
                        }
                        body
                    }
                    if (!continuation.isCancelled) continuation.resume(parsed)
                } catch (e: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        })
    }
}
