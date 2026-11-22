package app.mettle

import app.mettle.tracking.Pdr
import app.mettle.workout.WorkoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdrTest {
    @Test fun tenStepsNorthMovesAboutSevenMetres() {
        val p = Pdr(Pdr.strideFor(170))            // 0.71 m stride
        p.fix(13.0, 80.2, 5.0)
        repeat(10) { p.step(0.0) }
        assertEquals(7.06, WorkoutMath.haversineM(13.0, 80.2, p.lat, p.lon), 0.1)
        assertTrue(p.lat > 13.0)
    }

    @Test fun squareWalkReturnsHome() {
        val p = Pdr(0.7); p.fix(13.0, 80.2, 5.0)
        for (h in listOf(0.0, 90.0, 180.0, 270.0)) repeat(8) { p.step(h) }
        assertTrue(WorkoutMath.haversineM(13.0, 80.2, p.lat, p.lon) < 0.05)
    }

    @Test fun worseFixDoesNotOverrideFreshSteps() {
        val p = Pdr(0.7); p.fix(13.0, 80.2, 4.0)
        repeat(3) { p.step(90.0) }                  // ~2 m east, uncertainty ~4.1 m
        assertFalse(p.fix(13.001, 80.201, 100.0))   // a ±100 m Wi-Fi guess is ignored
        assertTrue(p.fix(13.0, 80.20002, 3.0))      // a sharper GPS fix re-anchors
    }
}
