package com.airsense.monitor.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airsense.monitor.BuildConfig
import com.airsense.monitor.data.LineEnding
import com.airsense.monitor.service.Notifications
import com.airsense.monitor.ui.AppViewModel
import com.airsense.monitor.ui.PermissionActions
import com.airsense.monitor.ui.btPermissions
import com.airsense.monitor.ui.components.Pill
import com.airsense.monitor.ui.components.SectionCard
import com.airsense.monitor.ui.theme.Palette
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    perms: PermissionActions,
    snackbar: SnackbarHostState,
    onPickDevice: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val r = vm.exportCsv(uri, everything = true)
            snackbar.showSnackbar(r.fold({ "Exported $it rows" }, { "Export failed: ${it.message}" }))
        }
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = "Sensor") {
            Text(s.deviceName ?: "No device selected", style = MaterialTheme.typography.titleMedium)
            s.deviceAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (btPermissions().all(::granted)) onPickDevice() else perms.requestCore() }) {
                    Text(if (s.deviceAddress == null) "Select device" else "Change")
                }
                if (s.deviceAddress != null) OutlinedButton(onClick = { vm.forgetDevice() }) { Text("Forget") }
            }
            Spacer(Modifier.height(6.dp))
            SwitchRow("Auto-connect on launch", "Reconnects automatically and retries if the link drops", s.autoConnect) {
                vm.setAutoConnect(it)
            }
        }

        SectionCard(title = "Sampling") {
            Text("Live view refresh", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 5, 10, 30).forEach { sec -> Pill("$sec s", s.livePollSec == sec, onClick = { vm.setLivePoll(sec) }) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Logging interval", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 120, 300, 600, 900, 1800, 3600).forEach { sec ->
                    Pill(Notifications.formatInterval(sec), s.logIntervalSec == sec, onClick = { vm.setLogInterval(sec) })
                }
            }
            Spacer(Modifier.height(8.dp))
            NumberField(
                label = "Custom interval (minutes)",
                value = s.logIntervalSec / 60f,
                onCommit = { if (it >= 1f) vm.setLogInterval((it * 60).toInt()) },
            )
            Text(
                "Each logged sample runs one H/T/C/P request cycle (~4 s). Shorter live refresh drains the battery faster; " +
                    "live polling only runs while the app is on screen.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
        }

        SectionCard(title = "Alerts") {
            SwitchRow("Threshold notifications", "Notifies once when a value crosses a threshold (15-min cool-down)", s.alertsEnabled) {
                vm.setAlerts(it)
            }
            if (s.alertsEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("Set 0 to disable an individual alert.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("PM2.5 (µg/m³)", s.thPm25, { vm.setThPm25(it) }, Modifier.weight(1f))
                    NumberField("PM10 (µg/m³)", s.thPm10, { vm.setThPm10(it) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Temp high (°C)", s.thTempHigh, { vm.setThTemp(it) }, Modifier.weight(1f))
                    NumberField("Humidity high (%)", s.thHumidityHigh, { vm.setThHum(it) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                NumberField("${s.gasLabel} high", s.thGas, { vm.setThGas(it) })
                Text(
                    "Defaults: PM2.5 35.5 and PM10 155 are where the US AQI turns “Unhealthy for sensitive groups”.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
            }
        }

        SectionCard(title = "Location") {
            SwitchRow("GPS-tag logged samples", "Lets you map pollution along your route", s.gpsTagging) { on ->
                vm.setGps(on)
                if (on && !granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    perms.requestLocation()
                }
            }
        }

        SectionCard(title = "Gas sensor (MQ-135)") {
            var label by remember(s.gasLabel) { mutableStateOf(s.gasLabel) }
            var unit by remember(s.gasUnit) { mutableStateOf(s.gasUnit) }
            Text(
                "The firmware reports this as “CO”. MQ-135 responds to many gases (NH₃, NOx, alcohol, benzene, smoke, CO₂) and " +
                    "needs calibration (R0, temperature/humidity compensation) before its number means ppm. Name it to match what your firmware computes.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.weight(1.4f),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Outline))
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Outline))
            }
            if (label != s.gasLabel || unit != s.gasUnit) {
                TextButton(onClick = { vm.setGasLabel(label, unit) }) { Text("Save") }
            }
        }

        SectionCard(title = "Protocol") {
            Text("Line ending after each command", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineEnding.entries.forEach { le -> Pill(le.label, s.lineEnding == le, onClick = { vm.setLineEnding(le) }) }
            }
            Spacer(Modifier.height(14.dp))
            var timeout by remember(s.commandTimeoutMs) { mutableFloatStateOf(s.commandTimeoutMs.toFloat()) }
            Text(
                String.format(Locale.US, "Response timeout: %.1f s", timeout / 1000f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = timeout,
                onValueChange = { timeout = it },
                onValueChangeFinished = { vm.setTimeout(timeout.toInt()) },
                valueRange = 1000f..8000f,
                steps = 13,
            )
            HorizontalDivider(color = Palette.Outline)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Terminal, null, tint = Palette.Teal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Raw serial console", style = MaterialTheme.typography.bodyLarge)
                    Text("See every byte exchanged; send custom commands", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                TextButton(onClick = onOpenConsole) { Icon(Icons.Outlined.ChevronRight, "Open") }
            }
        }

        SectionCard(title = "Data") {
            Text("$total samples stored on this phone", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { exportAll.launch("airsense_all.csv") }, enabled = total > 0) { Text("Export all (CSV)") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = total > 0) { Text("Clear all", color = Palette.Red) }
            }
        }

        Text(
            "AirSense ${BuildConfig.VERSION_NAME} · PMS5003 · AHT20 · MQ-135 · SPP Bluetooth",
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all data?") },
            text = { Text("All $total samples and every session will be removed. Export first if you need them.") },
            confirmButton = { TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("Delete", color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Teal, checkedThumbColor = Palette.Bg),
        )
    }
}

@Composable
private fun NumberField(label: String, value: Float, onCommit: (Float) -> Unit, modifier: Modifier = Modifier) {
    fun show(v: Float) = if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
    var text by remember { mutableStateOf(show(value)) }
    LaunchedEffect(value) {
        if (text.replace(',', '.').toFloatOrNull() != value) text = show(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()?.let { if (it >= 0f) onCommit(it) }
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Outline),
    )
}
