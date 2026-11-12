package com.airsense.monitor.util

import com.airsense.monitor.data.Metric
import com.airsense.monitor.data.Reading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

data class MetricStats(
    val count: Int,
    val min: Float,
    val max: Float,
    val mean: Float,
    val std: Float,
    val last: Float?,
)

data class TimePoint(val t: Long, val v: Float)

object Analytics {

    fun series(readings: List<Reading>, metric: Metric): List<TimePoint> =
        readings.mapNotNull { r -> metric.of(r)?.let { TimePoint(r.timestamp, it) } }

    fun stats(readings: List<Reading>, metric: Metric): MetricStats? {
        val v = readings.mapNotNull { metric.of(it) }
        if (v.isEmpty()) return null
        val mean = v.average()
        val variance = v.sumOf { (it - mean) * (it - mean) } / v.size
        return MetricStats(
            count = v.size,
            min = v.min(),
            max = v.max(),
            mean = mean.toFloat(),
            std = sqrt(variance).toFloat(),
            last = v.lastOrNull(),
        )
    }

    /** Mean value for each hour of the day (0..23), null where no data. */
    fun hourlyProfile(readings: List<Reading>, metric: Metric, zone: ZoneId = ZoneId.systemDefault()): List<Float?> {
        val sums = DoubleArray(24)
        val counts = IntArray(24)
        for (r in readings) {
            val v = metric.of(r) ?: continue
            val h = Instant.ofEpochMilli(r.timestamp).atZone(zone).hour
            sums[h] += v.toDouble(); counts[h]++
        }
        return (0 until 24).map { if (counts[it] == 0) null else (sums[it] / counts[it]).toFloat() }
    }

    /** Daily means, most recent [maxDays] days that have data. */
    fun dailyMeans(
        readings: List<Reading>,
        metric: Metric,
        maxDays: Int = 14,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<LocalDate, Float>> {
        val map = sortedMapOf<LocalDate, Pair<Double, Int>>()
        for (r in readings) {
            val v = metric.of(r) ?: continue
            val d = Instant.ofEpochMilli(r.timestamp).atZone(zone).toLocalDate()
            val (s, c) = map[d] ?: (0.0 to 0)
            map[d] = (s + v) to (c + 1)
        }
        return map.entries.toList().takeLast(maxDays).map { (d, sc) -> d to (sc.first / sc.second).toFloat() }
    }

    /** Number of samples in each AQI category (from PM2.5/PM10). */
    fun aqiDistribution(readings: List<Reading>): Map<AqiCategory, Int> {
        val out = LinkedHashMap<AqiCategory, Int>()
        AqiCategory.entries.forEach { out[it] = 0 }
        for (r in readings) {
            val a = Aqi.compute(r.pm25, r.pm10) ?: continue
            out[a.category] = (out[a.category] ?: 0) + 1
        }
        return out
    }

    fun exceedanceFraction(readings: List<Reading>, metric: Metric, threshold: Float): Float? {
        val v = readings.mapNotNull { metric.of(it) }
        if (v.isEmpty()) return null
        return v.count { it > threshold }.toFloat() / v.size
    }

    /** Pearson correlation between two metrics over samples that have both. */
    fun pearson(readings: List<Reading>, a: Metric, b: Metric): Float? {
        val pairs = readings.mapNotNull { r ->
            val x = a.of(r); val y = b.of(r)
            if (x != null && y != null) x.toDouble() to y.toDouble() else null
        }
        if (pairs.size < 8) return null
        val mx = pairs.sumOf { it.first } / pairs.size
        val my = pairs.sumOf { it.second } / pairs.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((x, y) in pairs) {
            sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my)
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return (sxy / sqrt(sxx * syy)).toFloat()
    }

    /** Bucket-average downsampling to at most [max] points. */
    fun downsample(points: List<TimePoint>, max: Int): List<TimePoint> {
        if (points.size <= max || max < 2) return points
        val bucket = points.size.toDouble() / max
        val out = ArrayList<TimePoint>(max)
        var i = 0.0
        while (i < points.size) {
            val from = i.toInt()
            val to = minOf(points.size, (i + bucket).toInt().coerceAtLeast(from + 1))
            var st = 0.0; var sv = 0.0
            for (k in from until to) { st += points[k].t; sv += points[k].v }
            val n = to - from
            out.add(TimePoint((st / n).toLong(), (sv / n).toFloat()))
            i += bucket
        }
        return out
    }
}
