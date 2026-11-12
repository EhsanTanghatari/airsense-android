package com.airsense.monitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    @Insert
    suspend fun insert(reading: Reading): Long

    @Insert
    suspend fun insertSession(session: Session): Long

    @Query("UPDATE sessions SET endTime = :end WHERE id = :id")
    suspend fun closeSession(id: Long, end: Long)

    @Query("UPDATE sessions SET name = :name WHERE id = :id")
    suspend fun renameSession(id: Long, name: String)

    @Query("SELECT * FROM readings WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    fun observeRange(from: Long, to: Long): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getRange(from: Long, to: Long): List<Reading>

    @Query("SELECT * FROM readings WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeSession(sessionId: Long): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSession(sessionId: Long): List<Reading>

    @Query("SELECT * FROM readings ORDER BY timestamp ASC")
    suspend fun getAll(): List<Reading>

    @Query(
        "SELECT s.*, (SELECT COUNT(*) FROM readings r WHERE r.sessionId = s.id) AS count " +
            "FROM sessions s ORDER BY s.startTime DESC"
    )
    fun observeSessions(): Flow<List<SessionWithCount>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionInfo(id: Long): Session?

    @Query("SELECT COUNT(*) FROM readings")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM readings ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<Reading?>

    @Query("DELETE FROM readings WHERE sessionId = :sessionId")
    suspend fun deleteReadingsOfSession(sessionId: Long)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionRow(sessionId: Long)

    @Query("DELETE FROM readings")
    suspend fun deleteAllReadings()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
