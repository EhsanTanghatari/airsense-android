package com.airsense.monitor.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airsense.monitor.data.AppSettings
import com.airsense.monitor.data.Metric
import com.airsense.monitor.data.SessionWithCount
import com.airsense.monitor.ui.AppViewModel
import com.airsense.monitor.ui.HistoryAnalysis
import com.airsense.monitor.ui.TimeRange
import com.airsense.monitor.ui.components.AqiDistributionBar
import com.airsense.monitor.ui.components.BarChart
import com.airsense.monitor.ui.components.LegendDot
import com.airsense.monitor.ui.components.LineChart
import com.airsense.monitor.ui.components.Pill
import com.airsense.monitor.ui.components.SectionCard
import com.airsense.monitor.ui.components.StatCell
import com.airsense.monitor.ui.components.TimeFmt
import com.airsense.monitor.ui.components.fmtValue
import com.airsense.monitor.ui.components.pm25Color
import com.airsense.monitor.ui.theme.Palette
import com.airsense.monitor.ui.theme.color
import com.airsense.monitor.util.Analytics
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val WHO_24H = mapOf(Metric.PM25 to 15f, Metric.PM10 to 45f)

fun Metric.displayLabel(s: AppSettings) = if (this == Metric.GAS) s.gasLabel else label
fun Metric.displayUnit(s: AppSettings) = if (this == Metric.GAS) s.gasUnit else unit

