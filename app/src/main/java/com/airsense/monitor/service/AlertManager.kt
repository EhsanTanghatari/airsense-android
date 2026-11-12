package com.airsense.monitor.service

import android.content.Context
import com.airsense.monitor.data.AppSettings
import com.airsense.monitor.sensor.SensorSnapshot
import java.util.Locale

/**
 * Threshold alerts with hysteresis (re-arms below 90 % of the threshold)
 * and a cool-down so a hovering value doesn't spam notifications.
 */
class AlertManager(private val context: Context) {

    private class Rule(val key: String, val id: Int) {
        var armed = true
        var lastFired = 0L
    }

    private val rules = mapOf(
        "pm25" to Rule("pm25", 2001),
        "pm10" to Rule("pm10", 2002),
        "gas" to Rule("gas", 2003),
        "temp" to Rule("temp", 2004),
        "hum" to Rule("hum", 2005),
    )
    private val cooldownMs = 15 * 60_000L

    fun check(s: SensorSnapshot, cfg: AppSettings) {
        if (!cfg.alertsEnabled) return
        eval("pm25", s.pm25, cfg.thPm25, "PM2.5 is high", "µg/m³")
        eval("pm10", s.pm10, cfg.thPm10, "PM10 is high", "µg/m³")
        eval("gas", s.gas, cfg.thGas, "${cfg.gasLabel} is high", cfg.gasUnit)
        eval("temp", s.temperature, cfg.thTempHigh, "Temperature is high", "°C")
        eval("hum", s.humidity, cfg.thHumidityHigh, "Humidity is high", "%")
    }

    private fun eval(key: String, value: Float?, threshold: Float, title: String, unit: String) {
        val r = rules.getValue(key)
        if (value == null || threshold <= 0f) return
        val now = System.currentTimeMillis()
        if (value >= threshold) {
            if (r.armed && now - r.lastFired > cooldownMs) {
                r.armed = false
                r.lastFired = now
                val v = String.format(Locale.US, "%.1f", value)
                val t = String.format(Locale.US, "%.1f", threshold)
                Notifications.alert(context, r.id, title, "Current value $v $unit exceeds your threshold of $t $unit.".replace("  ", " "))
            }
        } else if (value < threshold * 0.9f) {
            r.armed = true
        }
    }
}
