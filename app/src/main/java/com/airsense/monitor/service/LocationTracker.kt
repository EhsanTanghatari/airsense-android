package com.airsense.monitor.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/** Lightweight GPS/network location source (no Google Play Services needed). */
class LocationTracker(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var best: Location? = null
    private var running = false

    private val listener = LocationListener { loc -> if (isBetter(loc, best)) best = loc }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 10_000L, 5f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { if (isBetter(it, best)) best = it }
                }
            }
        }
        running = true
    }

    fun stop() {
        if (!running) return
        runCatching { lm.removeUpdates(listener) }
        running = false
    }

    /** Most recent fix not older than [maxAgeMs], or null. */
    fun current(maxAgeMs: Long = 120_000L): Location? {
        val l = best ?: return null
        return if (System.currentTimeMillis() - l.time <= maxAgeMs) l else null
    }

    private fun isBetter(new: Location, old: Location?): Boolean {
        if (old == null) return true
        val newer = new.time - old.time
        if (newer > 30_000) return true
        if (newer < -30_000) return false
        return new.accuracy <= old.accuracy || newer > 0 && new.accuracy < old.accuracy * 1.5f
    }
}
