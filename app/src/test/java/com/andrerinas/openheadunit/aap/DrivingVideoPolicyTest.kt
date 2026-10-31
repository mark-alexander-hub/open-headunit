package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingVideoPolicyTest {

    @Test
    fun startsParked() {
        assertFalse(DrivingVideoPolicy().moving)
    }

    @Test
    fun movingLocksAtOnceAndReportsOnlyTheChange() {
        val p = DrivingVideoPolicy()
        assertEquals(true, p.feed(5f, 0))
        assertNull(p.feed(8f, 1_000))
        assertTrue(p.moving)
    }

    @Test
    fun aShortStopKeepsVideoOff() {
        val p = DrivingVideoPolicy()
        p.feed(10f, 0)
        for (t in 1..59) assertNull(p.feed(0f, t * 1_000L))
        assertTrue(p.moving)
    }

    @Test
    fun aMinuteStandingStillUnlocks() {
        val p = DrivingVideoPolicy()
        p.feed(10f, 0)
        assertNull(p.feed(0f, 1_000))
        assertEquals(false, p.feed(0.3f, 61_000))
        assertFalse(p.moving)
    }

    @Test
    fun creepingRestartsTheWait() {
        val p = DrivingVideoPolicy()
        p.feed(10f, 0)
        p.feed(0f, 1_000)
        assertNull(p.feed(1.5f, 40_000)) // crawling forward in a queue
        assertNull(p.feed(0f, 41_000))
        assertNull(p.feed(0f, 100_000))  // 59 s since the crawl
        assertEquals(false, p.feed(0f, 101_000))
    }

    @Test
    fun gpsDriftWhileParkedDoesNotLock() {
        val p = DrivingVideoPolicy()
        listOf(0.4f, 1.8f, 2.2f, 0.9f).forEachIndexed { i, s -> assertNull(p.feed(s, i * 1_000L)) }
        assertFalse(p.moving)
    }
}
