package com.airsense.monitor.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.airsense.monitor.AppContainer
import com.airsense.monitor.container
import com.airsense.monitor.data.AppSettings
import com.airsense.monitor.data.Reading
import com.airsense.monitor.sensor.ConnectionState
import com.airsense.monitor.sensor.SensorSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that keeps the SPP link alive, polls the sensor for the live view,
 * writes samples to the database at the logging interval, tags them with GPS and fires alerts.
 * It stops itself when nothing needs it (not logging and app not visible).
 */
class SensorService : Service() {

    companion object {
        private const val TAG = "SensorService"
        const val ACTION_RUN = "com.airsense.monitor.RUN"
        const val ACTION_SHUTDOWN = "com.airsense.monitor.SHUTDOWN"
        private const val IDLE_STOP_MS = 90_000L

        @Volatile var running = false
            private set

        fun start(context: Context) {
            val i = Intent(context, SensorService::class.java).setAction(ACTION_RUN)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "Cannot start service: ${it.message}") }
        }

        fun shutdown(context: Context) {
            if (!running) {
                context.container.sensor.disconnect()
                return
            }
            context.startService(Intent(context, SensorService::class.java).setAction(ACTION_SHUTDOWN))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var c: AppContainer
    private lateinit var location: LocationTracker
    private lateinit var alerts: AlertManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSnapshot: SensorSnapshot? = null
    private var foregroundTypes = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        c = container
        location = LocationTracker(this)
        alerts = AlertManager(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground(null)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHUTDOWN) {
            scope.launch {
                c.stopLogging()
                c.sensor.disconnect()
                stopEverything()
            }
            return START_NOT_STICKY
        }
        if (loopJob?.isActive != true) loopJob = scope.launch { mainLoop() }
        c.wake.trySend(Unit)
        return START_STICKY
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Calls startForeground with the service types we are currently allowed to use. */
    private fun goForeground(cfg: AppSettings?): Boolean {
        val settings = cfg ?: AppSettings()
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val locOk = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (settings.gpsTagging && settings.loggingActive && locOk) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (types == foregroundTypes) return true
        val n = Notifications.foreground(this, title(), lastSnapshot, settings.loggingActive, settings)
        return try {
            ServiceCompat.startForeground(this, Notifications.ID_FOREGROUND, n, types)
            foregroundTypes = types
            true
        } catch (e: Exception) {
            // e.g. location type refused when (re)started from background: retry without it.
            Log.w(TAG, "startForeground($types) failed: ${e.message}")
            if (Build.VERSION.SDK_INT >= 29 && types != ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
                try {
                    ServiceCompat.startForeground(this, Notifications.ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    true
                } catch (e2: Exception) {
                    false
                }
            } else false
        }
    }

    private fun title(): String = when (val s = c.sensor.state.value) {
        is ConnectionState.Connected -> "Connected to ${s.name}"
        is ConnectionState.Connecting -> "Connecting to ${s.name}…"
        is ConnectionState.Failed -> "Sensor: ${s.message}"
        ConnectionState.Idle -> "AirSense"
    }

    private fun refreshNotification(cfg: AppSettings) {
        val n = Notifications.foreground(this, title(), lastSnapshot, cfg.loggingActive, cfg)
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_FOREGROUND, n) }
    }

    private suspend fun mainLoop() {
        var backoffMs = 3_000L
        var nextLogAt = 0L
        var nextLiveAt = 0L
        var idleSince = 0L
        var wasLogging = false

        while (scope.isActive) {
            val cfg = c.settings.current()
            val now = System.currentTimeMillis()
            goForeground(cfg)

            // Logging state transitions
            if (cfg.loggingActive && !wasLogging) { nextLogAt = now; acquireWakeLock() }
            if (!cfg.loggingActive && wasLogging) releaseWakeLock()
            wasLogging = cfg.loggingActive

            if (cfg.loggingActive && cfg.gpsTagging) location.start() else location.stop()

            val needed = cfg.loggingActive || c.appVisible.value
            if (!needed) {
                if (idleSince == 0L) idleSince = now
                if (now - idleSince > IDLE_STOP_MS) {
                    c.sensor.disconnect()
                    stopEverything()
                    return
                }
            } else idleSince = 0L

            // Connection management with exponential back-off
            val address = cfg.deviceAddress
            if (!c.sensor.isConnected) {
                if (address == null) {
                    refreshNotification(cfg)
                    waitForWake(10_000L)
                    continue
                }
                if (c.sensor.state.value is ConnectionState.Connected) c.sensor.disconnect()
                refreshNotification(cfg)
                val ok = c.sensor.connect(address)
                refreshNotification(cfg)
                if (!ok) {
                    waitForWake(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                    continue
                }
                backoffMs = 3_000L
                nextLiveAt = 0L
                kotlinx.coroutines.delay(400) // let the module settle
            }

            val t = System.currentTimeMillis()
            val doLog = cfg.loggingActive && t >= nextLogAt
            val doLive = c.appVisible.value && t >= nextLiveAt
            val doNow = c.readNowRequested
            if (doLog || doLive || doNow) {
                c.readNowRequested = false
                val snap = c.sensor.readAll(cfg)
                if (snap != null && !snap.isEmpty) {
                    lastSnapshot = snap
                    if (doLog) {
                        store(snap, cfg)
                        nextLogAt = alignNext(nextLogAt, cfg.logIntervalSec * 1000L, snap.timestamp)
                    }
                    alerts.check(snap, cfg)
                } else if (doLog) {
                    // Retry the logging sample sooner if the sensor did not answer.
                    nextLogAt = System.currentTimeMillis() + 10_000L
                }
                nextLiveAt = System.currentTimeMillis() + cfg.livePollSec * 1000L
                refreshNotification(cfg)
            }

            val now2 = System.currentTimeMillis()
            var wakeAt = now2 + 30_000L
            if (cfg.loggingActive) wakeAt = minOf(wakeAt, nextLogAt)
            if (c.appVisible.value) wakeAt = minOf(wakeAt, nextLiveAt)
            waitForWake((wakeAt - now2).coerceIn(200L, 30_000L))
        }
    }

    /** Keeps samples on a regular grid even if one read took a few seconds. */
    private fun alignNext(prev: Long, interval: Long, now: Long): Long {
        var next = if (prev <= 0) now + interval else prev + interval
        while (next <= now) next += interval
        return next
    }

    private suspend fun waitForWake(ms: Long) {
        withTimeoutOrNull(ms) { c.wake.receive() }
    }

    private suspend fun store(s: SensorSnapshot, cfg: AppSettings) {
        val loc = if (cfg.gpsTagging) location.current() else null
        c.dao.insert(
            Reading(
                timestamp = s.timestamp,
                sessionId = cfg.activeSessionId.takeIf { it > 0 },
                pm1 = s.pm1, pm25 = s.pm25, pm10 = s.pm10,
                temperature = s.temperature, humidity = s.humidity, gas = s.gas,
                latitude = loc?.latitude, longitude = loc?.longitude, accuracy = loc?.accuracy,
            )
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSense:logging").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun stopEverything() {
        location.stop()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        location.stop()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
