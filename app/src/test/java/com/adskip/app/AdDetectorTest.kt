package com.adskip.app

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectorTest {

    private val ads = listOf(72.0 to 132.0, 228.0 to 288.0)
    private val burst = 150.0 to 158.0

    /** Runs a simulated 6-minute clip. Returns (true = ad started / false = show back, seconds). */
    private fun events(
        mode: Mode,
        adList: List<Pair<Double, Double>>,
        burstSpan: Pair<Double, Double>?,
        totalSeconds: Int = 360
    ): List<Pair<Boolean, Double>> {
        val d = AdDetector()
        d.mode = mode
        val rnd = Random(7)
        var wander = 0.0
        val out = ArrayList<Pair<Boolean, Double>>()
        for (i in 0 until totalSeconds * 2) {
            val t = i * 0.5
            val realAd = adList.any { t >= it.first && t < it.second }
            val inBurst = burstSpan != null && t >= burstSpan.first && t < burstSpan.second
            // Fast-forwarding makes the picture change constantly and removes the sound.
            val ff = d.inAd && mode == Mode.RECORDED
            val cut = if (ff) rnd.nextDouble() < 0.5
            else rnd.nextDouble() < (if (realAd) 0.34 else if (inBurst) 0.3 else 0.07)
            wander = wander * 0.9 + (rnd.nextDouble() - 0.5) * 1.2
            val db = if (d.inAd) -90.0
            else (if (realAd) -19.5 else -24.5) + wander + (rnd.nextDouble() - 0.5) * 1.5
            val logo = if (realAd) rnd.nextDouble() < 0.12 else rnd.nextDouble() < 0.96
            val r = d.step(AdDetector.Step(cut, logo, db))
            if (r.changed) out.add(r.inAd to t)
        }
        return out
    }

    private fun checkBreaks(ev: List<Pair<Boolean, Double>>) {
        assertEquals(4, ev.size)
        assertTrue("first ad start", ev[0].first && ev[0].second in 72.0..90.0)
        assertTrue("first ad end", !ev[1].first && ev[1].second in 132.0..152.0)
        assertTrue("second ad start", ev[2].first && ev[2].second in 228.0..246.0)
        assertTrue("second ad end", !ev[3].first && ev[3].second in 288.0..308.0)
    }

    @Test
    fun liveModeFindsBothBreaksAndIgnoresActionScene() {
        checkBreaks(events(Mode.LIVE, ads, burst))
    }

    @Test
    fun recordedModeFindsBothBreaksAndIgnoresActionScene() {
        checkBreaks(events(Mode.RECORDED, ads, burst))
    }

    @Test
    fun regularlySpacedBreaksAreAllFoundAndTheActionSceneIsStillIgnored() {
        val longAds = listOf(70.0 to 130.0, 490.0 to 550.0, 910.0 to 970.0)
        val ev = events(Mode.LIVE, longAds, 700.0 to 708.0, 1100)
        assertEquals(6, ev.size)
        assertTrue("third break found", ev[4].first && ev[4].second in 910.0..930.0)
        assertTrue("third break over", !ev[5].first && ev[5].second in 970.0..992.0)
    }

    @Test
    fun showWithNoAdsIsNeverFlagged() {
        assertEquals(0, events(Mode.LIVE, emptyList(), burst).size)
        assertEquals(0, events(Mode.RECORDED, emptyList(), burst).size)
    }
}
