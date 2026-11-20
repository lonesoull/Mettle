package app.mettle.tracking

import app.mettle.data.db.DayMinuteEntity
import app.mettle.data.db.DayState
import kotlin.math.roundToInt

/** Body details used for estimates; defaults are used until the profile is loaded. */
data class Body(val heightCm: Int = 170, val weightKg: Double = 70.0)

data class DaySummary(
    val steps: Int,
    val distanceM: Int,
    val activeMin: Int,
    val walkingMin: Int,
    val runningMin: Int,
    val stillMin: Int,
    val vehicleMin: Int,
    val cyclingMin: Int,
    val activeCalories: Int,
    val trackedMin: Int,
    val sittingMin: Int = 0,
    val standingMin: Int = 0,
    val floorsUp: Int = 0,
    val floorsDown: Int = 0,
    val liftTrips: Int = 0,
)

data class Segment(val startMinute: Int, val endMinute: Int, val state: DayState, val steps: Int)

/** Pure calculations for all-day tracking (no Android dependencies; unit-tested). */
object DayMath {
    /** Steps per minute above which a minute counts as running. */
    const val RUN_CADENCE = 140
    /** Steps per minute from which walking counts as "active" (brisk). */
    const val ACTIVE_CADENCE = 60

    fun strideM(body: Body, state: DayState): Double = when (state) {
        DayState.RUNNING -> body.heightCm * 0.0065    // ≈1.1 m at 170 cm
        else -> body.heightCm * 0.00415               // ≈0.71 m at 170 cm
    }

    /**
     * Final state of a minute: the motion sensor's label, corrected by step rhythm.
     * Many steps always means on foot, whatever the activity sensor guessed.
     */
    fun resolve(sensorState: DayState, steps: Int): DayState = when {
        steps >= RUN_CADENCE -> DayState.RUNNING
        steps >= 30 -> if (sensorState == DayState.RUNNING) DayState.RUNNING else DayState.WALKING
        steps >= 10 && sensorState in setOf(DayState.STILL, DayState.SITTING, DayState.STANDING, DayState.UNKNOWN) -> DayState.WALKING
        else -> sensorState
    }

    fun summary(minutes: List<DayMinuteEntity>, body: Body): DaySummary {
        var distance = 0.0
        var calories = 0.0
        val count = mutableMapOf<DayState, Int>()
        var active = 0
        for (m in minutes) {
            count[m.state] = (count[m.state] ?: 0) + 1
            val meters = m.steps * strideM(body, m.state)
            distance += meters
            // Net walking ≈ 0.5 kcal/kg/km, running ≈ 1.0 kcal/kg/km.
            calories += body.weightKg * (meters / 1000.0) * if (m.state == DayState.RUNNING) 1.0 else 0.5
            if (m.state == DayState.RUNNING || m.steps >= ACTIVE_CADENCE || m.state == DayState.CYCLING) active++
        }
        return DaySummary(
            steps = minutes.sumOf { it.steps },
            distanceM = distance.roundToInt(),
            activeMin = active,
            walkingMin = count[DayState.WALKING] ?: 0,
            runningMin = count[DayState.RUNNING] ?: 0,
            stillMin = (count[DayState.STILL] ?: 0) + (count[DayState.SITTING] ?: 0) + (count[DayState.STANDING] ?: 0),
            sittingMin = count[DayState.SITTING] ?: 0,
            standingMin = count[DayState.STANDING] ?: 0,
            floorsUp = minutes.sumOf { it.floorsUp },
            floorsDown = minutes.sumOf { it.floorsDown },
            liftTrips = minutes.sumOf { it.liftTrips },
            vehicleMin = count[DayState.VEHICLE] ?: 0,
            cyclingMin = count[DayState.CYCLING] ?: 0,
            activeCalories = calories.roundToInt(),
            trackedMin = minutes.size,
        )
    }

    /**
     * Day timeline: consecutive minutes with the same state, with blips shorter
     * than [minLength] minutes absorbed into the previous segment. Gaps in the
     * minutes (tracking off) are kept as gaps.
     */
    fun segments(minutes: List<DayMinuteEntity>, minLength: Int = 3): List<Segment> {
        val raw = mutableListOf<Segment>()
        for (m in minutes.sortedBy { it.minute }) {
            val last = raw.lastOrNull()
            if (last != null && last.state == m.state && last.endMinute == m.minute) {
                raw[raw.lastIndex] = last.copy(endMinute = m.minute + 1, steps = last.steps + m.steps)
            } else {
                raw += Segment(m.minute, m.minute + 1, m.state, m.steps)
            }
        }
        val out = mutableListOf<Segment>()
        for (s in raw) {
            val prev = out.lastOrNull()
            val short = s.endMinute - s.startMinute < minLength
            if (prev != null && prev.endMinute == s.startMinute && (short || prev.state == s.state)) {
                out[out.lastIndex] = prev.copy(endMinute = s.endMinute, steps = prev.steps + s.steps)
            } else {
                out += s
            }
        }
        return out
    }
}
