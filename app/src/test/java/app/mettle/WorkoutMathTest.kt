package app.mettle

import app.mettle.workout.Sample
import app.mettle.workout.WorkoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutMathTest {
    /** A straight run north at [speed] m/s for [seconds], one sample per second. */
    private fun run(seconds: Int, speed: Double, startLat: Double = 12.97, t0: Long = 0L, alt: (Int) -> Double = { 900.0 }): List<Sample> {
        val degPerM = 1 / 111_320.0
        return (0..seconds).map { s -> Sample(t0 + s * 1000L, startLat + s * speed * degPerM, 77.59, 5f, alt(s), null, s * 3) }
    }

    @Test fun distanceAndPaceOfSteadyRun() {
        val r = WorkoutMath.analyse(run(600, 3.33))          // 10 min at 5:00/km
        assertEquals(1998.0, r.distanceM.toDouble(), 15.0)
        assertEquals(600, r.movingS)
        assertEquals(300.0, r.avgPaceSKm!!, 5.0)
        assertEquals(1, r.splits.count { it.distanceM == 1000 })
        assertEquals(2, r.splits.size)   // 1 km + the remaining ~998 m
        assertEquals("run_1k", r.efforts.first { it.name == "run_1k" }.name)
        assertEquals(300.0, r.efforts.first { it.name == "run_1k" }.timeS.toDouble(), 3.0)
    }

    @Test fun standingStillAutoPauses() {
        val moving1 = run(120, 3.0)
        val last = moving1.last()
        val still = (1..60).map { i -> last.copy(tMs = last.tMs + i * 1000L) }
        val moving2 = run(120, 3.0, startLat = last.lat!!, t0 = last.tMs + 61_000L)
        val r = WorkoutMath.analyse(moving1 + still + moving2)
        assertEquals(301, r.elapsedS)
        assertTrue("moving ${r.movingS}", r.movingS in 240..250)
    }

    @Test fun gpsNoiseDoesNotAddClimb() {
        val r = WorkoutMath.analyse(run(300, 3.0) { s -> 900.0 + if (s % 2 == 0) 2.0 else -2.0 })
        assertEquals(0.0, r.elevGainM, 0.01)
    }

    @Test fun uphillMakesGapFasterThanPace() {
        val r = WorkoutMath.analyse(run(600, 3.0) { s -> 900.0 + s * 0.15 })   // 5% grade
        assertNotNull(r.gapSKm)
        assertTrue("gap ${r.gapSKm} pace ${r.avgPaceSKm}", r.gapSKm!! < r.avgPaceSKm!!)
        assertTrue(r.elevGainM > 80)
    }

    @Test fun simplifyKeepsEndsAndDropsStraightLinePoints() {
        val lat = (0..100).map { 12.97 + it * 0.0001 }
        val lon = List(101) { 77.59 }
        assertEquals(listOf(0, 100), WorkoutMath.simplify(lat, lon))
    }
}
