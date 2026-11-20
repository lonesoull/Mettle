package app.mettle

import app.mettle.stairs.Direction
import app.mettle.stairs.StairMath
import org.junit.Assert.assertEquals
import org.junit.Test

class StairMathTest {
    /** [n] steps every [gapMs] starting at [t0]. */
    private fun steps(t0: Long, n: Int, gapMs: Long = 600) = (0 until n).map { t0 + it * gapMs }

    @Test fun noBarometerAlternatesUpAndDownBetweenRests() {
        // 10 sets: 15 floors up (330 steps at 22/floor), 60 s rest, 15 floors down, 30 s rest.
        val all = ArrayList<Long>(); var t = 0L
        repeat(10) {
            all += steps(t, 330); t += 330 * 600 + 60_000
            all += steps(t, 330, 450); t += 330 * 450 + 30_000
        }
        val r = StairMath.analyse(all, null, stepsPerFloor = 22, floorHeightM = 3.0)
        assertEquals(150, r.floorsUp)
        assertEquals(150, r.floorsDown)
        assertEquals(10, r.sets.size)
        assertEquals(15, r.sets[0].floorsUp)
        assertEquals(197, r.sets[0].climbS)   // 329 gaps × 0.6 s
        assertEquals(1, r.efforts.count { it.name == "stairs_up_50fl" })
    }

    @Test fun barometerDecidesDirectionAndDetectsLift() {
        val climb = steps(0, 110)                                // 66 s of stepping
        val alt = ArrayList<Pair<Long, Double>>()
        for (s in 0..66) alt += s * 1000L to 900.0 + s * (15.0 / 66)          // +15 m = 5 floors
        for (s in 67..120) alt += s * 1000L to 915.0                          // standing
        for (s in 121..150) alt += s * 1000L to 915.0 - (s - 121) * 1.0       // lift down 30 m, no steps
        for (s in 151..200) alt += s * 1000L to 885.0
        val r = StairMath.analyse(climb, alt, stepsPerFloor = 22, floorHeightM = 3.0)
        assertEquals(5, r.floorsUp)
        assertEquals(0, r.floorsDown)
        assertEquals(10, r.liftDown)
        assertEquals(Direction.UP, r.bouts.first().direction)
    }

    @Test fun stairMachineCountsEverythingUp() {
        val r = StairMath.analyse(steps(0, 320), null, stepsPerFloor = 16, floorHeightM = 3.0, machine = true)
        assertEquals(20, r.floorsUp); assertEquals(0, r.floorsDown)
    }

    @Test fun manualLapSplitsABout() {
        val s = steps(0, 220)
        val r = StairMath.analyse(s, null, stepsPerFloor = 22, floorHeightM = 3.0, laps = listOf(s[110] - 1))
        assertEquals(5, r.floorsUp); assertEquals(5, r.floorsDown)
    }
}
