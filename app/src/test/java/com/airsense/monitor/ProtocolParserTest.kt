package com.airsense.monitor

import com.airsense.monitor.sensor.Field
import com.airsense.monitor.sensor.ProtocolParser
import com.airsense.monitor.util.Aqi
import com.airsense.monitor.util.AqiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolParserTest {

    @Test fun parsesFirmwareLines() {
        assertEquals(mapOf(Field.HUMIDITY to 35f), ProtocolParser.parse("humi:35.00"))
        assertEquals(mapOf(Field.TEMPERATURE to 29f), ProtocolParser.parse("temp:29.00"))
        assertEquals(mapOf(Field.GAS to 1.18f), ProtocolParser.parse("CO:1.18"))
        assertEquals(mapOf(Field.PM1 to 7f), ProtocolParser.parse("PM1.0=7.0 ug/m3"))
        assertEquals(mapOf(Field.PM25 to 19f), ProtocolParser.parse("PM2.5=19.0 ug/m3"))
        assertEquals(mapOf(Field.PM10 to 19f), ProtocolParser.parse("PM10=19.0 ug/m3"))
    }

    @Test fun ignoresEchoAndNoise() {
        assertTrue(ProtocolParser.parse("H").isEmpty())
        assertTrue(ProtocolParser.parse("P").isEmpty())
        assertTrue(ProtocolParser.parse("").isEmpty())
    }

    @Test fun parsesCombinedLine() {
        val m = ProtocolParser.parse("PM1=3 PM2.5=12.5 PM10=20, Temperature = 24.5 RH=40 CO2:415")
        assertEquals(3f, m[Field.PM1])
        assertEquals(12.5f, m[Field.PM25])
        assertEquals(20f, m[Field.PM10])
        assertEquals(24.5f, m[Field.TEMPERATURE])
        assertEquals(40f, m[Field.HUMIDITY])
        assertEquals(415f, m[Field.GAS])
    }

    @Test fun aqiBreakpoints() {
        assertEquals(50, Aqi.fromPm25(9.0f))
        assertEquals(51, Aqi.fromPm25(9.1f))
        assertEquals(100, Aqi.fromPm25(35.4f))
        assertEquals(101, Aqi.fromPm25(35.5f))
        assertEquals(50, Aqi.fromPm10(54f))
        val r = Aqi.compute(19f, 19f)!!
        assertEquals("PM2.5", r.dominant)
        assertEquals(AqiCategory.MODERATE, r.category)
    }
}
