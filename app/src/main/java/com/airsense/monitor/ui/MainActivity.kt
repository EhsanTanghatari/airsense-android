package com.airsense.monitor.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.airsense.monitor.container
import com.airsense.monitor.ui.screens.ConsoleScreen
import com.airsense.monitor.ui.screens.DevicePickerSheet
import com.airsense.monitor.ui.screens.HistoryScreen
import com.airsense.monitor.ui.screens.LiveScreen
import com.airsense.monitor.ui.screens.MapScreen
import com.airsense.monitor.ui.screens.SettingsScreen
import com.airsense.monitor.ui.theme.AirSenseTheme
import com.airsense.monitor.ui.theme.Palette

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AirSenseTheme { AppRoot(vm) } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("live", "Live", Icons.Outlined.Air),
    Tab("history", "History", Icons.Outlined.ShowChart),
    Tab("map", "Map", Icons.Outlined.Map),
    Tab("settings", "Settings", Icons.Outlined.Tune),
)

/** Lambdas the screens use to ask for runtime permissions. */
class PermissionActions(
    val requestCore: () -> Unit,
    val requestLocation: () -> Unit,
    val enableBluetooth: () -> Unit,
)

fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
private fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onAppStart()
    }
    val coreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val adapter = context.container.sensor.adapter
        if (adapter != null && !adapter.isEnabled && btPermissions().all { p -> granted(p) }) {
            runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        } else vm.onAppStart()
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    val perms = remember {
        PermissionActions(
            requestCore = {
                val list = btPermissions().toMutableList()
                if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
                list += Manifest.permission.ACCESS_FINE_LOCATION
                list += Manifest.permission.ACCESS_COARSE_LOCATION
                coreLauncher.launch(list.distinct().toTypedArray())
            },
            requestLocation = {
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            enableBluetooth = {
                runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            },
        )
    }

    LaunchedEffect(Unit) {
        val missing = btPermissions().any { !granted(it) } ||
            (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
        if (missing) perms.requestCore() else {
            val adapter = context.container.sensor.adapter
            if (adapter != null && !adapter.isEnabled) perms.enableBluetooth() else vm.onAppStart()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != null && tabs.any { it.route == route }) {
                NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Teal,
                                selectedTextColor = Palette.Teal,
                                indicatorColor = Palette.Teal.copy(alpha = 0.14f),
                                unselectedIconColor = Palette.Muted,
                                unselectedTextColor = Palette.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(nav, startDestination = "live", modifier = Modifier.padding(inner)) {
            composable("live") {
                LiveScreen(vm, perms, snackbar, onPickDevice = { showPicker = true })
            }
            composable("history") { HistoryScreen(vm, snackbar) }
            composable("map") { MapScreen(vm) }
            composable("settings") {
                SettingsScreen(
                    vm, perms, snackbar,
                    onPickDevice = { showPicker = true },
                    onOpenConsole = { nav.navigate("console") },
                )
            }
            composable("console") { ConsoleScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }

    if (showPicker) {
        DevicePickerSheet(
            vm = vm,
            perms = perms,
            currentAddress = settings.deviceAddress,
            onDismiss = { showPicker = false },
        )
    }
}
