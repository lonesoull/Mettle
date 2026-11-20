package app.mettle

import app.mettle.space.StayMath
import app.mettle.space.TrackPt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class StayMathTest {
    private val home = 12.9800 to 77.6200
    private val office = 12.9900 to 77.6400
    private fun at(h: Int, m: Int, day: Int = 18) = LocalDateTime.of(2026, 11, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun sit(p: Pair<Double, Double>, from: Long, to: Long, every: Long = 5 * 60_000L) =
        (from..to step every).mapIndexed { i, t -> TrackPt(t, p.first + (i % 3) * 0.00005, p.second, 20f, 900.0) }

    @Test fun findsHomeCommuteOffice() {
        val commute = (0 until 10).map { i -> TrackPt(at(8, 30) + i * 60_000L, home.first + (office.first - home.first) * i / 10, home.second + (office.second - home.second) * i / 10, 10f, null) }
        val pts = sit(home, at(0, 0), at(8, 25)) + commute + sit(office, at(8, 45), at(17, 0))
        val stays = StayMath.stays(pts)
        assertEquals(2, stays.size)
        assertEquals(at(0, 0), stays[0].startMs)
        assertEquals(at(8, 45), stays[1].startMs)
    }

    @Test fun shortStopsAreNotStays() {
        val pts = sit(home, at(10, 0), at(10, 3), every = 60_000L)
        assertEquals(0, StayMath.stays(pts).size)
    }

    @Test fun learnsHomeAndWork() {
        val stays = (16..20).flatMap { d ->
            StayMath.stays(sit(home, at(0, 0, d), at(8, 20, d)) + sit(office, at(9, 0, d), at(17, 30, d)))
        }
        val places = StayMath.learnPlaces(stays, ZoneId.of("UTC"))
        assertEquals(listOf("Home", "Work"), places.map { it.label })
        assertEquals("Home", StayMath.label(stays.first(), places))
    }
}
