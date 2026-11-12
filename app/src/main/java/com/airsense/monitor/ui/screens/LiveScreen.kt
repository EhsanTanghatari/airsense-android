package com.airsense.monitor.ui.screens

import android.content.pm.PackageManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airsense.monitor.data.Metric
import com.airsense.monitor.sensor.ConnectionState
import com.airsense.monitor.sensor.SensorSnapshot
import com.airsense.monitor.service.Notifications
import com.airsense.monitor.ui.AppViewModel
import com.airsense.monitor.ui.PermissionActions
import com.airsense.monitor.ui.btPermissions
import com.airsense.monitor.ui.components.AqiGauge
import com.airsense.monitor.ui.components.Pill
import com.airsense.monitor.ui.components.SectionCard
import com.airsense.monitor.ui.components.Sparkline
import com.airsense.monitor.ui.components.StatusDot
import com.airsense.monitor.ui.components.TimeFmt
import com.airsense.monitor.ui.components.fmtValue
import com.airsense.monitor.ui.theme.Palette
import com.airsense.monitor.ui.theme.color
import com.airsense.monitor.util.Aqi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LiveScreen(
    vm: AppViewModel,
    perms: PermissionActions,
    snackbar: SnackbarHostState,
    onPickDevice: () -> Unit,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val history by vm.liveHistory.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    fun hasBt() = btPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun connectOrAsk() {
        when {
            !hasBt() -> perms.requestCore()
            settings.deviceAddress == null -> onPickDevice()
            vm.connection.value.let { it is ConnectionState.Idle || it is ConnectionState.Failed } -> vm.connect()
        }
        if (vm.connection.value is ConnectionState.Connected) vm.readNow()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AirSense", style = MaterialTheme.typography.headlineSmall)
                Text("Portable air-quality monitor", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            ConnectionChip(
                conn = conn,
                hasDevice = settings.deviceAddress != null,
                logging = settings.loggingActive,
                onConnect = { connectOrAsk() },
                onDisconnect = {
                    if (settings.loggingActive) scope.launch { snackbar.showSnackbar("Stop logging first, or use the notification's stop button") }
                    else vm.disconnect()
                },
                onChoose = onPickDevice,
            )
        }

        if (settings.deviceAddress == null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BluetoothSearching, null, tint = Palette.Teal, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Choose your sensor", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pair the JDY-31 / SPP module once (PIN is usually 1234). AirSense reconnects automatically after that.",
                            style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { if (hasBt()) onPickDevice() else perms.requestCore() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Select device")
                }
            }
        }

        AqiHero(latest, now, busy, onRefresh = { connectOrAsk() })

        MetricGrid(latest, history, settings.gasLabel, settings.gasUnit)

        LoggingCard(
            loggingActive = settings.loggingActive,
            intervalSec = settings.logIntervalSec,
            gps = settings.gpsTagging,
            activeSession = sessions.firstOrNull { it.session.id == settings.activeSessionId },
            now = now,
            onInterval = { vm.setLogInterval(it) },
            onStart = { name ->
                if (!hasBt()) perms.requestCore()
                else if (settings.deviceAddress == null) onPickDevice()
                else vm.startLogging(name)
            },
            onStop = { vm.stopLogging() },
        )

        Text(
            "AQI uses US EPA breakpoints on instantaneous readings, so it is indicative only. " +
                "The MQ-135 value is an uncalibrated gas index—watch its trend, not the absolute number.",
            style = MaterialTheme.typography.bodySmall, color = Palette.Muted.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectionChip(
    conn: ConnectionState,
    hasDevice: Boolean,
    logging: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onChoose: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (color, text) = when (conn) {
        is ConnectionState.Connected -> Palette.Green to conn.name
        is ConnectionState.Connecting -> Palette.Amber to "Connecting…"
        is ConnectionState.Failed -> Palette.Red to "Retrying"
        ConnectionState.Idle -> Palette.Muted to if (hasDevice) "Connect" else "No device"
    }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
                .clickable {
                    when (conn) {
                        is ConnectionState.Connected -> menu = true
                        ConnectionState.Idle -> if (hasDevice) onConnect() else onChoose()
                        else -> menu = true
                    }
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conn is ConnectionState.Connecting) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
            } else StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (logging) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.FiberManualRecord, "Logging", tint = Palette.Red, modifier = Modifier.size(12.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (conn is ConnectionState.Failed) {
                DropdownMenuItem(text = { Text(conn.message) }, onClick = { menu = false }, enabled = false)
                DropdownMenuItem(text = { Text("Retry now") }, onClick = { menu = false; onConnect() })
            }
            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onDisconnect() })
            DropdownMenuItem(text = { Text("Choose another device") }, onClick = { menu = false; onChoose() })
        }
    }
}

