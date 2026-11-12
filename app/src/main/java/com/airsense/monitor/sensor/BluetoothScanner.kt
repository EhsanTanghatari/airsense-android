package com.airsense.monitor.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class BtDeviceInfo(val name: String, val address: String, val bonded: Boolean)

/** Lists bonded devices and discovers nearby classic-Bluetooth devices. */
class BluetoothScanner(private val context: Context, private val adapter: BluetoothAdapter?) {

    @SuppressLint("MissingPermission")
    fun bonded(): List<BtDeviceInfo> = runCatching {
        adapter?.bondedDevices.orEmpty().map {
            BtDeviceInfo(it.name ?: it.address, it.address, true)
        }.sortedByDescending { looksLikeSensor(it.name) }
    }.getOrDefault(emptyList())

    fun looksLikeSensor(name: String?): Boolean {
        val n = name?.uppercase() ?: return false
        return listOf("JDY", "SPP", "HC-05", "HC-06", "HC05", "HC06", "BT04", "AIR").any { n.contains(it) }
    }

    /** Emits each discovered device; discovery is cancelled when the collector stops. */
    @SuppressLint("MissingPermission")
    fun discover(): Flow<BtDeviceInfo> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d != null) {
                        val name = runCatching { d.name }.getOrNull()
                        trySend(BtDeviceInfo(name ?: d.address, d.address, d.bondState == BluetoothDevice.BOND_BONDED))
                    }
                } else if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
            adapter?.startDiscovery()
        }
        awaitClose {
            runCatching { adapter?.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    fun pair(address: String): Boolean = runCatching {
        adapter?.getRemoteDevice(address)?.createBond() ?: false
    }.getOrDefault(false)
}
