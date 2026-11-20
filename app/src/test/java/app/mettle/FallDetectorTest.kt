package app.mettle

import app.mettle.safety.FallDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class FallDetectorTest {
    /** Feeds 50 Hz samples of magnitude f(t) along z; returns how many falls were detected. */
    private fun run(seconds: Double, f: (Long) -> Float): Int {
        val d = FallDetector(); var n = 0
        var t = 0L
        while (t <= (seconds * 1000).toLong()) { if (d.add(t, 0f, 0f, f(t))) n++; t += 20 }
        return n
    }

    @Test fun realFallIsDetected() {
        assertEquals(1, run(12.0) { t ->
            when {
                t < 2000 -> 9.8f + (if (t % 400 < 200) 1.5f else -1.5f)   // walking
                t < 2300 -> 1.0f                                          // free fall 300 ms
                t < 2360 -> 32f                                           // impact
                else -> 9.75f + (t % 3) * 0.05f                           // lying still
            }
        })
    }

    @Test fun droppingThePhoneButPickingItUpIsNotAFall() {
        assertEquals(0, run(12.0) { t ->
            when {
                t < 2000 -> 9.8f
                t < 2300 -> 1.0f
                t < 2360 -> 32f
                else -> 9.8f + (if (t % 300 < 150) 4f else -4f)            // picked up and walking again
            }
        })
    }

    @Test fun joggingIsNotAFall() {
        assertEquals(0, run(20.0) { t -> 9.8f + (if (t % 350 < 175) 14f else -7f) })
    }

    @Test fun sittingDownHardIsNotAFall() {
        assertEquals(0, run(12.0) { t -> if (t in 2000..2060) 26f else 9.8f })   // impact without free fall
    }
}
