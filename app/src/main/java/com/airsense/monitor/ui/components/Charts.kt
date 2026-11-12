package com.airsense.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.monitor.ui.theme.Palette
import com.airsense.monitor.util.Analytics
import com.airsense.monitor.util.Aqi
import com.airsense.monitor.util.AqiCategory
import com.airsense.monitor.util.TimePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun fmtValue(v: Float): String = when {
    abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
    abs(v) >= 10 -> String.format(Locale.US, "%.1f", v)
    else -> String.format(Locale.US, "%.2f", v)
}

private val axisStyle = TextStyle(color = Palette.Muted, fontSize = 10.sp)

/** Time-series line chart with gap detection, threshold lines and touch scrubbing. */
@Composable
fun LineChart(
    points: List<TimePoint>,
    color: Color,
    unit: String,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
    thresholds: List<Pair<Float, Color>> = emptyList(),
    height: Dp = 220.dp,
) {
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                if (points.isEmpty()) "No data in this range" else "Need at least two samples",
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    val pts = remember(points) { Analytics.downsample(points, 600) }
    val measurer = rememberTextMeasurer()
    var selected by remember(pts) { mutableStateOf<Int?>(null) }

    val tMin = pts.first().t
    val tMax = max(pts.last().t, tMin + 1)
    var vMin = pts.minOf { it.v }
    var vMax = pts.maxOf { it.v }
    thresholds.forEach { (th, _) -> if (th <= vMax * 1.6f && th >= vMin) vMax = max(vMax, th) }
    if (vMax - vMin < 1e-3f) { vMin -= 1f; vMax += 1f }
    val pad = (vMax - vMin) * 0.12f
    vMin = if (vMin >= 0f) max(0f, vMin - pad) else vMin - pad
    vMax += pad

    val gapMs = remember(pts) {
        val dts = pts.zipWithNext { a, b -> b.t - a.t }.sorted()
        max(dts[dts.size / 2] * 4, 120_000L)
    }

    val leftPad = 40.dp
    val bottomPad = 20.dp

    fun indexAt(x: Float, width: Float, left: Float): Int {
        val frac = ((x - left) / (width - left)).coerceIn(0f, 1f)
        val t = tMin + (frac * (tMax - tMin)).toLong()
        var best = 0
        var bestD = Long.MAX_VALUE
        for (i in pts.indices) {
            val d = abs(pts[i].t - t)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(pts) {
                detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), leftPad.toPx()) }
            }
            .pointerInput(pts) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> selected = indexAt(o.x, size.width.toFloat(), leftPad.toPx()) },
                    onHorizontalDrag = { change, _ ->
                        selected = indexAt(change.position.x, size.width.toFloat(), leftPad.toPx())
                    },
                )
            }
    ) {
        val left = leftPad.toPx()
        val bottom = size.height - bottomPad.toPx()
        val top = 8.dp.toPx()
        val w = size.width - left
        val h = bottom - top
        fun x(t: Long) = left + (t - tMin).toFloat() / (tMax - tMin) * w
        fun y(v: Float) = bottom - (v - vMin) / (vMax - vMin) * h

        // grid + y labels
        for (i in 0..4) {
            val v = vMin + (vMax - vMin) * i / 4f
            val yy = y(v)
            drawLine(Palette.Outline.copy(alpha = 0.6f), Offset(left, yy), Offset(size.width, yy), 1f)
            val tl = measurer.measure(fmtValue(v), axisStyle)
            drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2f))
        }
        // x labels
        for (i in 0..3) {
            val t = tMin + (tMax - tMin) * i / 3
            val tl = measurer.measure(timeLabel(t), axisStyle)
            val xx = (x(t) - tl.size.width / 2f).coerceIn(left, size.width - tl.size.width)
            drawText(tl, topLeft = Offset(xx, bottom + 4.dp.toPx()))
        }
        // thresholds
        thresholds.forEach { (th, c) ->
            if (th in vMin..vMax) {
                drawLine(
                    c.copy(alpha = 0.8f), Offset(left, y(th)), Offset(size.width, y(th)), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }
        }
        // line + area (split at gaps)
        val line = Path()
        val segments = mutableListOf<Pair<Int, Int>>()
        var segStart = 0
        for (i in pts.indices) {
            val p = pts[i]
            if (i == 0 || p.t - pts[i - 1].t > gapMs) {
                if (i > 0) segments.add(segStart to i - 1)
                segStart = i
                line.moveTo(x(p.t), y(p.v))
            } else line.lineTo(x(p.t), y(p.v))
        }
        segments.add(segStart to pts.lastIndex)
        for ((a, b) in segments) {
            if (b <= a) continue
            val area = Path().apply {
                moveTo(x(pts[a].t), bottom)
                for (k in a..b) lineTo(x(pts[k].t), y(pts[k].v))
                lineTo(x(pts[b].t), bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f)), startY = top, endY = bottom))
        }
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // isolated points
        for ((a, b) in segments) if (a == b) drawCircle(color, 2.5.dp.toPx(), Offset(x(pts[a].t), y(pts[a].v)))

        // selection tooltip
        selected?.let { idx ->
            val p = pts[idx]
            val px = x(p.t); val py = y(p.v)
            drawLine(Palette.Muted.copy(alpha = 0.7f), Offset(px, top), Offset(px, bottom), 1f)
            drawCircle(Palette.Bg, 6.dp.toPx(), Offset(px, py))
            drawCircle(color, 4.dp.toPx(), Offset(px, py))
            val txt = "${fmtValue(p.v)} $unit  ·  ${timeLabel(p.t)}"
            val tl = measurer.measure(txt, TextStyle(color = Palette.Text, fontSize = 11.sp))
            val bw = tl.size.width + 16.dp.toPx()
            val bh = tl.size.height + 10.dp.toPx()
            val bx = (px - bw / 2).coerceIn(left, size.width - bw)
            val by = top
            drawRoundRect(Palette.SurfaceHi, Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()))
            drawText(tl, topLeft = Offset(bx + 8.dp.toPx(), by + 5.dp.toPx()))
        }
    }
}

