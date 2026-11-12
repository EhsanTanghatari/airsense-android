package com.airsense.monitor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airsense.monitor.container
import com.airsense.monitor.data.AppSettings
import com.airsense.monitor.data.CsvExporter
import com.airsense.monitor.data.LineEnding
import com.airsense.monitor.data.Metric
import com.airsense.monitor.data.Reading
import com.airsense.monitor.data.SessionWithCount
import com.airsense.monitor.sensor.BtDeviceInfo
import com.airsense.monitor.sensor.ConnectionState
import com.airsense.monitor.sensor.ConsoleLine
import com.airsense.monitor.sensor.SensorSnapshot
import com.airsense.monitor.service.SensorService
import com.airsense.monitor.util.Analytics
import com.airsense.monitor.util.AqiCategory
import com.airsense.monitor.util.MetricStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class TimeRange(val label: String, val millis: Long) {
    H1("1h", 3_600_000L),
    H6("6h", 6 * 3_600_000L),
    H24("24h", 24 * 3_600_000L),
    D7("7d", 7 * 24 * 3_600_000L),
    D30("30d", 30 * 24 * 3_600_000L),
    ALL("All", Long.MAX_VALUE),
}

/** What the History/Map screens show: a time window or one recorded session. */
data class HistoryFilter(val range: TimeRange = TimeRange.H24, val sessionId: Long? = null)

