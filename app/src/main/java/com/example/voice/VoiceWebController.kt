package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class VoiceRoomState(
    val ready: Boolean = false,
    val starting: Boolean = false,
    val micOpen: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null
)

@SuppressLint("SetJavaScriptEnabled")
internal class VoiceWebController(
    context: Context,
    private val sourceUrl: String,
    private val requestNativePermission: () -> Unit,
    private val onState: (VoiceRoomState) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val gate = VoiceCaptureGate()
    private var disposed = false
    private var state = VoiceRoomState()
    private var permissionPending = false
    private val appContext = context.applicationContext

    private fun publish(next: VoiceRoomState) {
        if (!disposed) { state = next; onState(next) }
    }

    private fun fail(message: String) { publish(state.copy(starting = false, error = message)) }

    private fun hasPermission() = ContextCompat.checkSelfPermission(appContext,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Native Start is the explicit gesture. Actual playback still requires the room to be running.
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(object {
            @JavascriptInterface fun start() { main.post { requestStart() } }
            @JavascriptInterface fun stop() { main.post { this@VoiceWebController.stop() } }
            @JavascriptInterface fun error(message: String) { main.post { fail(message) } }
            @JavascriptInterface fun status(json: String) {
                main.post {
                    if (disposed) return@post
                    runCatching {
                        val value = JSONObject(json)
                        publish(state.copy(
                            ready = value.optBoolean("ready"),
                            starting = value.optBoolean("starting") ||
                                (state.starting && gate.requested && !value.optBoolean("running")),
                            micOpen = value.optBoolean("micOpen"),
                            connected = value.optBoolean("serviceConnected"),
                            error = value.optString("lastError").takeUnless { it.isBlank() || it == "null" } ?: state.error
                        ))
                    }.onFailure { fail("Voice status: ${it.message}") }
                }
            }
        }, "VoiceConsoleBridge")
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Only this explicit Start can grant the room its requested audio resource.
                if (!disposed && gate.canCapture(hasPermission()) &&
                    VoiceRoomAdapter.sameOrigin(sourceUrl, request.origin.toString()) &&
                    request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                    fail("Microphone access requires Start and Android microphone permission.")
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Document previews are subframes; the voice room remains the main page.
                return request.isForMainFrame && !VoiceRoomAdapter.sameOrigin(sourceUrl, request.url.toString())
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val requestUrl = request.url.toString()
                if (!VoiceRoomAdapter.sameOrigin(sourceUrl, requestUrl) || request.url.path != "/app.js") return null
                return try {
                    val connection = URL(requestUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.useCaches = false
                    try {
                        check(connection.responseCode == 200) { "Voice room script HTTP ${connection.responseCode}" }
                        val source = connection.inputStream.bufferedReader().use { it.readText() }
                        val adapted = VoiceRoomAdapter.adapt(source).toByteArray(Charsets.UTF_8)
                        WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(adapted))
                    } finally { connection.disconnect() }
                } catch (error: Exception) {
                    val message = "Voice room: ${error.message}"
                    main.post { fail(message) }
                    val script = "throw new Error(${JSONObject.quote(message)});"
                    WebResourceResponse("application/javascript", "UTF-8", 503, "Voice adapter unavailable",
                        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(script.toByteArray(Charsets.UTF_8)))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!gate.foreground) script("globalThis.voiceConsoleAdapter?.suspend()")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("Voice room: ${error.description}")
            }
        }
    }

    fun load() {
        val uri = runCatching { URI(sourceUrl) }.getOrNull()
        if (uri?.scheme !in listOf("http", "https") || uri?.host == null) {
            fail("Voice room URL must be an HTTP or HTTPS URL.")
            return
        }
        webView.loadUrl(sourceUrl)
    }

    fun requestStart() {
        if (disposed || !state.ready || !gate.requestStart()) return
        publish(state.copy(starting = true, error = null))
        if (hasPermission()) startRoom() else if (!permissionPending) {
            permissionPending = true
            runCatching { requestNativePermission() }.onFailure {
                permissionPending = false
                gate.stop()
                fail("Android microphone permission request: ${it.message}")
            }
        }
    }

    fun permissionResult(granted: Boolean) {
        permissionPending = false
        if (disposed || !gate.requested || !gate.foreground) return
        if (granted) startRoom() else {
            gate.stop()
            fail("Android microphone permission was denied. Choose Start to request it again.")
        }
    }

    private fun startRoom() {
        if (!gate.canCapture(hasPermission())) return
        script("(globalThis.voiceConsoleAdapter.resume(), globalThis.voiceConsoleAdapter.start())")
    }

    private fun script(expression: String) {
        if (disposed) return
        webView.evaluateJavascript("Promise.resolve().then(() => $expression).catch(e => VoiceConsoleBridge.error(String(e.message || e)));", null)
    }

    fun stop() {
        if (disposed) return
        gate.stop()
        publish(state.copy(starting = false))
        script("globalThis.voiceConsoleAdapter?.stop()")
    }

    fun suspend() {
        if (disposed) return
        gate.suspend()
        publish(state.copy(starting = false))
        script("globalThis.voiceConsoleAdapter?.suspend()")
        webView.onPause()
    }

    fun resume() {
        if (disposed) return
        gate.resume()
        webView.onResume()
        script("globalThis.voiceConsoleAdapter?.resume()")
    }

    fun destroy() {
        if (disposed) return
        gate.suspend()
        webView.evaluateJavascript("globalThis.voiceConsoleAdapter?.suspend();", null)
        disposed = true
        webView.removeJavascriptInterface("VoiceConsoleBridge")
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
