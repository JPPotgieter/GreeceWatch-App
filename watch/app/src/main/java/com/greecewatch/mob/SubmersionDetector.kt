package com.greecewatch.mob

/**
 * Detects that the watch has gone under water using the barometer.
 *
 * Water adds roughly 1 hPa of pressure per centimetre of depth, while weather
 * changes air pressure by only a few hPa per hour. So a jump of [thresholdHpa]
 * above the recent air-pressure baseline, held for [confirmMs], means the wrist
 * is at least ~[thresholdHpa] cm under water.
 *
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 */
class SubmersionDetector(
    private val thresholdHpa: Float = 20f,
    private val confirmMs: Long = 400,
    private val baselineTauMs: Long = 60_000,
) {
    /** Recent air pressure, in hPa. Null until the first sample. */
    var baselineHpa: Float? = null
        private set

    /** Latest pressure minus baseline, in hPa. Roughly centimetres of water. */
    var deltaHpa: Float = 0f
        private set

    var triggered: Boolean = false
        private set

    private var aboveSinceMs: Long? = null
    private var rearmWhenDry = false
    private var lastSampleMs: Long? = null

    /** Feeds one barometer reading. Returns true exactly once, on the sample that confirms submersion. */
    fun onSample(pressureHpa: Float, timeMs: Long): Boolean {
        val baseline = baselineHpa
        val previousMs = lastSampleMs
        lastSampleMs = timeMs
        if (baseline == null || previousMs == null) {
            baselineHpa = pressureHpa
            return false
        }

        deltaHpa = pressureHpa - baseline

        if (deltaHpa >= thresholdHpa) {
            val since = aboveSinceMs ?: timeMs.also { aboveSinceMs = it }
            if (!triggered && timeMs - since >= confirmMs) {
                triggered = true
                return true
            }
            return false
        }

        aboveSinceMs = null
        if (rearmWhenDry && deltaHpa < thresholdHpa / 2) {
            rearmWhenDry = false
            triggered = false
        }
        // Only learn the baseline from readings that look like air, so a slow
        // entry into the water can't drag the baseline down with it.
        if (deltaHpa < thresholdHpa / 2) {
            val dt = (timeMs - previousMs).coerceAtLeast(0)
            val alpha = dt.toFloat() / (baselineTauMs + dt)
            baselineHpa = baseline + alpha * (pressureHpa - baseline)
        }
        return false
    }

    /**
     * Re-arms after an alert has been dealt with, once the watch is back in air,
     * so pressing "I'm safe" while still swimming doesn't fire a new alert.
     */
    fun reset() {
        if (triggered) rearmWhenDry = true
        aboveSinceMs = null
    }
}
