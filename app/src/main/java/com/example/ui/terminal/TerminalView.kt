package com.example.ui.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.db.OutputChunkEntity
import org.json.JSONArray
import org.json.JSONObject

class TerminalJsBridge(
    private val input: (String) -> Unit,
    private val ready: () -> Unit,
    private val error: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun onData(base64: String) { main.post { input(base64) } }
    @JavascriptInterface fun onReady() { main.post { ready() } }
    @JavascriptInterface fun onError(message: String) { main.post { error(message) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    processId: String,
    chunks: List<OutputChunkEntity>,
    onInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentInput by rememberUpdatedState(onInput)
    val context = LocalContext.current
    key(processId) {
        var ready by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var disposed by remember { mutableStateOf(false) }
        val view = remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(TerminalJsBridge(
                    input = { if (!disposed) currentInput(it) },
                    ready = { if (!disposed) ready = true },
                    error = { if (!disposed) error = it }
                ), "AndroidBridge")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) error = message.message()
                        return true
                    }
                }
                loadUrl("file:///android_asset/terminal/index.html")
            }
        }
        DisposableEffect(view) {
            onDispose {
                disposed = true
                view.removeJavascriptInterface("AndroidBridge")
                view.stopLoading()
                view.destroy()
            }
        }
        LaunchedEffect(chunks, ready) {
            if (ready && !disposed) {
                val batch = JSONArray()
                for (chunk in chunks.sortedBy { it.seq }) {
                    batch.put(JSONObject().apply {
                        put("seq", chunk.seq); put("chunkBase64", chunk.chunkBase64)
                    })
                }
                view.evaluateJavascript("receiveChunks(" + JSONObject.quote(processId) + "," + batch.toString() + ");", null)
            }
        }
        Box(modifier.fillMaxSize()) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            error?.let { Text("Terminal error: $it") }
        }
    }
}
