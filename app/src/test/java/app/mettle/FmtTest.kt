package app.mettle

import app.mettle.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test fun durationUnderAnHour() = assertEquals("33:20", Fmt.duration(2000))
    @Test fun durationOverAnHour() = assertEquals("2:01:40", Fmt.duration(7300))
    @Test fun paceFormatsMinutesAndSeconds() = assertEquals("5:09", Fmt.pace(309.4))
    @Test fun paceWithoutDataShowsDashes() = assertEquals("–:––", Fmt.pace(null))
    @Test fun kilometres() = assertEquals("6.47", Fmt.km2(6470))
}
