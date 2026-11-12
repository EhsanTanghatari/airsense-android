package com.airsense.monitor.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.airsense.monitor.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Connected(val name: String, val address: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class ConsoleLine(val time: Long, val text: String, val outgoing: Boolean, val system: Boolean = false)

/**
 * Owns the RFCOMM (SPP) socket to the sensor and implements the request/response protocol.
 * All requests are serialised with a mutex so the live view and the logger never interleave.
 */
class SensorController(private val context: Context) {

    companion object {
        private const val TAG = "SensorController"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONSOLE_MAX = 400
        private const val HISTORY_MAX = 240
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestMutex = Mutex()
    private val connectMutex = Mutex()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var lines = Channel<String>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _latest = MutableStateFlow<SensorSnapshot?>(null)
    val latest: StateFlow<SensorSnapshot?> = _latest.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    /** In-memory rolling history of live reads (for sparklines), not persisted. */
    private val _history = MutableStateFlow<List<SensorSnapshot>>(emptyList())
    val history: StateFlow<List<SensorSnapshot>> = _history.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val isConnected: Boolean get() = _state.value is ConnectionState.Connected && socket?.isConnected == true

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun log(text: String, outgoing: Boolean = false, system: Boolean = false) {
        val line = ConsoleLine(System.currentTimeMillis(), text, outgoing, system)
        _console.update { (it + line).takeLast(CONSOLE_MAX) }
    }

    fun clearConsole() { _console.value = emptyList() }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): Boolean = connectMutex.withLock {
        if (isConnected) return true
        val bt = adapter
        if (bt == null) { fail("Bluetooth not available"); return false }
        if (!hasConnectPermission()) { fail("Bluetooth permission not granted"); return false }
        if (!bt.isEnabled) { fail("Bluetooth is off"); return false }

        val device: BluetoothDevice = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            fail("Invalid device address"); return false
        }
        val name = runCatching { device.name }.getOrNull() ?: address
        _state.value = ConnectionState.Connecting(name)
        log("Connecting to $name ($address)…", system = true)

        return withContext(Dispatchers.IO) {
            runCatching { if (bt.isDiscovering) bt.cancelDiscovery() }
            val sock = openSocket(device)
            if (sock == null) {
                fail("Could not connect to $name")
                false
            } else {
                socket = sock
                input = sock.inputStream
                output = sock.outputStream
                lines = Channel(Channel.UNLIMITED)
                startReader()
                _state.value = ConnectionState.Connected(name, address)
                log("Connected", system = true)
                true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        // 1) Standard SPP UUID (secure, then insecure), 2) channel-1 reflection fallback used by HC-05/JDY modules.
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        for ((i, make) in attempts.withIndex()) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                Log.w(TAG, "connect attempt ${i + 1} failed: ${e.message}")
                runCatching { s?.close() }
            }
        }
        return null
    }

    private fun startReader() {
        readerJob?.cancel()
        val stream = input ?: return
        val channel = lines
        readerJob = scope.launch {
            val buf = ByteArray(256)
            val sb = StringBuilder()
            try {
                while (isActive) {
                    val n = stream.read(buf)
                    if (n < 0) throw IOException("Stream closed")
                    for (i in 0 until n) {
                        val c = (buf[i].toInt() and 0xFF).toChar()
                        if (c == '\n' || c == '\r') {
                            if (sb.isNotEmpty()) {
                                val line = sb.toString().trim()
                                sb.setLength(0)
                                if (line.isNotEmpty()) {
                                    log(line)
                                    channel.trySend(line)
                                }
                            }
                        } else if (c.code >= 0x20 || c == '\t') {
                            sb.append(c)
                            if (sb.length > 512) sb.setLength(0)
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive && _state.value is ConnectionState.Connected) {
                    log("Connection lost: ${e.message}", system = true)
                    closeQuietly()
                    _state.value = ConnectionState.Failed("Connection lost")
                }
            }
        }
    }

    private fun fail(msg: String) {
        log(msg, system = true)
        _state.value = ConnectionState.Failed(msg)
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
        lines.close()
    }

    fun disconnect() {
        _state.value = ConnectionState.Idle
        readerJob?.cancel()
        readerJob = null
        val wasConnected = socket != null
        closeQuietly()
        if (wasConnected) log("Disconnected", system = true)
    }

    /** Sends raw text (console). */
    suspend fun sendRaw(text: String, ending: String) {
        val out = output ?: throw IOException("Not connected")
        log(text, outgoing = true)
        withContext(Dispatchers.IO) {
            out.write((text + ending).toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private suspend fun request(cmd: SensorCommand, ending: String, timeoutMs: Long): Map<Field, Float> {
        val out = output ?: throw IOException("Not connected")
        // Drop anything unsolicited that is still buffered.
        while (lines.tryReceive().isSuccess) { /* drain */ }
        log(cmd.code.toString(), outgoing = true)
        withContext(Dispatchers.IO) {
            out.write((cmd.code.toString() + ending).toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        val result = LinkedHashMap<Field, Float>()
        withTimeoutOrNull(timeoutMs) {
            while (!result.keys.containsAll(cmd.fields)) {
                val line = lines.receiveCatching().getOrNull() ?: break
                ProtocolParser.parse(line).forEach { (f, v) -> if (f in cmd.fields) result[f] = v }
            }
        }
        return result
    }

    /** Runs H, T, C, P in sequence and returns the merged snapshot, or null if nothing answered. */
    suspend fun readAll(settings: AppSettings): SensorSnapshot? = requestMutex.withLock {
        if (!isConnected) return null
        _busy.value = true
        try {
            val values = LinkedHashMap<Field, Float>()
            for (cmd in SensorCommand.entries) {
                try {
                    values.putAll(request(cmd, settings.lineEnding.suffix, settings.commandTimeoutMs.toLong()))
                } catch (e: IOException) {
                    log("Write failed: ${e.message}", system = true)
                    closeQuietly()
                    _state.value = ConnectionState.Failed("Connection lost")
                    return null
                }
                delay(120)
            }
            if (values.isEmpty()) {
                log("No answer from sensor", system = true)
                return null
            }
            val snap = SensorSnapshot.from(System.currentTimeMillis(), values)
            _latest.value = snap
            _history.update { (it + snap).takeLast(HISTORY_MAX) }
            snap
        } finally {
            _busy.value = false
        }
    }
}
