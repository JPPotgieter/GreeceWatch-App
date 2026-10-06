package com.greecewatch.mob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmersionDetectorTest {
    private val air = 1013.25f

    /** Feeds samples every [stepMs] and returns the times at which the detector fired. */
    private fun SubmersionDetector.feed(fromMs: Long, toMs: Long, stepMs: Long = 100, pressure: (Long) -> Float): List<Long> =
        (fromMs until toMs step stepMs).filter { t -> onSample(pressure(t), t) }

    @Test
    fun `jumping in fires once`() {
        val d = SubmersionDetector()
        assertTrue(d.feed(0, 30_000) { air }.isEmpty())
        // Plunge to 1.5 m, then bob near the surface at 30 cm.
        val fired = d.feed(30_000, 40_000) { t -> if (t < 32_000) air + 150 else air + 30 }
        assertEquals(listOf(30_400L), fired)
        assertTrue(d.triggered)
    }

    @Test
    fun `brief spike is ignored`() {
        val d = SubmersionDetector()
        d.feed(0, 10_000) { air }
        val fired = d.feed(10_000, 20_000) { t -> if (t in 10_000..10_200) air + 50 else air }
        assertTrue(fired.isEmpty())
        assertFalse(d.triggered)
    }

    @Test
    fun `wave splash and hand washing depth is ignored`() {
        val d = SubmersionDetector()
        d.feed(0, 10_000) { air }
        assertTrue(d.feed(10_000, 20_000) { air + 8 }.isEmpty())
    }

    @Test
    fun `weather change and going below deck are ignored`() {
        val d = SubmersionDetector()
        // Front passing: -6 hPa over 10 minutes, plus companionway step of +0.3 hPa.
        val fired = d.feed(0, 600_000, stepMs = 1_000) { t -> air - 6f * t / 600_000 + if (t > 300_000) 0.3f else 0f }
        assertTrue(fired.isEmpty())
        assertEquals(air - 6f, d.baselineHpa!!, 1f)
    }

    @Test
    fun `slow entry still fires because baseline stops learning`() {
        val d = SubmersionDetector()
        d.feed(0, 10_000) { air }
        // Sliding in: depth grows 1 cm per 100 ms.
        val fired = d.feed(10_000, 20_000) { t -> air + (t - 10_000) / 100f }
        assertEquals(1, fired.size)
    }

    @Test
    fun `reset allows a second alert after getting out of the water`() {
        val d = SubmersionDetector()
        d.feed(0, 5_000) { air }
        assertEquals(1, d.feed(5_000, 7_000) { air + 100 }.size)
        d.reset()
        // Still swimming after pressing "I'm safe": no new alert.
        assertTrue(d.feed(7_000, 20_000) { air + 60 }.isEmpty())
        // Back on deck, then overboard again.
        d.feed(20_000, 25_000) { air }
        assertFalse(d.triggered)
        assertEquals(1, d.feed(25_000, 27_000) { air + 100 }.size)
    }
}
