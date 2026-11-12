package com.airsense.monitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(entities = [Reading::class, Session::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    suspend fun deleteSession(id: Long) = withTransaction {
        readingDao().deleteReadingsOfSession(id)
        readingDao().deleteSessionRow(id)
    }

    suspend fun clearAll() = withTransaction {
        readingDao().deleteAllReadings()
        readingDao().deleteAllSessions()
    }

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "airsense.db").build()
    }
}
