package com.andrerinas.openheadunit.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripHeatTest {

    /** As read from a Lenovo K9 (MT6762) on 31 Oct 2026, trimmed. */
    private val k9 = """
        mtktsbattery 33000
        mtktscpu 38600
        mtktspa 43000
        mtktscharger 38000
        tzimgs0 -127000
        tzts1 38400
    """.trimIndent()

    @Test
    fun picksCpuAndBatteryZonesByType() {
        val r = TripHeat.parse(k9)!!
        assertEquals(38600, r.cpuMilliC)
        assertEquals(33000, r.batteryMilliC)
    }

    @Test
    fun takesTheHottestOfSeveralCpuZones() {
        val r = TripHeat.parse("cpu0-thermal 41000\ncpu1-thermal 47500\nbattery 30000")!!
        assertEquals(47500, r.cpuMilliC)
        assertEquals(30000, r.batteryMilliC)
    }

    @Test
    fun ignoresUnpluggedSensorsAndJunk() {
        val r = TripHeat.parse("mtktscpu -127000\nmtktscpu 39000\nbattery abc\n\nlonely")!!
        assertEquals(39000, r.cpuMilliC)
        assertEquals(-1, r.batteryMilliC)
    }

    @Test
    fun nothingUsefulIsNull() {
        assertNull(TripHeat.parse("tzts1 38400\nmtktspa 43000"))
        assertNull(TripHeat.parse(""))
    }
}
