package com.airsense.monitor.sensor

/** Values the firmware can report. */
enum class Field { PM1, PM25, PM10, TEMPERATURE, HUMIDITY, GAS }

/**
 * Single-character request commands understood by the firmware.
 * The device answers e.g. `humi:35.00`, `temp:29.00`, `CO:1.18`,
 * `PM1.0=7.0 ug/m3` / `PM2.5=19.0 ug/m3` / `PM10=19.0 ug/m3`, then echoes the command letter.
 */
enum class SensorCommand(val code: Char, val fields: Set<Field>) {
    HUMIDITY('H', setOf(Field.HUMIDITY)),
    TEMPERATURE('T', setOf(Field.TEMPERATURE)),
    GAS('C', setOf(Field.GAS)),
    PARTICLES('P', setOf(Field.PM1, Field.PM25, Field.PM10)),
}

data class SensorSnapshot(
    val timestamp: Long,
    val pm1: Float? = null,
    val pm25: Float? = null,
    val pm10: Float? = null,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val gas: Float? = null,
) {
    val isEmpty: Boolean
        get() = pm1 == null && pm25 == null && pm10 == null && temperature == null && humidity == null && gas == null

    companion object {
        fun from(timestamp: Long, values: Map<Field, Float>) = SensorSnapshot(
            timestamp = timestamp,
            pm1 = values[Field.PM1],
            pm25 = values[Field.PM25],
            pm10 = values[Field.PM10],
            temperature = values[Field.TEMPERATURE],
            humidity = values[Field.HUMIDITY],
            gas = values[Field.GAS],
        )
    }
}

/**
 * Tolerant line parser: accepts `key:value` or `key=value`, any case, optional units,
 * and several spellings (humi/humidity/rh, temp/temperature, co/co2/gas/mq135).
 * A line may contain more than one value.
 */
object ProtocolParser {
    private const val NUM = """(-?\d+(?:[.,]\d+)?)"""
    private val patterns: List<Pair<Field, Regex>> = listOf(
        Field.PM10 to Regex("""\bpm\s*10(?![.,]?\d)\s*[:=]\s*$NUM""", RegexOption.IGNORE_CASE),
        Field.PM25 to Regex("""\bpm\s*2[.,_]?5\s*[:=]\s*$NUM""", RegexOption.IGNORE_CASE),
        Field.PM1 to Regex("""\bpm\s*1(?:[.,]0)?(?!\d)\s*[:=]\s*$NUM""", RegexOption.IGNORE_CASE),
        Field.HUMIDITY to Regex("""\b(?:humi(?:dity)?|hum|rh)\s*[:=]\s*$NUM""", RegexOption.IGNORE_CASE),
        Field.TEMPERATURE to Regex("""\btemp(?:erature)?\s*[:=]\s*$NUM""", RegexOption.IGNORE_CASE),
        Field.GAS to Regex("""\b(?:co2?|gas|mq-?135|voc|air)\s*[:=]\s*$NUM""", RegexOption.IGNORE_CASE),
    )

    fun parse(line: String): Map<Field, Float> {
        val out = LinkedHashMap<Field, Float>()
        for ((field, regex) in patterns) {
            val m = regex.find(line) ?: continue
            m.groupValues[1].replace(',', '.').toFloatOrNull()?.let { if (it.isFinite()) out[field] = it }
        }
        return out
    }
}
