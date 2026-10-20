package com.example.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Existing PCM/STT/TTS room. No binding, microphone request or playback is started on composition. */
@Composable
fun VoiceConsole(url: String = DEFAULT_VOICE_URL, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    key(url) {
        var state by remember { mutableStateOf(VoiceRoomState()) }
        val controllerRef = remember { arrayOfNulls<VoiceWebController>(1) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            controllerRef[0]?.permissionResult(granted)
        }
        val controller = remember(context, url) {
            VoiceWebController(context, url,
                requestNativePermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
                onState = { state = it })
        }
        SideEffect { controllerRef[0] = controller }
        DisposableEffect(controller, lifecycle) {
            controllerRef[0] = controller
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> controller.resume()
                    Lifecycle.Event.ON_STOP -> controller.suspend()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.suspend()
            controller.load()
            onDispose {
                lifecycle.removeObserver(observer)
                controllerRef[0] = null
                controller.destroy()
            }
        }
        Column(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Voice", style = MaterialTheme.typography.titleLarge)
                Text("Source: $url", style = MaterialTheme.typography.bodySmall)
                Text(when {
                    state.micOpen -> "Microphone open"
                    state.starting -> "Opening microphone / waiting for permission"
                    else -> "Microphone stopped"
                })
                Text(when {
                    !state.ready -> "Loading existing voice room"
                    state.connected -> "Room connected"
                    else -> "Room loaded · service status pending"
                }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = controller::requestStart,
                        enabled = state.ready && !state.micOpen && !state.starting) { Text("Start microphone") }
                    OutlinedButton(onClick = controller::stop, enabled = state.micOpen || state.starting) {
                        Text("Stop")
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
            AndroidView(factory = { controller.webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
