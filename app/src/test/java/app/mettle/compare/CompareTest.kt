package app.mettle.compare

import app.mettle.workout.Sample
import app.mettle.workout.WorkoutMath
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Side by side: Mettle's current distance maths vs the OpenTracks method
 * (±50 m cut-off, store a point every 10 m, >200 m jump = new segment, sum of straight lines).
 * Many runs per scenario with different random GPS noise; prints average error.
 */
class CompareTest {
    private val lat0 = 13.0; private val lon0 = 80.2
    private val mLat = 111_320.0; private val mLon = 111_320.0 * cos(Math.toRadians(lat0))

    private fun openTracks(samples: List<Sample>): Double {
        var total = 0.0; var last: Sample? = null
        for (s in samples) {
            if (s.lat == null || (s.accuracyM ?: 0f) > 50f) continue
            val l = last
            if (l == null) { last = s; continue }
            val d = WorkoutMath.haversineM(l.lat!!, l.lon!!, s.lat, s.lon!!)
            if (d > 200) { last = s; continue }          // new segment, jump not counted
            if (d >= 10) { total += d; last = s }
        }
        return total
    }

    /** path(t) gives true metres (x east, y north) at second t; returns (samples, true distance). */
    var slow = true; var speedMissing = 0.0
    private fun simulate(seconds: Int, noise: Double, seed: Long, multipath: Double = 0.0, dopplerNoise: Double = dop,
                         path: (Double) -> Pair<Double, Double>): Pair<List<Sample>, Double> {
        val r = Random(seed); var ex = 0.0; var ey = 0.0; var truth = 0.0
        var prev = path(0.0)
        val out = ArrayList<Sample>()
        for (t in 0..seconds) {
            val (x, y) = path(t.toDouble())
            val step = sqrt((x - prev.first) * (x - prev.first) + (y - prev.second) * (y - prev.second)); truth += step; prev = x to y
            // Realistic GPS error: slowly wandering (correlated), not white noise.
            val k = if (slow) 0.983 else 0.85
            val amp = noise * sqrt(1 - k * k)
            ex = k * ex + r.nextGaussian() * amp
            ey = k * ey + r.nextGaussian() * amp
            var jx = 0.0; var jy = 0.0; var acc = noise.toFloat()
            if (multipath > 0 && r.nextDouble() < multipath) { jx = r.nextGaussian() * 40; jy = r.nextGaussian() * 40; acc = (noise * 2).toFloat() }
            val (nx, ny) = path(t + 0.5); val sp = sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y)) * 2
            // Chip speed: noisy, biased upward when nearly still (it can't go below 0), sometimes missing.
            val speed = if (r.nextDouble() < speedMissing) null else abs(sp + r.nextGaussian() * dopplerNoise).toFloat()
            out += Sample(t * 1000L, lat0 + (y + ey + jy) / mLat, lon0 + (x + ex + jx) / mLon, acc, null, null, null, speed, 0)
        }
        return out to truth
    }

    private fun scenario(name: String, runs: Int = 20, make: (Long) -> Pair<List<Sample>, Double>) {
        var errM = 0.0; var errO = 0.0; var worstM = 0.0; var worstO = 0.0; var truthSum = 0.0
        for (seed in 1L..runs) {
            val (s, truth) = make(seed)
            val m = WorkoutMath.analyse(s).distanceM.toDouble(); val o = openTracks(s)
            val em = if (truth > 0) abs(m - truth) / truth * 100 else m; val eo = if (truth > 0) abs(o - truth) / truth * 100 else o
            errM += em; errO += eo; worstM = maxOf(worstM, em); worstO = maxOf(worstO, eo); truthSum += truth
        }
        val unit = if (truthSum == 0.0) " m" else "%"
        println("RESULT|$name|Mettle avg ${"%.1f".format(errM / runs)}$unit worst ${"%.1f".format(worstM)}$unit|OpenTracks avg ${"%.1f".format(errO / runs)}$unit worst ${"%.1f".format(worstO)}$unit")
    }

    @Test fun compare() {
        println("RESULT|--- realistic: slow GPS drift, speed ±0.4 m/s ---||")
        slow = true; speedMissing = 0.0; runAll()
        println("RESULT|--- harsh: fast GPS jitter, speed ±0.8 m/s, 30% speed missing ---||")
        slow = false; speedMissing = 0.3
        runAll(doppler = 0.8)
    }

    private var dop = 0.4
    private fun runAll(doppler: Double = 0.4) { dop = doppler
        // 1. Straight road run, good open-sky GPS (±4 m), 3 m/s for 1000 s
        scenario("Straight run, open sky") { simulate(1000, 4.0, it) { t -> t * 3.0 to 0.0 } }
        // 2. Track: 5 laps of 400 m
        scenario("Track 5 laps") { seed -> val rad = 400 / (2 * Math.PI); simulate(571, 4.0, seed) { t -> val a = t * 3.5 / rad; rad * cos(a) to rad * sin(a) } }
        // 3. Twisty park path (zig-zag every 15 m), walking 1.4 m/s
        scenario("Twisty path, walking") { simulate(900, 4.0, it) { t -> val d = t * 1.4; d * 0.7 to 8 * sin(d / 15.0 * Math.PI) } }
        // 4. City: buildings, ±10 m with 5% multipath jumps
        scenario("City streets, buildings") { simulate(1000, 10.0, it, multipath = 0.05) { t -> t * 3.0 to 0.0 } }
        // 5. Slow walk 1.0 m/s with ±8 m GPS
        scenario("Slow walk, rough GPS") { simulate(1200, 8.0, it) { t -> t * 1.0 to 0.0 } }
        // 6. Standing still 10 minutes, ±6 m (error in metres, should be 0)
        scenario("Standing still 10 min") { simulate(600, 6.0, it) { 0.0 to 0.0 } }
        // 7. Run with 2 min stop at traffic light in the middle
        scenario("Run with 2-min stop") { simulate(900, 5.0, it) { t -> (if (t < 400) t * 3.0 else if (t < 520) 1200.0 else 1200 + (t - 520) * 3.0) to 0.0 } }
    }
}