@Composable
fun HistoryScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val metric by vm.metric.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val r = vm.exportCsv(uri, everything = false)
            snackbar.showSnackbar(r.fold({ "Exported $it rows" }, { "Export failed: ${it.message}" }))
        }
    }

    val selectedSession = sessions.firstOrNull { it.session.id == filter.sessionId }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("History & analysis", style = MaterialTheme.typography.headlineSmall)
                Text("${analysis.readings.size} samples", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            IconButton(onClick = {
                val name = (selectedSession?.session?.name ?: "airsense_${filter.range.label}")
                    .replace(Regex("[^A-Za-z0-9_-]+"), "_")
                exportLauncher.launch("$name.csv")
            }) { Icon(Icons.Outlined.FileDownload, "Export CSV", tint = Palette.Teal) }
        }

        // Range / session selector
        if (selectedSession != null) {
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Teal.copy(alpha = 0.12f)).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session: ${selectedSession.session.name}", color = Palette.Teal, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.setSession(null) }) { Icon(Icons.Outlined.Close, "Clear", tint = Palette.Teal) }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeRange.entries.forEach { r -> Pill(r.label, filter.range == r, onClick = { vm.setRange(r) }) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric.entries.forEach { m ->
                Pill(if (m == Metric.GAS) "Gas" else m.short, metric == m, onClick = { vm.setMetric(m) }, color = m.color())
            }
        }

        TrendCard(analysis, metric, settings)
        StatsCard(analysis, metric, settings)
        if (analysis.aqiDist.values.sum() > 0) AqiCard(analysis)
        PatternsCard(analysis, metric, settings)
        SessionsCard(
            sessions = sessions,
            activeId = if (settings.loggingActive) settings.activeSessionId else -1L,
            selectedId = filter.sessionId,
            onSelect = { vm.setSession(it) },
            onRename = { id, n -> vm.renameSession(id, n) },
            onDelete = { vm.deleteSession(it) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TrendCard(a: HistoryAnalysis, metric: Metric, s: AppSettings) {
    val points = remember(a.readings, metric) { Analytics.series(a.readings, metric) }
    val span = if (points.size > 1) points.last().t - points.first().t else 0L
    val userTh = when (metric) {
        Metric.PM25 -> s.thPm25
        Metric.PM10 -> s.thPm10
        Metric.GAS -> s.thGas
        Metric.TEMPERATURE -> s.thTempHigh
        Metric.HUMIDITY -> s.thHumidityHigh
        Metric.PM1 -> 0f
    }
    val thresholds = buildList {
        WHO_24H[metric]?.let { add(it to Palette.Green) }
        if (userTh > 0f) add(userTh to Palette.Red)
    }
    SectionCard(title = "${metric.displayLabel(s)} trend") {
        LineChart(
            points = points,
            color = metric.color(),
            unit = metric.displayUnit(s),
            timeLabel = TimeFmt.forSpan(span),
            thresholds = thresholds,
        )
        if (thresholds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                if (WHO_24H[metric] != null) LegendDot(Palette.Green, "WHO 24-h guideline")
                if (userTh > 0f) LegendDot(Palette.Red, "Your alert threshold")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Tap or drag on the chart to read values.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
}

@Composable
private fun StatsCard(a: HistoryAnalysis, metric: Metric, s: AppSettings) {
    val st = a.stats[metric]
    SectionCard(title = "Statistics") {
        if (st == null) {
            Text("No samples for this metric.", color = Palette.Muted)
            return@SectionCard
        }
        val u = metric.displayUnit(s)
        Row(Modifier.fillMaxWidth()) {
            StatCell("Mean", "${fmtValue(st.mean)} $u", Modifier.weight(1f), metric.color())
            StatCell("Min", fmtValue(st.min), Modifier.weight(1f))
            StatCell("Max", fmtValue(st.max), Modifier.weight(1f))
            StatCell("Std dev", fmtValue(st.std), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Palette.Outline)
        Spacer(Modifier.height(10.dp))
        Text("All metrics (mean · min – max)", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        Spacer(Modifier.height(6.dp))
        Metric.entries.forEach { m ->
            val x = a.stats[m] ?: return@forEach
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(m.color()))
                Spacer(Modifier.width(8.dp))
                Text(m.displayLabel(s), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${fmtValue(x.mean)} · ${fmtValue(x.min)} – ${fmtValue(x.max)} ${m.displayUnit(s)}",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
                )
            }
        }
    }
}

@Composable
private fun AqiCard(a: HistoryAnalysis) {
    SectionCard(title = "Time in each AQI category") {
        AqiDistributionBar(a.aqiDist)
        a.whoExceed?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                String.format(Locale.US, "%.0f%% of samples had PM2.5 above the WHO 24-h guideline (15 µg/m³).", it * 100),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun PatternsCard(a: HistoryAnalysis, metric: Metric, s: AppSettings) {
    val u = metric.displayUnit(s)
    val colorFor: ((Float) -> Color)? = if (metric == Metric.PM25) ::pm25Color else null
    SectionCard(title = "Patterns") {
        Text("Average by hour of day", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        BarChart(
            values = a.hourly,
            labels = (0 until 24).map { it.toString() },
            color = metric.color(),
            unit = u,
            labelEvery = 3,
            colorFor = colorFor,
        )
        Spacer(Modifier.height(16.dp))
        Text("Daily average", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val fmt = remember { DateTimeFormatter.ofPattern("d/M", Locale.US) }
        BarChart(
            values = a.daily.map { it.second },
            labels = a.daily.map { it.first.format(fmt) },
            color = metric.color(),
            unit = u,
            labelEvery = if (a.daily.size > 8) 2 else 1,
            colorFor = colorFor,
        )
        if (a.corrHumidity != null || a.corrTemperature != null) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Palette.Outline)
            Spacer(Modifier.height(10.dp))
            Text("Correlation (Pearson r)", style = MaterialTheme.typography.titleSmall)
            a.corrHumidity?.let { CorrRow("with humidity", it) }
            a.corrTemperature?.let { CorrRow("with temperature", it) }
            if (metric in setOf(Metric.PM25, Metric.PM10, Metric.PM1) && (a.corrHumidity ?: 0f) > 0.5f) {
                Text(
                    "Strong positive link with humidity: optical PM sensors like the PMS5003 over-read in humid air " +
                        "(particles absorb water). Consider a humidity correction when RH > 70 %.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Amber,
                )
            }
        }
    }
}

@Composable
private fun CorrRow(label: String, r: Float) {
    val strength = when {
        abs(r) >= 0.7f -> "strong"
        abs(r) >= 0.4f -> "moderate"
        abs(r) >= 0.2f -> "weak"
        else -> "negligible"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "r = %+.2f  (%s)", r, strength), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}

@Composable
private fun SessionsCard(
    sessions: List<SessionWithCount>,
    activeId: Long,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<SessionWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionWithCount?>(null) }

    SectionCard(title = "Sessions", contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 8.dp)) {
        if (sessions.isEmpty()) {
            Text("No logging sessions yet. Start one from the Live tab.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        sessions.forEach { sw ->
            val s = sw.session
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selectedId == s.id) Palette.Teal.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable { onSelect(s.id) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.id == activeId) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Red))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    val end = s.endTime ?: System.currentTimeMillis()
                    Text(
                        "${TimeFmt.full(s.startTime)} · ${TimeFmt.duration(end - s.startTime)} · ${sw.count} samples",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Options", tint = Palette.Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("View") }, onClick = { menu = false; onSelect(s.id) })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameTarget = sw })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleteTarget = sw })
                    }
                }
            }
        }
    }

    renameTarget?.let { t ->
        var text by remember(t) { mutableStateOf(t.session.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(t.session.id, text.trim()); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${t.session.name}”?") },
            text = { Text("${t.count} samples will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = { onDelete(t.session.id); deleteTarget = null }) { Text("Delete", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}
