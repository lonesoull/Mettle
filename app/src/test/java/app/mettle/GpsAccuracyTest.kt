package app.mettle

import app.mettle.workout.Sample
import app.mettle.workout.WorkoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos

/** Simulated GPS with realistic noise: checks distance is close to the true distance. */
class GpsAccuracyTest {
    private val lat0 = 13.0; private val lon0 = 80.2
    private val mLat = 111_320.0; private val mLon = 111_320.0 * cos(Math.toRadians(lat0))

    /** A run at [speed] m/s along a route given as metres (x east, y north) per second, with GPS noise of [noise] m. */
    private fun run(path: (Int) -> Pair<Double, Double>, seconds: Int, noise: Double, speed: Float?, seed: Long = 1,
                    segmentAt: (Int) -> Int = { 0 }): List<Sample> {
        val r = Random(seed)
        var ex = 0.0; var ey = 0.0   // GPS error drifts slowly (correlated), like real receivers
        return (0..seconds).map { s ->
            ex = 0.8 * ex + 0.2 * r.nextGaussian() * noise * 2.2
            ey = 0.8 * ey + 0.2 * r.nextGaussian() * noise * 2.2
            val (x, y) = path(s)
            Sample(s * 1000L, lat0 + (y + ey) / mLat, lon0 + (x + ex) / mLon, noise.toFloat(), 10.0, null, null,
                speed?.let { (it + r.nextGaussian() * 0.15).toFloat().coerceAtLeast(0f) }, segmentAt(s))
        }
    }

    @Test fun straightRunWithinTwoPercent() {
        val res = WorkoutMath.analyse(run({ s -> s * 3.0 to 0.0 }, 1000, 4.0, 3f))   // 3000 m true
        val err = kotlin.math.abs(res.distanceM - 3000) / 3000.0; println("straight: ${res.distanceM} m of 3000")
        assertTrue("distance ${res.distanceM} m, error ${"%.1f".format(err * 100)}%", err < 0.02)
    }

    @Test fun loopRunWithinThreePercent() {
        // 400 m track, 5 laps at 3.5 m/s.
        val rad = 400 / (2 * Math.PI)
        val res = WorkoutMath.analyse(run({ s -> val a = s * 3.5 / rad; rad * cos(a) to rad * kotlin.math.sin(a) }, 571, 4.0, 3.5f))
        val err = kotlin.math.abs(res.distanceM - 2000) / 2000.0; println("loop: ${res.distanceM} m of 2000")
        assertTrue("distance ${res.distanceM} m, error ${"%.1f".format(err * 100)}%", err < 0.03)
    }

    @Test fun standingStillAddsAlmostNothing() {
        val res = WorkoutMath.analyse(run({ 0.0 to 0.0 }, 300, 5.0, 0f))   // 5 min at a crossing
        println("still: ${res.distanceM} m"); assertTrue("drift ${res.distanceM} m", res.distanceM < 15)
    }

    @Test fun pausedTravelIsNotCounted() {
        // Run 600 m, pause and walk 500 m away (not recorded), resume and run 600 m.
        val samples = run({ s -> s * 3.0 to 0.0 }, 200, 3.0, 3f) +
            run({ s -> 1100.0 + s * 3.0 to 0.0 }, 200, 3.0, 3f, seed = 2).map { it.copy(tMs = it.tMs + 600_000, segment = 1) }
        val res = WorkoutMath.analyse(samples)
        println("paused: ${res.distanceM} m of 1200"); assertEquals(1200.0, res.distanceM.toDouble(), 30.0)
    }
}
