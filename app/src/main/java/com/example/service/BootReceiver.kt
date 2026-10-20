package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.db.AppDatabase
import com.example.data.repository.ExecRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val repository = ExecRepository(db)
                    if (repository.isAutoBootEnabled() && repository.isConnectionDesired()) {
                        try {
                            ExecServerService.startService(context)
                        } catch (e: Exception) {
                            repository.recordEvent(
                                "IN",
                                "BOOT_FAILURE",
                                "Background start blocked: ${e.message}",
                                e.stackTraceToString()
                            )
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
