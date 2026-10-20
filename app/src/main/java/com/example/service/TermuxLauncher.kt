package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    sealed class TermuxLaunchResult {
        object Success : TermuxLaunchResult()
        data class Error(val message: String, val recoveryHint: String) : TermuxLaunchResult()
    }

    fun startTermuxExecutor(context: Context): TermuxLaunchResult {
        val pm = context.packageManager

        // 1. Verify Termux is installed
        val isInstalled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        if (!isInstalled) {
            return TermuxLaunchResult.Error(
                message = "Termux application is not installed on this device.",
                recoveryHint = "Install Termux from F-Droid or GitHub to run the codex exec-server."
            )
        }

        // 2. Verify permission
        val hasPermission = context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return TermuxLaunchResult.Error(
                message = "Missing RUN_COMMAND permission ($PERMISSION_RUN_COMMAND).",
                recoveryHint = "Grant the RUN_COMMAND permission to Codex Goal in Android App Settings."
            )
        }

        // 3. Construct and dispatch intent
        val intent = Intent().apply {
            component = ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/.local/bin/codex-exec-service")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("ensure"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0") // 0 = run in background
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Dispatched Termux RUN_COMMAND intent successfully")
            TermuxLaunchResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux RUN_COMMAND rejected", e)
            TermuxLaunchResult.Error(
                message = "Termux rejected external command: ${e.message}",
                recoveryHint = "In Termux, enable 'allow-external-apps = true' in ~/.termux/termux.properties and run 'termux-reload-settings'."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Termux service", e)
            TermuxLaunchResult.Error(
                message = "Failed to start Termux service: ${e.message}",
                recoveryHint = "Verify Termux is running and not restricted by battery saver."
            )
        }
    }
}
