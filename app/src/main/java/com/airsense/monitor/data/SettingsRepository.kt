package com.airsense.monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class LineEnding(val label: String, val suffix: String) {
    NONE("None", ""),
    CR("CR (\\r)", "\r"),
    LF("LF (\\n)", "\n"),
    CRLF("CR+LF", "\r\n"),
}

data class AppSettings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val autoConnect: Boolean = true,
    val livePollSec: Int = 5,
    val logIntervalSec: Int = 60,
    val lineEnding: LineEnding = LineEnding.CRLF,
    val commandTimeoutMs: Int = 3000,
    val gpsTagging: Boolean = true,
    val alertsEnabled: Boolean = true,
    /** Thresholds; a value <= 0 disables that alert. */
    val thPm25: Float = 35.5f,
    val thPm10: Float = 155f,
    val thGas: Float = 0f,
    val thTempHigh: Float = 0f,
    val thHumidityHigh: Float = 0f,
    val gasLabel: String = "Gas (MQ-135)",
    val gasUnit: String = "",
    /** Persisted so the logging run survives the service being restarted by the OS. */
    val loggingActive: Boolean = false,
    val activeSessionId: Long = -1L,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val deviceAddress = stringPreferencesKey("device_address")
        val deviceName = stringPreferencesKey("device_name")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val livePollSec = intPreferencesKey("live_poll_sec")
        val logIntervalSec = intPreferencesKey("log_interval_sec")
        val lineEnding = stringPreferencesKey("line_ending")
        val commandTimeoutMs = intPreferencesKey("command_timeout_ms")
        val gpsTagging = booleanPreferencesKey("gps_tagging")
        val alertsEnabled = booleanPreferencesKey("alerts_enabled")
        val thPm25 = floatPreferencesKey("th_pm25")
        val thPm10 = floatPreferencesKey("th_pm10")
        val thGas = floatPreferencesKey("th_gas")
        val thTempHigh = floatPreferencesKey("th_temp_high")
        val thHumidityHigh = floatPreferencesKey("th_humidity_high")
        val gasLabel = stringPreferencesKey("gas_label")
        val gasUnit = stringPreferencesKey("gas_unit")
        val loggingActive = booleanPreferencesKey("logging_active")
        val activeSessionId = longPreferencesKey("active_session_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            deviceAddress = p[K.deviceAddress],
            deviceName = p[K.deviceName],
            autoConnect = p[K.autoConnect] ?: d.autoConnect,
            livePollSec = p[K.livePollSec] ?: d.livePollSec,
            logIntervalSec = p[K.logIntervalSec] ?: d.logIntervalSec,
            lineEnding = p[K.lineEnding]?.let { v -> LineEnding.entries.firstOrNull { it.name == v } } ?: d.lineEnding,
            commandTimeoutMs = p[K.commandTimeoutMs] ?: d.commandTimeoutMs,
            gpsTagging = p[K.gpsTagging] ?: d.gpsTagging,
            alertsEnabled = p[K.alertsEnabled] ?: d.alertsEnabled,
            thPm25 = p[K.thPm25] ?: d.thPm25,
            thPm10 = p[K.thPm10] ?: d.thPm10,
            thGas = p[K.thGas] ?: d.thGas,
            thTempHigh = p[K.thTempHigh] ?: d.thTempHigh,
            thHumidityHigh = p[K.thHumidityHigh] ?: d.thHumidityHigh,
            gasLabel = p[K.gasLabel] ?: d.gasLabel,
            gasUnit = p[K.gasUnit] ?: d.gasUnit,
            loggingActive = p[K.loggingActive] ?: d.loggingActive,
            activeSessionId = p[K.activeSessionId] ?: d.activeSessionId,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDevice(address: String, name: String?) = context.dataStore.edit {
        it[K.deviceAddress] = address
        if (name != null) it[K.deviceName] = name else it.remove(K.deviceName)
    }

    suspend fun forgetDevice() = context.dataStore.edit {
        it.remove(K.deviceAddress); it.remove(K.deviceName)
    }

    suspend fun setAutoConnect(v: Boolean) = context.dataStore.edit { it[K.autoConnect] = v }
    suspend fun setLivePollSec(v: Int) = context.dataStore.edit { it[K.livePollSec] = v.coerceIn(2, 600) }
    suspend fun setLogIntervalSec(v: Int) = context.dataStore.edit { it[K.logIntervalSec] = v.coerceAtLeast(10) }
    suspend fun setLineEnding(v: LineEnding) = context.dataStore.edit { it[K.lineEnding] = v.name }
    suspend fun setCommandTimeoutMs(v: Int) = context.dataStore.edit { it[K.commandTimeoutMs] = v.coerceIn(500, 15000) }
    suspend fun setGpsTagging(v: Boolean) = context.dataStore.edit { it[K.gpsTagging] = v }
    suspend fun setAlertsEnabled(v: Boolean) = context.dataStore.edit { it[K.alertsEnabled] = v }
    suspend fun setThPm25(v: Float) = context.dataStore.edit { it[K.thPm25] = v }
    suspend fun setThPm10(v: Float) = context.dataStore.edit { it[K.thPm10] = v }
    suspend fun setThGas(v: Float) = context.dataStore.edit { it[K.thGas] = v }
    suspend fun setThTempHigh(v: Float) = context.dataStore.edit { it[K.thTempHigh] = v }
    suspend fun setThHumidityHigh(v: Float) = context.dataStore.edit { it[K.thHumidityHigh] = v }
    suspend fun setGasLabel(label: String, unit: String) = context.dataStore.edit {
        it[K.gasLabel] = label.ifBlank { "Gas (MQ-135)" }
        it[K.gasUnit] = unit
    }

    suspend fun setLogging(active: Boolean, sessionId: Long) = context.dataStore.edit {
        it[K.loggingActive] = active
        it[K.activeSessionId] = sessionId
    }
}
