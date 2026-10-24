package com.victor.signalscanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DspTest {
    @Test fun goertzelFindsTone() {
        val fs = 200.0
        val x = DoubleArray(400) { 0.8 * sin(2 * PI * 60 * it / fs) }
        assertEquals(0.8, Dsp.toneAmplitude(x, fs, 60.0), 0.05)
        assertTrue(Dsp.toneAmplitude(x, fs, 50.0) < 0.1)
    }
    @Test fun alias() {
        assertEquals(40.0, Dsp.alias(60.0, 100.0), 1e-9)
        assertEquals(50.0, Dsp.alias(50.0, 100.0), 1e-9)
        assertEquals(10.0, Dsp.alias(60.0, 50.0), 1e-9)
    }
    @Test fun aliasedHumDetectable() {
        val fs = 100.0
        val x = DoubleArray(200) { 0.5 * sin(2 * PI * 60 * it / fs) }
        assertEquals(0.5, Dsp.toneAmplitude(x, fs, Dsp.alias(60.0, fs)), 0.05)
    }
    @Test fun fftPeakAtRightBin() {
        val n = 4096; val fs = 48000.0; val f = 18750.0  // exactly bin 1600
        val re = FloatArray(n) { sin(2 * PI * f * it / fs).toFloat() }; val im = FloatArray(n)
        Dsp.fft(re, im)
        val mags = (0 until n / 2).map { hypot(re[it], im[it]) }
        assertEquals(1600, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2.0, mags[1600].toDouble(), 1.0)
    }
    @Test fun peakDetection() {
        val db = FloatArray(2048) { -110f }; db[1600] = -60f; db[1599] = -75f; db[1601] = -75f
        val (floor, pk) = UltraDsp.peaks(db, 1280, 1877, 11.71875)
        assertEquals(-110f, floor); assertEquals(1, pk.size); assertEquals(18750.0, pk[0].first, 0.1)
    }
    @Test fun toneTrackerPersistence() {
        val tr = ToneTracker()
        for (i in 0..30) tr.update(i * 85L, listOf(18750.0 + (i % 2) * 11.7 to -60f), -110f)   // ~2.6 s steady tone
        assertEquals(1, tr.persistent(30 * 85L).size)
        tr.update(4000, emptyList(), -110f)
        assertEquals(1, tr.log.size); assertTrue(tr.log[0].durationMs >= 2000); assertEquals(50f, tr.log[0].aboveFloorDb)
        // a short blip is not logged
        tr.update(5000, listOf(17000.0 to -70f), -110f); tr.update(5200, listOf(17000.0 to -70f), -110f); tr.update(6000, emptyList(), -110f)
        assertEquals(1, tr.log.size)
    }
}
