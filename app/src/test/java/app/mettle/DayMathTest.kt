package app.mettle

import app.mettle.data.db.DayMinuteEntity
import app.mettle.data.db.DayState
import app.mettle.tracking.Body
import app.mettle.tracking.DayMath
import org.junit.Assert.assertEquals
import org.junit.Test

class DayMathTest {
    private fun m(minute: Int, steps: Int, state: DayState) = DayMinuteEntity("2026-11-21", minute, steps, state, 0)

    @Test fun manyStepsMeansOnFootEvenIfSensorSaidStill() {
        assertEquals(DayState.WALKING, DayMath.resolve(DayState.STILL, 95))
        assertEquals(DayState.RUNNING, DayMath.resolve(DayState.WALKING, 165))
        assertEquals(DayState.VEHICLE, DayMath.resolve(DayState.VEHICLE, 2))
    }

    @Test fun distanceUsesHeightBasedStride() {
        val s = DayMath.summary(listOf(m(0, 1000, DayState.WALKING)), Body(heightCm = 180, weightKg = 80.0))
        assertEquals(747, s.distanceM)          // 1000 × 0.747 m
        assertEquals(30, s.activeCalories)      // 80 kg × 0.747 km × 0.5
        assertEquals(1, s.activeMin)
    }

    @Test fun timelineMergesShortBlips() {
        val minutes = (0 until 10).map { m(it, 0, DayState.STILL) } +
            listOf(m(10, 50, DayState.WALKING)) +                        // 1-minute blip
            (11 until 20).map { m(it, 0, DayState.STILL) } +
            (20 until 30).map { m(it, 100, DayState.WALKING) }
        val seg = DayMath.segments(minutes)
        assertEquals(2, seg.size)
        assertEquals(DayState.STILL, seg[0].state); assertEquals(0, seg[0].startMinute); assertEquals(20, seg[0].endMinute)
        assertEquals(DayState.WALKING, seg[1].state); assertEquals(1000, seg[1].steps)
    }

    @Test fun gapsStayGaps() {
        val seg = DayMath.segments((0 until 5).map { m(it, 0, DayState.STILL) } + (60 until 65).map { m(it, 0, DayState.STILL) })
        assertEquals(2, seg.size)
    }
}
