package com.airsense.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airsense.monitor.sensor.ConnectionState
import com.airsense.monitor.sensor.SensorCommand
import com.airsense.monitor.ui.AppViewModel
import com.airsense.monitor.ui.components.Pill
import com.airsense.monitor.ui.components.TimeFmt
import com.airsense.monitor.ui.theme.MonoStyle
import com.airsense.monitor.ui.theme.Palette

@Composable
fun ConsoleScreen(vm: AppViewModel, onBack: () -> Unit) {
    val lines by vm.console.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Serial console", style = MaterialTheme.typography.titleLarge)
                Text(
                    when (val c = conn) {
                        is ConnectionState.Connected -> "Connected to ${c.name}"
                        is ConnectionState.Connecting -> "Connecting…"
                        is ConnectionState.Failed -> c.message
                        ConnectionState.Idle -> "Not connected"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
            }
            IconButton(onClick = { vm.clearConsole() }) { Icon(Icons.Outlined.DeleteSweep, "Clear", tint = Palette.Muted) }
        }
        LazyColumn(
            state = list,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
                .padding(10.dp),
        ) {
            items(lines) { l ->
                Row {
                    Text(TimeFmt.hms(l.time), style = MonoStyle, color = Palette.Muted.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (l.outgoing) "› " else "") + l.text,
                        style = MonoStyle,
                        color = when {
                            l.system -> Palette.Amber
                            l.outgoing -> Palette.Sky
                            else -> Palette.Green
                        },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SensorCommand.entries.forEach { c -> Pill(c.code.toString(), false, onClick = { vm.sendConsole(c.code.toString()) }) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Type a command") },
                singleLine = true,
                textStyle = MonoStyle,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Outline),
            )
            IconButton(onClick = { if (input.isNotEmpty()) { vm.sendConsole(input); input = "" } }) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Palette.Teal)
            }
        }
    }
}
