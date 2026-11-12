package com.airsense.monitor.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airsense.monitor.data.Metric
import com.airsense.monitor.data.Reading
import com.airsense.monitor.ui.AppViewModel
import com.airsense.monitor.ui.TimeRange
import com.airsense.monitor.ui.components.Pill
import com.airsense.monitor.ui.components.TimeFmt
import com.airsense.monitor.ui.components.fmtValue
import com.airsense.monitor.ui.components.pm25Color
import com.airsense.monitor.ui.theme.Palette
import com.airsense.monitor.ui.theme.color
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.hypot

/** Draws a coloured dot per GPS-tagged sample and reports taps on them. */
private class SamplesOverlay(private val onTap: (Reading?) -> Unit) : Overlay() {
    var readings: List<Reading> = emptyList()
    var colorOf: (Reading) -> Int = { 0xFF5EEAD4.toInt() }
    var selectedId: Long? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF0B1220.toInt()
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x885EEAD4.toInt()
    }
    private val tmp = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || readings.isEmpty()) return
        val proj = mapView.projection
        val density = mapView.resources.displayMetrics.density
        var prev: Point? = null
        for (r in readings) {
            val lat = r.latitude ?: continue
            val lon = r.longitude ?: continue
            proj.toPixels(GeoPoint(lat, lon), tmp)
            val p = Point(tmp.x, tmp.y)
            prev?.let { canvas.drawLine(it.x.toFloat(), it.y.toFloat(), p.x.toFloat(), p.y.toFloat(), track) }
            prev = p
        }
        for (r in readings) {
            val lat = r.latitude ?: continue
            val lon = r.longitude ?: continue
            proj.toPixels(GeoPoint(lat, lon), tmp)
            val rad = (if (r.id == selectedId) 9f else 6f) * density
            fill.color = colorOf(r)
            canvas.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), rad, fill)
            canvas.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), rad, ring)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val limit = 24f * mapView.resources.displayMetrics.density
        var best: Reading? = null
        var bestD = Float.MAX_VALUE
        for (r in readings) {
            val lat = r.latitude ?: continue
            val lon = r.longitude ?: continue
            proj.toPixels(GeoPoint(lat, lon), tmp)
            val d = hypot(tmp.x - e.x, tmp.y - e.y)
            if (d < bestD) { bestD = d; best = r }
        }
        val hit = if (bestD <= limit) best else null
        selectedId = hit?.id
        onTap(hit)
        mapView.invalidate()
        return hit != null
    }
}

private val mapMetrics = listOf(Metric.PM25, Metric.PM10, Metric.TEMPERATURE, Metric.HUMIDITY, Metric.GAS)

@Composable
fun MapScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var colorMetric by remember { mutableStateOf(Metric.PM25) }
    var selected by remember { mutableStateOf<Reading?>(null) }

    val located = remember(analysis.readings) { analysis.readings.filter { it.latitude != null && it.longitude != null } }

    val overlay = remember { SamplesOverlay { selected = it } }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(35.7004, 51.3510)) // default view until data arrives
            overlays.add(overlay)
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onPause(); mapView.onDetach() }
    }

    // Colour scale for non-PM metrics: min..max of the shown data, teal→amber→red.
    val range = remember(located, colorMetric) {
        val v = located.mapNotNull { colorMetric.of(it) }
        if (v.isEmpty()) 0f to 1f else v.min() to v.max()
    }

    LaunchedEffect(located, colorMetric) {
        overlay.readings = located
        overlay.colorOf = { r ->
            val v = colorMetric.of(r)
            when {
                v == null -> Palette.Muted.toArgb()
                colorMetric == Metric.PM25 -> pm25Color(v).toArgb()
                else -> scaleColor(v, range.first, range.second).toArgb()
            }
        }
        if (located.isNotEmpty()) {
            val pts = located.map { GeoPoint(it.latitude!!, it.longitude!!) }
            mapView.post {
                if (pts.size == 1) {
                    mapView.controller.setZoom(17.0); mapView.controller.setCenter(pts[0])
                } else {
                    val box = BoundingBox.fromGeoPointsSafe(pts)
                    runCatching { mapView.zoomToBoundingBox(box.increaseByScale(1.25f), false, 48) }
                }
            }
        }
        mapView.invalidate()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.Bg.copy(alpha = 0.88f))
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filter.sessionId == null) {
                    TimeRange.entries.forEach { r -> Pill(r.label, filter.range == r, onClick = { vm.setRange(r) }) }
                    Spacer(Modifier.width(4.dp))
                }
                mapMetrics.forEach { m ->
                    Pill(if (m == Metric.GAS) "Gas" else m.short, colorMetric == m, onClick = { colorMetric = m }, color = m.color())
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.Bg.copy(alpha = 0.92f))
                .padding(14.dp)
        ) {
            val sel = selected
            if (sel != null) {
                Text(TimeFmt.full(sel.timestamp), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildList {
                        sel.pm25?.let { add("PM2.5 ${fmtValue(it)}") }
                        sel.pm10?.let { add("PM10 ${fmtValue(it)}") }
                        sel.temperature?.let { add("${fmtValue(it)} °C") }
                        sel.humidity?.let { add("${fmtValue(it)} %") }
                        sel.gas?.let { add("${settings.gasLabel} ${fmtValue(it)}") }
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
            } else if (located.isEmpty()) {
                Text("No GPS-tagged samples in this range", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Turn on GPS tagging in Settings and start a logging session; samples are pinned where they were taken.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
            } else {
                Text("${located.size} located samples", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                if (colorMetric == Metric.PM25) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(5f to "Good", 20f to "Moderate", 45f to "USG", 90f to "Unhealthy", 150f to "V. unhealthy").forEach { (v, l) ->
                            Box(Modifier.size(9.dp).clip(CircleShape).background(pm25Color(v)))
                            Spacer(Modifier.width(4.dp))
                            Text(l, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                } else {
                    Text(
                        "Colour: ${fmtValue(range.first)} (teal) → ${fmtValue(range.second)} (red)",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                    )
                }
                Text("Tap a dot for details.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
    }
}

private fun scaleColor(v: Float, lo: Float, hi: Float): Color {
    val t = if (hi - lo < 1e-6f) 0.5f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    return if (t < 0.5f) lerp(Palette.Teal, Palette.Amber, t * 2) else lerp(Palette.Amber, Palette.Red, (t - 0.5f) * 2)
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
