package com.victor.signalscanner

import org.junit.Assert.*
import org.junit.Test

class TrackerTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private val home = Home(61.2000, -149.9000, 250.0)
    private val rule = TrackerRule()
    private val t0 = 1_760_000_000_000L
    private val min = 60_000L
    // ~0.0045 deg latitude ≈ 500 m
    private fun s(m: Long, lat: Double?, lon: Double?, rssi: Int = -70) = Sighting(t0 + m * min, lat, lon, 20f, rssi)

    @Test fun classifySeparatedAirTag() {
        val key = IntArray(22) { it + 1 }
        val d = b(0x12, 0x19, 0x10, *key, 0x00, 0x00)   // status 0x10 -> AirTag, 25 bytes = separated
        val h = TrackerClassify.classify("C1:22:33:44:55:66", mapOf(0x004C to d), emptyMap(), emptySet())!!
        assertEquals("AirTag", h.type); assertEquals(true, h.separated); assertEquals("FM-C1:22:33:44:55:66", h.id); assertEquals("010203040506", h.payload)
    }

    @Test fun classifyOwnerNearbyFindMy() {
        val h = TrackerClassify.classify("D0:70:68:47:E5:13", mapOf(0x004C to b(0x12, 0x02, 0x00, 0x01)), emptyMap(), emptySet())!!
        assertEquals(false, h.separated); assertEquals("Apple device (Find My)", h.type)
    }

    @Test fun classifyAfterOtherAppleTypes() {
        // Nearby Info (0x10) followed by Find My (0x12)
        val h = TrackerClassify.classify("AA", mapOf(0x004C to b(0x10, 0x03, 1, 2, 3, 0x12, 0x02, 0x30, 0x00)), emptyMap(), emptySet())
        assertNotNull(h); assertEquals("AirPods (Find My)", h!!.type)
        assertNull(TrackerClassify.classify("AA", mapOf(0x004C to b(0x10, 0x03, 1, 2, 3)), emptyMap(), emptySet()))
    }

    @Test fun classifyOthers() {
        assertEquals("samsung", TrackerClassify.classify("A", emptyMap(), mapOf(0xFD5A to b(0x42, 1, 2)), emptySet())!!.family)
        assertEquals("tile", TrackerClassify.classify("A", emptyMap(), emptyMap(), setOf(0xFEED))!!.family)
        assertEquals("chipolo", TrackerClassify.classify("A", emptyMap(), emptyMap(), setOf(0xFE33))!!.family)
        val g = TrackerClassify.classify("A", emptyMap(), mapOf(0xFEAA to b(0x41, 1, 2)), emptySet())!!
        assertEquals("google", g.family); assertEquals(true, g.separated)
        assertNull(TrackerClassify.classify("A", emptyMap(), mapOf(0xFEAA to b(0x10, 1)), emptySet()))  // plain Eddystone URL
        assertEquals("dult", TrackerClassify.classify("A", emptyMap(), mapOf(0xFCB2 to b(1)), emptySet())!!.family)
        assertNull(TrackerClassify.classify("A", emptyMap(), mapOf(0xFE2C to b(1, 2, 3)), emptySet()))   // Fast Pair headphones are not trackers
    }

    @Test fun homeOnlyNeverAlerts() {
        val ss = (0..300 step 5).map { s(it.toLong(), 61.2000 + (it % 3) * 0.0003, -149.9000) }   // 5 h at home
        val v = TrackerRules.evaluate(ss, home, rule, false)
        assertFalse(v.following); assertTrue(v.allAtHome); assertEquals(1, v.places.size)
    }

    @Test fun sameSpotWithoutHomeDoesNotAlert() {
        val v = TrackerRules.evaluate((0..120 step 5).map { s(it.toLong(), 61.25, -149.85) }, null, rule, false)
        assertFalse(v.following); assertEquals(1, v.places.size)
    }

    @Test fun threePlacesAlert() {
        val ss = listOf(s(0, 61.2000, -149.9000), s(5, 61.2001, -149.9001), s(15, 61.2100, -149.9000), s(25, 61.2200, -149.9000))
        val v = TrackerRules.evaluate(ss, home, rule, false)
        assertTrue(v.reason, v.following); assertEquals(3, v.places.size); assertTrue(v.travelledM > 2000)
    }

    @Test fun twoPlacesShortTimeNoAlert() {
        val v = TrackerRules.evaluate(listOf(s(0, 61.2100, -149.9), s(10, 61.2200, -149.9)), home, rule, false)
        assertFalse(v.following); assertEquals(2, v.places.size)
    }

    @Test fun longTimeWhileMovingAlerts() {
        // drives slowly 600 m over 40 min, in 150 m steps (all inside "one place" clusters of 500 m)
        val ss = (0..4).map { s(it * 10L, 61.3000 + it * 0.00135, -149.8) }
        val v = TrackerRules.evaluate(ss, null, rule, false)
        assertTrue(v.reason, v.following); assertTrue(v.reason.startsWith("With you"))
    }

    @Test fun longTimeStationaryNoAlert() {
        val v = TrackerRules.evaluate((0..90 step 10).map { s(it.toLong(), 61.3, -149.8) }, null, rule, false)
        assertFalse(v.following)
    }

    @Test fun trustedNeverAlerts() {
        val ss = listOf(s(0, 61.21, -149.9), s(15, 61.22, -149.9), s(25, 61.23, -149.9))
        assertFalse(TrackerRules.evaluate(ss, home, rule, true).following)
        assertTrue(TrackerRules.evaluate(ss, home, rule, false).following)
    }

    @Test fun configurableThresholds() {
        val ss = listOf(s(0, 61.21, -149.9), s(15, 61.22, -149.9))
        assertTrue(TrackerRules.evaluate(ss, home, TrackerRule(minPlaces = 2), false).following)
        assertFalse(TrackerRules.evaluate(ss, home, TrackerRule(minPlaces = 2, placeSepM = 2000.0, minMoveM = 2000.0), false).following)
    }

    @Test fun sightingsWithoutLocation() {
        val v = TrackerRules.evaluate((0..60 step 10).map { s(it.toLong(), null, null) }, home, rule, false)
        assertFalse(v.following); assertEquals(0, v.located)
    }

    @Test fun distance() {
        val d = TrackerRules.distM(61.2, -149.9, 61.2045, -149.9)
        assertTrue("$d", d in 495.0..505.0)
    }
}
