package com.airsense.monitor

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.airsense.monitor.data.AppDatabase
import com.airsense.monitor.data.Session
import com.airsense.monitor.data.SettingsRepository
import com.airsense.monitor.sensor.BluetoothScanner
import com.airsense.monitor.sensor.SensorController
import com.airsense.monitor.service.Notifications
import com.airsense.monitor.service.SensorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.config.Configuration

class AirSenseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { container.setVisible(true) }
            override fun onStop(owner: LifecycleOwner) { container.setVisible(false) }
        })
    }
}

val Context.container: AppContainer get() = (applicationContext as AirSenseApp).container

/** Simple manual dependency container. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.build(app)
    val dao = db.readingDao()
    val settings = SettingsRepository(app)
    val sensor = SensorController(app)
    val scanner by lazy { BluetoothScanner(app, sensor.adapter) }

    private val _visible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Wakes the service loop (read-now, settings change, visibility change). */
    val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile var readNowRequested = false

    fun setVisible(v: Boolean) {
        _visible.value = v
        wake.trySend(Unit)
    }

    fun requestReadNow() {
        readNowRequested = true
        wake.trySend(Unit)
    }

    /** Starts (or pokes) the foreground service that owns the Bluetooth connection. */
    fun ensureService() = SensorService.start(app)

    suspend fun startLogging(name: String) {
        val cfg = settings.current()
        if (cfg.loggingActive) return
        val id = dao.insertSession(
            Session(name = name.ifBlank { "Session" }, startTime = System.currentTimeMillis(), endTime = null, intervalSec = cfg.logIntervalSec)
        )
        settings.setLogging(true, id)
        ensureService()
        wake.trySend(Unit)
    }

    suspend fun stopLogging() {
        val cfg = settings.current()
        if (cfg.activeSessionId > 0) dao.closeSession(cfg.activeSessionId, System.currentTimeMillis())
        settings.setLogging(false, -1L)
        wake.trySend(Unit)
    }
}
