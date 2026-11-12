package com.airsense.monitor.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stored sample. Any field can be null if the sensor did not answer that command. */
@Entity(
    tableName = "readings",
    indices = [Index("timestamp"), Index("sessionId")]
)
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sessionId: Long?,
    val pm1: Float?,
    val pm25: Float?,
    val pm10: Float?,
    val temperature: Float?,
    val humidity: Float?,
    val gas: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
)

/** A logging run started by the user (e.g. "Commute", "Bedroom overnight"). */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val endTime: Long?,
    val intervalSec: Int,
)

data class SessionWithCount(
    @Embedded val session: Session,
    val count: Int,
)

/** Every metric the app knows about, with display metadata. */
enum class Metric(val label: String, val short: String, val unit: String) {
    PM25("PM2.5", "PM2.5", "µg/m³"),
    PM10("PM10", "PM10", "µg/m³"),
    PM1("PM1.0", "PM1", "µg/m³"),
    TEMPERATURE("Temperature", "Temp", "°C"),
    HUMIDITY("Humidity", "RH", "%"),
    GAS("Gas index", "Gas", "");

    fun of(r: Reading): Float? = when (this) {
        PM25 -> r.pm25
        PM10 -> r.pm10
        PM1 -> r.pm1
        TEMPERATURE -> r.temperature
        HUMIDITY -> r.humidity
        GAS -> r.gas
    }
}
