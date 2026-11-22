package app.mettle

import app.mettle.space.StayMath
import app.mettle.space.TrackPt
import app.mettle.workout.WorkoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos

/** Sitting at home all morning while Wi-Fi/cell positions jump around (the 22 Nov screenshot). */
class StillDayTest {
    private val homeLat = 12.9850; private val homeLon = 80.2400
    private val mLat = 111_320.0; private val mLon = 111_320.0 * cos(Math.toRadians(homeLat))

    private fun noisyMorning(): List<TrackPt> {
        val r = Random(7)
        return (0 until 160).map { i ->     // a fix every ~4 min for ~10.5 h
            val t = i * 4 * 60_000L
            val wild = r.nextDouble() < 0.12       // some fixes land hundreds of metres away
            val err = if (wild) 300 + r.nextDouble() * 600 else r.nextDouble() * 25
            val ang = r.nextDouble() * 2 * Math.PI
            val acc = if (wild) (if (r.nextBoolean()) 100f else 60f) else 20f
            TrackPt(t, homeLat + err * kotlin.math.sin(ang) / mLat, homeLon + err * cos(ang) / mLon, acc, null)
        }
    }

    @Test fun drawnPathStaysAtHome() {
        val pts = noisyMorning()
        val kept = StayMath.clean(pts).map { pts[it] }
        val settled = StayMath.settle(kept)
        val far = settled.maxOf { WorkoutMath.haversineM(homeLat, homeLon, it.lat, it.lon) }
        assertTrue("furthest drawn point is ${far.toInt()} m from home", far < 100)
    }

    @Test fun oneStayNotMany() {
        val stays = StayMath.stays(noisyMorning())
        assertEquals("stays: ${stays.map { (it.endMs - it.startMs) / 60_000 }}", 1, stays.size)
        assertTrue(WorkoutMath.haversineM(homeLat, homeLon, stays[0].lat, stays[0].lon) < 40)
    }

    @Test fun realTripIsKept() {
        // Walk 1.2 km away over 15 minutes with good fixes: must not be removed as a spike.
        val trip = (0..15).map { m -> TrackPt(m * 60_000L, homeLat + (m * 80.0) / mLat, homeLon, 10f, null) }
        val kept = StayMath.clean(trip)
        assertEquals(trip.size, kept.size)
    }
}

class StepGateTest {
    private val lat0 = 12.9850; private val lon0 = 80.2400; private val mLat = 111_320.0
    private fun pt(min: Int, northM: Double, acc: Float = 5f) = TrackPt(min * 60_000L, lat0 + northM / mLat, lon0, acc, null)

    @Test fun sharpWrongFixesWithoutStepsStayHome() {
        // At home; at 9:00-9:03 four sharp (±4 m) fixes say 50 m north (the 22 Nov main-street line). 0 steps.
        val pts = (0..60).map { pt(it, 0.0) } + (61..64).map { pt(it, 50.0, 4f) } + (65..120).map { pt(it, 0.0) }
        val out = StayMath.anchorToSteps(pts) { _, _ -> 0.0 }
        assertTrue(out.all { WorkoutMath.haversineM(lat0, lon0, it.lat, it.lon) < 1 })
    }

    @Test fun realWalkWithStepsIsKept() {
        // Walk 300 m north over 5 minutes with ~100 steps a minute.
        val pts = (0..5).map { pt(it, it * 60.0) }
        val out = StayMath.anchorToSteps(pts) { t0, t1 -> ((t1 - t0) / 60_000.0) * 100 * 0.72 * 1.3 }
        assertEquals(300.0, WorkoutMath.haversineM(lat0, lon0, out.last().lat, out.last().lon), 1.0)
    }

    @Test fun wrongStartingPointCorrectsItself() {
        // First fix is 80 m off, then 20 minutes of consistent fixes at the true spot, no steps.
        val pts = listOf(pt(0, 80.0)) + (1..20).map { pt(it, 0.0) }
        val out = StayMath.anchorToSteps(pts) { _, _ -> 0.0 }
        assertTrue(WorkoutMath.haversineM(lat0, lon0, out.last().lat, out.last().lon) < 1)
    }
}

class StepGateLongErrorTest {
    private val lat0 = 12.9850; private val lon0 = 80.2400; private val mLat = 111_320.0
    private fun pt(min: Int, northM: Double) = TrackPt(min * 60_000L, lat0 + northM / mLat, lon0, 5f, null)

    @Test fun twentyMinutesOfWrongFixesAfterHoursAtHomeDoNotMoveYou() {
        val pts = (0..180).map { pt(it, 0.0) } + (181..200).map { pt(it, 50.0) } + (201..240).map { pt(it, 0.0) }
        val out = StayMath.anchorToSteps(pts) { _, _ -> 0.0 }
        org.junit.Assert.assertTrue(out.all { WorkoutMath.haversineM(lat0, lon0, it.lat, it.lon) < 1 })
    }
}
