package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.repository.ExecRepository

class CodexGoalApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: ExecRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = ExecRepository(database)

    }
}
