package app.mettle

import app.mettle.health.HealthMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class HealthMathTest {
    private fun ppg(bpm: Double, seconds: Int = 30, fps: Int = 30, noise: Double = 0.3, drift: Double = 2.0): List<Pair<Long, Double>> {
        val r = Random(7)
        return (0 until seconds * fps).map { i ->
            val t = i * 1000L / fps
            val beat = sin(2 * PI * bpm / 60.0 * t / 1000.0) + 0.4 * sin(4 * PI * bpm / 60.0 * t / 1000.0)
            t to 180 + beat + drift * t / 30_000.0 + r.nextDouble(-noise, noise)
        }
    }

    @Test fun detectsRestingPulse() { val p = HealthMath.pulse(ppg(62.0))!!; assertEquals(62.0, p.bpm.toDouble(), 2.0); assertTrue(p.quality > 0.5) }
    @Test fun detectsFastPulse() = assertEquals(118.0, HealthMath.pulse(ppg(118.0))!!.bpm.toDouble(), 3.0)
    @Test fun tooShortGivesNothing() = assertNull(HealthMath.pulse(ppg(70.0, seconds = 2)))

    @Test fun readinessDropsWithHighHeartRateAndLoadSpike() {
        val fresh = HealthMath.readiness(55, 56.0, 470, 40.0, 45.0)!!
        val tired = HealthMath.readiness(63, 56.0, 330, 90.0, 45.0)!!
        assertTrue("fresh $fresh tired $tired", fresh > 75 && tired < 35)
    }

    @Test fun readinessWorksWithoutSleepData() = assertTrue(HealthMath.readiness(57, 56.0, null, 40.0, 40.0)!! > 50)
}
