package app.mettle.health

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PulseResult(val bpm: Int, val quality: Double)

/** Pure health maths (unit-tested). */
object HealthMath {
    /**
     * Heart rate from fingertip camera brightness (photoplethysmography).
     * [samples] are (time ms, frame brightness). The signal is detrended, then the
     * strongest repeating period between 40 and 200 bpm is found by autocorrelation.
     */
    fun pulse(samples: List<Pair<Long, Double>>): PulseResult? {
        if (samples.size < 90) return null
        // Resample to an even 30 Hz grid.
        val t0 = samples.first().first; val t1 = samples.last().first
        val dt = 1000.0 / 30
        val n = ((t1 - t0) / dt).toInt()
        if (n < 90) return null
        val grid = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + i * dt
            while (j < samples.size - 2 && samples[j + 1].first < t) j++
            val (ta, va) = samples[j]; val (tb, vb) = samples[j + 1]
            grid[i] = if (tb == ta) va else va + (vb - va) * (t - ta) / (tb - ta)
        }
        // Remove slow drift with a 1-second moving average.
        val w = 30
        val x = DoubleArray(n) { i ->
            val a = (i - w / 2).coerceAtLeast(0); val b = (i + w / 2).coerceAtMost(n - 1)
            var s = 0.0; for (k in a..b) s += grid[k]
            grid[i] - s / (b - a + 1)
        }
        val mean = x.average()
        val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / n)
        if (sd < 1e-6) return null
        val z = DoubleArray(n) { (x[it] - mean) / sd }
        val minLag = (30 * 60 / 200.0).toInt()   // 200 bpm
        val maxLag = (30 * 60 / 40.0).toInt()    // 40 bpm
        var bestLag = -1; var best = 0.0
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var s = 0.0; for (i in 0 until n - lag) s += z[i] * z[i + lag]
            ac[lag] = s / (n - lag)
        }
        for (lag in minLag + 1 until maxLag) {
            if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > best) { best = ac[lag]; bestLag = lag }
        }
        if (bestLag < 0) return null
        // Refine the peak position with a parabola through its neighbours.
        val y0 = ac[bestLag - 1]; val y1 = ac[bestLag]; val y2 = ac[bestLag + 1]
        val shift = (y0 - y2) / (2 * (y0 - 2 * y1 + y2)).let { if (it == 0.0) 1.0 else it }
        val lag = bestLag + shift.coerceIn(-0.5, 0.5)
        return PulseResult((60.0 * 30 / lag).roundToInt(), best.coerceIn(0.0, 1.0))
    }

    /**
     * Morning readiness 0–100: resting heart rate against your own baseline,
     * last night's sleep (if a device provides it) and training load balance.
     */
    fun readiness(restingHr: Int?, baselineHr: Double?, sleepMin: Int?, acuteLoad: Double, chronicLoad: Double): Int? {
        val parts = ArrayList<Pair<Double, Double>>()   // score 0..1, weight
        if (restingHr != null && baselineHr != null && baselineHr > 0) {
            val diff = restingHr - baselineHr                  // +5 bpm above baseline is a strong fatigue sign
            parts += (1 / (1 + exp((diff - 1.5) / 2.0))) to 0.45
        }
        if (sleepMin != null) parts += ((sleepMin - 300) / 180.0).coerceIn(0.0, 1.0) to 0.30      // 5 h → 0, 8 h → 1
        if (chronicLoad > 0) {
            val ratio = acuteLoad / chronicLoad                // >1.3 means a recent load spike
            parts += (1.0 - ((ratio - 1.0).coerceAtLeast(0.0) / 0.6)).coerceIn(0.0, 1.0) to 0.25
        }
        if (parts.isEmpty()) return null
        val w = parts.sumOf { it.second }
        return (parts.sumOf { it.first * it.second } / w * 100).roundToInt().coerceIn(0, 100)
    }

    /** Exponentially weighted daily load: acute (7-day) and chronic (42-day). */
    fun loads(dailyLoad: List<Double>): Pair<Double, Double> {
        var a = 0.0; var c = 0.0
        for (l in dailyLoad) { a += (l - a) / 7.0; c += (l - c) / 42.0 }
        return a to c
    }
}
