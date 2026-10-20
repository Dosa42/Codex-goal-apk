package com.example.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.db.OutputChunkEntity

class TerminalJsBridge(
    private val onInputCallback: (String) -> Unit,
    private val onReadyCallback: () -> Unit
) {
    @JavascriptInterface
    fun onData(base64: String) {
        onInputCallback(base64)
    }

    @JavascriptInterface
    fun onReady() {
        onReadyCallback()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    processId: String,
    chunks: List<OutputChunkEntity>,
    onInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isTerminalReady by remember { mutableStateOf(false) }
    var lastProcessId by remember { mutableStateOf<String?>(null) }
    var lastWrittenSeq by remember { mutableStateOf<Long?>(-1L) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = true

                addJavascriptInterface(
                    TerminalJsBridge(
                        onInputCallback = onInput,
                        onReadyCallback = {
                            isTerminalReady = true
                        }
                    ),
                    "AndroidBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isTerminalReady = true
                    }
                }

                loadUrl("file:///android_asset/terminal/index.html")
                webViewRef = this
            }
        },
        update = { webView ->
            webViewRef = webView
        },
        modifier = modifier.fillMaxSize()
    )

    // Key output rendering by processId and ordered chunks
    LaunchedEffect(processId, chunks, isTerminalReady) {
        val webView = webViewRef
        if (webView != null && isTerminalReady) {
            val sortedChunks = chunks.sortedBy { it.seq }

            // If process switched, reset terminal buffer and replay
            if (lastProcessId != processId) {
                lastProcessId = processId
                lastWrittenSeq = -1L
                webView.evaluateJavascript("setProcessContext('$processId');", null)
            }

            // Check if missing gap chunks arrived out of order (e.g. seq 2 arrived after seq 3)
            val hasGapsRepaired = sortedChunks.any { it.seq <= (lastWrittenSeq ?: -1L) && sortedChunks.indexOf(it) > 0 }

            if (lastWrittenSeq == -1L || hasGapsRepaired) {
                // Replay complete ordered history
                webView.evaluateJavascript("setProcessContext('$processId');", null)
                for (chunk in sortedChunks) {
                    val script = "writeChunkBase64('${chunk.chunkBase64}', '${chunk.stream}');"
                    webView.evaluateJavascript(script, null)
                }
                lastWrittenSeq = sortedChunks.lastOrNull()?.seq ?: -1L
            } else {
                // Incrementally append new chunks
                for (chunk in sortedChunks) {
                    if (chunk.seq > (lastWrittenSeq ?: -1L)) {
                        val script = "writeChunkBase64('${chunk.chunkBase64}', '${chunk.stream}');"
                        webView.evaluateJavascript(script, null)
                        lastWrittenSeq = chunk.seq
                    }
                }
            }
        }
    }
}
