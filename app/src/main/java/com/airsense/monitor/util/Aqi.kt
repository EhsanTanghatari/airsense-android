package com.airsense.monitor.util

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * US EPA AQI (PM2.5 breakpoints revised 2024). Officially defined on 24-h averages;
 * applied to an instantaneous reading it is only indicative.
 */
enum class AqiCategory(val label: String, val color: Long, val advice: String) {
    GOOD("Good", 0xFF34D399, "Air quality is satisfactory."),
    MODERATE("Moderate", 0xFFFBBF24, "Unusually sensitive people should limit long exertion outdoors."),
    USG("Unhealthy for sensitive groups", 0xFFFB923C, "Sensitive groups should reduce prolonged exertion."),
    UNHEALTHY("Unhealthy", 0xFFF87171, "Everyone may begin to feel effects; limit outdoor exertion."),
    VERY_UNHEALTHY("Very unhealthy", 0xFFC084FC, "Health alert: avoid outdoor exertion, consider a mask."),
    HAZARDOUS("Hazardous", 0xFFBE123C, "Emergency conditions: stay indoors with filtered air."),
}

data class AqiResult(val value: Int, val category: AqiCategory, val dominant: String)

object Aqi {
    private data class Bp(val cLo: Double, val cHi: Double, val iLo: Int, val iHi: Int)

    private val pm25 = listOf(
        Bp(0.0, 9.0, 0, 50),
        Bp(9.1, 35.4, 51, 100),
        Bp(35.5, 55.4, 101, 150),
        Bp(55.5, 125.4, 151, 200),
        Bp(125.5, 225.4, 201, 300),
        Bp(225.5, 325.4, 301, 500),
    )
    private val pm10 = listOf(
        Bp(0.0, 54.0, 0, 50),
        Bp(55.0, 154.0, 51, 100),
        Bp(155.0, 254.0, 101, 150),
        Bp(255.0, 354.0, 151, 200),
        Bp(355.0, 424.0, 201, 300),
        Bp(425.0, 604.0, 301, 500),
    )

    private fun index(c: Double, table: List<Bp>): Int {
        if (c < 0) return 0
        val bp = table.firstOrNull { c <= it.cHi } ?: return 500
        val lo = if (c < bp.cLo) bp.cLo else c
        return (((bp.iHi - bp.iLo) / (bp.cHi - bp.cLo)) * (lo - bp.cLo) + bp.iLo).roundToInt()
    }

    fun fromPm25(c: Float): Int = index(floor(c * 10.0) / 10.0, pm25)
    fun fromPm10(c: Float): Int = index(floor(c.toDouble()), pm10)

    fun category(aqi: Int): AqiCategory = when {
        aqi <= 50 -> AqiCategory.GOOD
        aqi <= 100 -> AqiCategory.MODERATE
        aqi <= 150 -> AqiCategory.USG
        aqi <= 200 -> AqiCategory.UNHEALTHY
        aqi <= 300 -> AqiCategory.VERY_UNHEALTHY
        else -> AqiCategory.HAZARDOUS
    }

    fun compute(pm25: Float?, pm10: Float?): AqiResult? {
        val a = pm25?.let { fromPm25(it) }
        val b = pm10?.let { fromPm10(it) }
        if (a == null && b == null) return null
        val (v, dom) = when {
            b == null -> a!! to "PM2.5"
            a == null -> b to "PM10"
            a >= b -> a to "PM2.5"
            else -> b to "PM10"
        }
        return AqiResult(v, category(v), dom)
    }
}