data class HistoryAnalysis(
    val readings: List<Reading> = emptyList(),
    val stats: Map<Metric, MetricStats> = emptyMap(),
    val hourly: List<Float?> = List(24) { null },
    val daily: List<Pair<LocalDate, Float>> = emptyList(),
    val aqiDist: Map<AqiCategory, Int> = emptyMap(),
    val whoExceed: Float? = null,
    val corrHumidity: Float? = null,
    val corrTemperature: Float? = null,
    val located: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val c = app.container

    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val connection: StateFlow<ConnectionState> = c.sensor.state
    val latest: StateFlow<SensorSnapshot?> = c.sensor.latest
    val liveHistory: StateFlow<List<SensorSnapshot>> = c.sensor.history
    val busy: StateFlow<Boolean> = c.sensor.busy
    val console: StateFlow<List<ConsoleLine>> = c.sensor.console

    val sessions: StateFlow<List<SessionWithCount>> =
        c.dao.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val totalCount: StateFlow<Int> =
        c.dao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ---------- History ----------
    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    private val _metric = MutableStateFlow(Metric.PM25)
    val metric: StateFlow<Metric> = _metric.asStateFlow()

    val analysis: StateFlow<HistoryAnalysis> = combine(_filter, _metric) { f, m -> f to m }
        .flatMapLatest { (f, m) ->
            val src = if (f.sessionId != null) c.dao.observeSession(f.sessionId) else {
                val now = System.currentTimeMillis()
                val from = if (f.range == TimeRange.ALL) 0L else now - f.range.millis
                c.dao.observeRange(from, Long.MAX_VALUE)
            }
            src.map { analyze(it, m) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryAnalysis())

    private fun analyze(rs: List<Reading>, m: Metric) = HistoryAnalysis(
        readings = rs,
        stats = Metric.entries.mapNotNull { mt -> Analytics.stats(rs, mt)?.let { mt to it } }.toMap(),
        hourly = Analytics.hourlyProfile(rs, m),
        daily = Analytics.dailyMeans(rs, m),
        aqiDist = Analytics.aqiDistribution(rs),
        whoExceed = Analytics.exceedanceFraction(rs, Metric.PM25, 15f),
        corrHumidity = if (m == Metric.HUMIDITY) null else Analytics.pearson(rs, m, Metric.HUMIDITY),
        corrTemperature = if (m == Metric.TEMPERATURE) null else Analytics.pearson(rs, m, Metric.TEMPERATURE),
        located = rs.count { it.latitude != null },
    )

    fun setRange(r: TimeRange) { _filter.value = HistoryFilter(range = r, sessionId = null) }
    fun setSession(id: Long?) { _filter.value = _filter.value.copy(sessionId = id) }
    fun setMetric(m: Metric) { _metric.value = m }

    // ---------- Connection / logging ----------
    fun connect() = c.ensureService()

    fun disconnect() = SensorService.shutdown(getApplication())

    fun readNow() {
        if (c.sensor.isConnected) c.requestReadNow() else c.ensureService()
    }

    fun startLogging(name: String) = viewModelScope.launch { c.startLogging(name) }
    fun stopLogging() = viewModelScope.launch { c.stopLogging() }

    fun onAppStart() {
        viewModelScope.launch {
            val s = c.settings.current()
            if ((s.autoConnect && s.deviceAddress != null) || s.loggingActive) c.ensureService()
        }
    }

    // ---------- Devices ----------
    fun bondedDevices(): List<BtDeviceInfo> = c.scanner.bonded()
    fun discover() = c.scanner.discover()
    fun pair(address: String) = c.scanner.pair(address)
    fun looksLikeSensor(name: String?) = c.scanner.looksLikeSensor(name)

    fun selectDevice(d: BtDeviceInfo) = viewModelScope.launch {
        c.sensor.disconnect()
        c.settings.setDevice(d.address, d.name)
        c.ensureService()
        c.wake.trySend(Unit)
    }

    fun forgetDevice() = viewModelScope.launch {
        c.stopLogging()
        SensorService.shutdown(getApplication())
        c.settings.forgetDevice()
    }

    // ---------- Console ----------
    private var consoleJob: Job? = null
    fun sendConsole(text: String) {
        consoleJob = viewModelScope.launch {
            runCatching { c.sensor.sendRaw(text, settings.value.lineEnding.suffix) }
        }
    }
    fun clearConsole() = c.sensor.clearConsole()

    // ---------- Settings ----------
    private fun edit(block: suspend () -> Unit) = viewModelScope.launch { block(); c.wake.trySend(Unit) }
    fun setAutoConnect(v: Boolean) = edit { c.settings.setAutoConnect(v) }
    fun setLivePoll(v: Int) = edit { c.settings.setLivePollSec(v) }
    fun setLogInterval(v: Int) = edit { c.settings.setLogIntervalSec(v) }
    fun setLineEnding(v: LineEnding) = edit { c.settings.setLineEnding(v) }
    fun setTimeout(v: Int) = edit { c.settings.setCommandTimeoutMs(v) }
    fun setGps(v: Boolean) = edit { c.settings.setGpsTagging(v) }
    fun setAlerts(v: Boolean) = edit { c.settings.setAlertsEnabled(v) }
    fun setThPm25(v: Float) = edit { c.settings.setThPm25(v) }
    fun setThPm10(v: Float) = edit { c.settings.setThPm10(v) }
    fun setThGas(v: Float) = edit { c.settings.setThGas(v) }
    fun setThTemp(v: Float) = edit { c.settings.setThTempHigh(v) }
    fun setThHum(v: Float) = edit { c.settings.setThHumidityHigh(v) }
    fun setGasLabel(label: String, unit: String) = edit { c.settings.setGasLabel(label, unit) }

    // ---------- Data ----------
    fun renameSession(id: Long, name: String) = viewModelScope.launch { c.dao.renameSession(id, name) }
    fun deleteSession(id: Long) = viewModelScope.launch {
        if (settings.value.activeSessionId == id) c.stopLogging()
        c.db.deleteSession(id)
        if (_filter.value.sessionId == id) _filter.value = _filter.value.copy(sessionId = null)
    }
    fun clearAll() = viewModelScope.launch {
        c.stopLogging()
        c.db.clearAll()
    }

    /** Exports either the current History selection or everything. */
    suspend fun exportCsv(uri: Uri, everything: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val rows = if (everything) c.dao.getAll() else analysis.value.readings
            val names = c.dao.observeSessions().first().associate { it.session.id to it.session.name }
            CsvExporter.write(getApplication(), uri, rows, names, settings.value.gasLabel)
        }
    }
}
