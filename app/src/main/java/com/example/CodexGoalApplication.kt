package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.repository.ExecRepository
import com.example.service.ExecServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CodexGoalApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: ExecRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = ExecRepository(database)

        // Automatically start service in background to be ready for connections
        CoroutineScope(Dispatchers.IO).launch {
            ExecServerService.startService(this@CodexGoalApplication)
        }
    }
}
