package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.service.TermuxLauncher
import com.example.service.ExecServerService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission handled gracefully
    }

    private val requestTermuxPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startTermuxExecutor(this)
    }

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as CodexGoalApplication
                return MainViewModel(app.repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val repo = (application as CodexGoalApplication).repository
            if (repo.isConnectionDesired() && ExecServerService.activeClient == null) {
                try { ExecServerService.startService(this@MainActivity) }
                catch (e: Exception) { repo.recordEvent("IN", "START_FAILURE", e.message ?: "Start failed", e.toString()) }
            }
        }

        // Request POST_NOTIFICATIONS on Android 13+ for foreground service notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestTermuxPermission = {
                        requestTermuxPermissionLauncher.launch(TermuxLauncher.PERMISSION_RUN_COMMAND)
                    }
                )
            }
        }
    }
}