@Composable
private fun AqiHero(latest: SensorSnapshot?, now: Long, busy: Boolean, onRefresh: () -> Unit) {
    val aqi = latest?.let { Aqi.compute(it.pm25, it.pm10) }
    val catColor = aqi?.let { Color(it.category.color) } ?: Palette.Muted
    SectionCard(
        title = "Air quality index",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    latest?.let { "Updated " + TimeFmt.ago(it.timestamp, now) } ?: "No reading yet",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, "Read now", tint = Palette.Teal)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            AqiGauge(aqi?.value, Modifier.align(Alignment.TopCenter))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    aqi?.value?.toString() ?: "—",
                    fontSize = 54.sp, fontWeight = FontWeight.Light, color = Palette.Text,
                )
                Text(aqi?.category?.label ?: "Waiting for sensor", color = catColor, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            aqi?.let { "${it.category.advice}  (dominant: ${it.dominant})" }
                ?: "Connect the sensor to see live readings.",
            style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetricGrid(latest: SensorSnapshot?, history: List<SensorSnapshot>, gasLabel: String, gasUnit: String) {
    data class Tile(val metric: Metric, val label: String, val unit: String, val value: Float?, val series: List<Float>)

    fun series(sel: (SensorSnapshot) -> Float?) = history.takeLast(60).mapNotNull(sel)
    val tiles = listOf(
        Tile(Metric.PM25, "PM2.5", "µg/m³", latest?.pm25, series { it.pm25 }),
        Tile(Metric.PM10, "PM10", "µg/m³", latest?.pm10, series { it.pm10 }),
        Tile(Metric.PM1, "PM1.0", "µg/m³", latest?.pm1, series { it.pm1 }),
        Tile(Metric.TEMPERATURE, "Temperature", "°C", latest?.temperature, series { it.temperature }),
        Tile(Metric.HUMIDITY, "Humidity", "%", latest?.humidity, series { it.humidity }),
        Tile(Metric.GAS, gasLabel, gasUnit, latest?.gas, series { it.gas }),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    MetricTile(t.label, t.value, t.unit, t.metric.color(), t.series, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: Float?, unit: String, color: Color, series: List<Float>, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { fmtValue(it) } ?: "—", fontSize = 28.sp, fontWeight = FontWeight.Normal)
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Sparkline(series, color, Modifier.fillMaxWidth().height(28.dp))
    }
}

private val intervalChoices = listOf(60 to "1 min", 120 to "2 min", 300 to "5 min", 600 to "10 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 h")

@Composable
private fun LoggingCard(
    loggingActive: Boolean,
    intervalSec: Int,
    gps: Boolean,
    activeSession: com.airsense.monitor.data.SessionWithCount?,
    now: Long,
    onInterval: (Int) -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(title = "Logging") {
        if (loggingActive) {
            val pulse = rememberInfiniteTransition(label = "rec")
            val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).alpha(a).clip(CircleShape).background(Palette.Red))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(activeSession?.session?.name ?: "Recording", style = MaterialTheme.typography.titleMedium)
                    val started = activeSession?.session?.startTime
                    Text(
                        buildString {
                            append("Every ${Notifications.formatInterval(intervalSec)}")
                            activeSession?.let { append(" · ${it.count} samples") }
                            started?.let { append(" · running ${TimeFmt.duration(now - it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                }
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Outlined.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (gps) Icons.Outlined.LocationOn else Icons.Outlined.Bluetooth, null,
                    tint = Palette.Muted, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (gps) "Samples are tagged with GPS location" else "GPS tagging is off",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                )
            }
        } else {
            val defaultName = remember {
                "Session " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US))
            }
            var name by rememberSaveable { mutableStateOf("") }
            Text(
                "Store a sample at a fixed interval—keeps running with the screen off.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervalChoices.forEach { (sec, label) ->
                    Pill(label, selected = intervalSec == sec, onClick = { onInterval(sec) })
                }
                if (intervalChoices.none { it.first == intervalSec }) {
                    Pill(Notifications.formatInterval(intervalSec), selected = true, onClick = {})
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(defaultName) },
                label = { Text("Session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Outline),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onStart(name.ifBlank { defaultName }); name = "" },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Teal),
            ) {
                Icon(Icons.Outlined.FiberManualRecord, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start logging")
            }
        }
    }
}
