package app.mettle.safety

import kotlin.math.sqrt

/**
 * Fall pattern on accelerometer magnitude (m/s²):
 *  1. free fall: |a| below 3.5 for at least 150 ms,
 *  2. impact: |a| above 24 (≈2.5 g) within 1 s after the free fall,
 *  3. stillness: the 6 s after impact stay close to 1 g with little movement.
 * Feed samples in time order; [add] returns true once when a fall is detected.
 */
class FallDetector {
    private data class S(val tMs: Long, val mag: Double)
    private val buf = ArrayDeque<S>()
    private var lastFallMs = Long.MIN_VALUE / 2
    private var candidateImpactMs: Long? = null

    fun add(tMs: Long, x: Float, y: Float, z: Float): Boolean {
        val mag = sqrt((x * x + y * y + z * z).toDouble())
        buf.addLast(S(tMs, mag))
        while (buf.isNotEmpty() && buf.first().tMs < tMs - 12_000) buf.removeFirst()

        val impact = candidateImpactMs
        if (impact == null) {
            if (mag > IMPACT && hadFreeFallBefore(tMs)) candidateImpactMs = tMs
            return false
        }
        if (tMs - impact < STILL_WINDOW_MS + 1000) return false
        candidateImpactMs = null
        val after = buf.filter { it.tMs in (impact + 1000)..(impact + 1000 + STILL_WINDOW_MS) }
        if (after.size < 10) return false
        val mean = after.map { it.mag }.average()
        val sd = sqrt(after.map { (it.mag - mean) * (it.mag - mean) }.average())
        val still = mean in 8.0..11.5 && sd < 1.2
        if (still && impact - lastFallMs > 60_000) { lastFallMs = impact; return true }
        return false
    }

    private fun hadFreeFallBefore(t: Long): Boolean {
        val window = buf.filter { it.tMs in (t - 1000)..t }
        var run = 0L; var start = -1L
        for (s in window) {
            if (s.mag < FREE_FALL) { if (start < 0) start = s.tMs; run = s.tMs - start; if (run >= 150) return true } else start = -1
        }
        return false
    }

    companion object {
        const val FREE_FALL = 3.5
        const val IMPACT = 24.0
        const val STILL_WINDOW_MS = 6_000L
    }
}
