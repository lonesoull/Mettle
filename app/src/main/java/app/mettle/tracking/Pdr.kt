package app.mettle.tracking

import kotlin.math.cos
import kotlin.math.sin

/**
 * Step-and-compass positioning (pedestrian dead reckoning). Between GPS fixes, each step moves the
 * position by one stride in the compass direction, so movement of a step or two shows up even
 * indoors where GPS can't see it. A good GPS fix re-anchors the position, stopping drift.
 *
 * Uncertainty grows ~5% of the distance walked since the last anchor (compass and stride errors).
 */
class Pdr(private val strideM: Double) {
    var lat = 0.0; private set
    var lon = 0.0; private set
    var accuracyM = Double.MAX_VALUE; private set
    var anchored = false; private set
    private var sinceAnchorM = 0.0
    private var anchorAcc = 0.0

    /** A GPS/network fix: adopt it if it's better than what step counting currently knows. */
    fun fix(la: Double, lo: Double, acc: Double): Boolean {
        if (anchored && acc >= accuracyM) return false
        lat = la; lon = lo; anchorAcc = acc; accuracyM = acc; sinceAnchorM = 0.0; anchored = true
        return true
    }

    /** One step towards [headingDeg] (0 = north, 90 = east). */
    fun step(headingDeg: Double) {
        if (!anchored) return
        val h = Math.toRadians(headingDeg)
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        lat += strideM * cos(h) / mPerDegLat
        lon += strideM * sin(h) / mPerDegLon
        sinceAnchorM += strideM
        accuracyM = anchorAcc + 0.05 * sinceAnchorM
    }

    companion object {
        /** Typical walking stride from height (0.415 × height). */
        fun strideFor(heightCm: Int) = (heightCm * 0.415 / 100.0).coerceIn(0.45, 1.0)
    }
}
