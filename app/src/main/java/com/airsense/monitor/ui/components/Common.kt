package com.airsense.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airsense.monitor.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(contentPadding)
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (title != null) {
                    Text(
                        title.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Muted,
                        modifier = Modifier.weight(1f),
                    )
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(9.dp).clip(CircleShape).background(color))
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Teal,
) {
    val bg = if (selected) color.copy(alpha = 0.18f) else Palette.SurfaceHi
    val fg = if (selected) color else Palette.Muted
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) color.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, color: Color = Palette.Text) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
    Spacer(Modifier.width(12.dp))
}

object TimeFmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val dayHm = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
    private val md = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val full = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

    private fun z(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())

    fun hm(t: Long): String = hm.format(z(t))
    fun hms(t: Long): String = hms.format(z(t))
    fun full(t: Long): String = full.format(z(t))
    fun md(t: Long): String = md.format(z(t))

    /** Picks an axis label format that suits the span being shown. */
    fun forSpan(spanMs: Long): (Long) -> String = when {
        spanMs <= 26 * 3_600_000L -> { t -> hm.format(z(t)) }
        spanMs <= 8 * 24 * 3_600_000L -> { t -> dayHm.format(z(t)) }
        else -> { t -> md.format(z(t)) }
    }

    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - t) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s % 60}s"
            else -> "${s}s"
        }
    }
}