/** Vertical bar chart; tap a bar to read its value. */
@Composable
fun BarChart(
    values: List<Float?>,
    labels: List<String>,
    color: Color,
    unit: String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    height: Dp = 170.dp,
    colorFor: ((Float) -> Color)? = null,
) {
    val present = values.filterNotNull()
    if (present.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("No data", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val measurer = rememberTextMeasurer()
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val vMax = max(present.max(), 1e-3f) * 1.12f
    val maxIdx = values.indices.maxByOrNull { values[it] ?: -Float.MAX_VALUE }

    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(values) {
            detectTapGestures { o ->
                val slot = size.width / values.size.toFloat()
                selected = (o.x / slot).toInt().coerceIn(0, values.lastIndex)
            }
        }
    ) {
        val bottom = size.height - 18.dp.toPx()
        val top = 18.dp.toPx()
        val slot = size.width / values.size
        val bw = slot * 0.62f
        drawLine(Palette.Outline, Offset(0f, bottom), Offset(size.width, bottom), 1f)
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            if (v != null) {
                val bh = (v / vMax) * (bottom - top)
                val base = colorFor?.invoke(v) ?: color
                val c = if (selected == null || selected == i) base else base.copy(alpha = 0.45f)
                drawRoundRect(
                    c, Offset(cx - bw / 2, bottom - bh), Size(bw, max(bh, 1.5f)),
                    CornerRadius(min(bw / 2, 4.dp.toPx())),
                )
            }
            if (i % labelEvery == 0 && i < labels.size) {
                val tl = measurer.measure(labels[i], axisStyle)
                drawText(tl, topLeft = Offset(cx - tl.size.width / 2f, bottom + 3.dp.toPx()))
            }
        }
        val show = selected ?: maxIdx
        if (show != null) {
            values[show]?.let { v ->
                val prefix = if (selected == null) "max " else ""
                val txt = "$prefix${fmtValue(v)} $unit · ${labels.getOrElse(show) { "" }}"
                val tl = measurer.measure(txt, TextStyle(color = Palette.Text, fontSize = 11.sp))
                val cx = slot * show + slot / 2
                val x0 = (cx - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width)
                drawText(tl, topLeft = Offset(x0, 0f))
            }
        }
    }
}

@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max()
        val range = if (hi - lo < 1e-3f) 1f else hi - lo
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - lo) / range * size.height * 0.9f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private val gaugeStops = listOf(0, 50, 100, 150, 200, 300, 500)

/** Semi-circular AQI gauge; each category gets an equal slice of the arc. */
@Composable
fun AqiGauge(aqi: Int?, modifier: Modifier = Modifier, size: Dp = 210.dp) {
    Canvas(modifier.size(size, size * 0.62f)) {
        val stroke = 14.dp.toPx()
        val d = this.size.width - stroke
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(d, d)
        val start = 180f
        val sweep = 180f
        val per = sweep / AqiCategory.entries.size
        AqiCategory.entries.forEachIndexed { i, c ->
            drawArc(
                Color(c.color).copy(alpha = 0.9f), start + i * per + 1.2f, per - 2.4f, false,
                topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt),
            )
        }
        if (aqi != null) {
            val a = aqi.coerceIn(0, 500)
            val seg = (1 until gaugeStops.size).first { a <= gaugeStops[it] } - 1
            val frac = (a - gaugeStops[seg]).toFloat() / (gaugeStops[seg + 1] - gaugeStops[seg])
            val angle = Math.toRadians((start + (seg + frac) * per).toDouble())
            val r = d / 2
            val cx = this.size.width / 2
            val cy = stroke / 2 + r
            val p = Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
            drawCircle(Palette.Bg, stroke * 0.85f, p)
            drawCircle(Color.White, stroke * 0.55f, p)
        }
    }
}

/** Stacked bar showing how much of the time was spent in each AQI category. */
@Composable
fun AqiDistributionBar(dist: Map<AqiCategory, Int>, modifier: Modifier = Modifier) {
    val total = dist.values.sum()
    Column(modifier) {
        if (total == 0) {
            Text("No particulate data", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.SurfaceHi)
        ) {
            dist.forEach { (cat, n) ->
                if (n > 0) Box(Modifier.weight(n.toFloat()).height(14.dp).background(Color(cat.color)))
            }
        }
        Spacer(Modifier.height(10.dp))
        dist.filter { it.value > 0 }.forEach { (cat, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(22.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(cat.color)))
                Spacer(Modifier.width(8.dp))
                Text(cat.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    String.format(Locale.US, "%.0f%%", 100f * n / total),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
            }
        }
    }
}

/** Colour for a PM2.5 concentration (via its AQI category). */
fun pm25Color(v: Float): Color = Color(Aqi.category(Aqi.fromPm25(v)).color)
