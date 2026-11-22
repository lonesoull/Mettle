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
