package com.example.console

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.service.TermuxLauncher

/** Explicit operator bootstrap, independent of codex-exec-service. */
object ConsoleBootstrap {
    fun dispatch(context: Context) {
        require(context.checkSelfPermission(TermuxLauncher.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED) {
            "RUN_COMMAND permission is missing. Grant Termux command permission, then press Start Console."
        }
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            component = ComponentName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/.local/bin/goal-console")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("ensure"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent) else context.startService(intent)
        check(dispatched != null) { "Termux RUN_COMMAND service was not found" }
    }
}
