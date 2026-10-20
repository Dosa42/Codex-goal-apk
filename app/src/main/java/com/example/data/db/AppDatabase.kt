package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        ProcessEntity::class,
        OutputChunkEntity::class,
        ProtocolEventEntity::class,
        SettingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun processDao(): ProcessDao
    abstract fun outputChunkDao(): OutputChunkDao
    abstract fun protocolEventDao(): ProtocolEventDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "codex_goal.db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
