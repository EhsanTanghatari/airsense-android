package com.airsense.monitor.data

import android.content.Context
import android.net.Uri
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CsvExporter {

    private val isoFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).withZone(ZoneId.systemDefault())

    /** Writes readings as CSV to a SAF document Uri. Returns number of rows written. */
    fun write(
        context: Context,
        uri: Uri,
        readings: List<Reading>,
        sessionNames: Map<Long, String>,
        gasLabel: String,
    ): Int {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open output file")
        OutputStreamWriter(out, Charsets.UTF_8).buffered().use { w ->
            val gasCol = "gas_" + gasLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            w.write("time_local,epoch_ms,session,pm1_ugm3,pm25_ugm3,pm10_ugm3,temperature_c,humidity_pct,$gasCol,latitude,longitude,gps_accuracy_m\n")
            for (r in readings) {
                val session = r.sessionId?.let { sessionNames[it] } ?: ""
                w.write(
                    listOf(
                        isoFormat.format(Instant.ofEpochMilli(r.timestamp)),
                        r.timestamp.toString(),
                        escape(session),
                        fmt(r.pm1), fmt(r.pm25), fmt(r.pm10),
                        fmt(r.temperature), fmt(r.humidity), fmt(r.gas),
                        r.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                        r.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                        fmt(r.accuracy),
                    ).joinToString(",")
                )
                w.write("\n")
            }
        }
        return readings.size
    }

    private fun fmt(v: Float?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: ""

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
