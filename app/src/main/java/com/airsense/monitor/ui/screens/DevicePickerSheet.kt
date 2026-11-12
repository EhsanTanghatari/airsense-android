package com.airsense.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airsense.monitor.sensor.BtDeviceInfo
import com.airsense.monitor.ui.AppViewModel
import com.airsense.monitor.ui.PermissionActions
import com.airsense.monitor.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(
    vm: AppViewModel,
    perms: PermissionActions,
    currentAddress: String?,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val bonded = remember { vm.bondedDevices() }
    val found = remember { mutableStateListOf<BtDeviceInfo>() }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    fun startScan() {
        scanJob?.cancel()
        found.clear()
        scanning = true
        scanJob = scope.launch {
            try {
                vm.discover().collect { d ->
                    if (bonded.none { it.address == d.address } && found.none { it.address == d.address }) found.add(d)
                }
            } finally {
                scanning = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }

    fun choose(d: BtDeviceInfo) {
        scanJob?.cancel()
        if (!d.bonded) vm.pair(d.address)
        vm.selectDevice(d)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Choose your sensor", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your module appears as “JDY-31-SPP” (or similar). If it is not paired yet, scan and tap it; the PIN is usually 1234.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
            Spacer(Modifier.height(16.dp))
            Text("PAIRED", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            Spacer(Modifier.height(6.dp))
            if (bonded.isEmpty()) {
                Text("No paired devices.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            bonded.forEach { d -> DeviceRow(d, d.address == currentAddress, vm.looksLikeSensor(d.name)) { choose(d) } }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEARBY", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                TextButton(onClick = { startScan() }) { Text(if (scanning) "Restart" else "Scan") }
            }
            if (!scanning && found.isEmpty()) {
                Text(
                    "Tap Scan to look for unpaired devices. If nothing shows up, check that Bluetooth and permissions are on.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = perms.requestCore) { Text("Re-check permissions") }
            }
            found.forEach { d -> DeviceRow(d, false, vm.looksLikeSensor(d.name)) { choose(d) } }
        }
    }
}

@Composable
private fun DeviceRow(d: BtDeviceInfo, current: Boolean, likely: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) Palette.Teal.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (likely) Icons.Outlined.Sensors else Icons.Outlined.Bluetooth, null,
            tint = if (likely) Palette.Teal else Palette.Muted,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                d.address + if (likely) "  ·  looks like your sensor" else "",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
        }
        if (current) Icon(Icons.Outlined.CheckCircle, "Selected", tint = Palette.Teal)
    }
}
