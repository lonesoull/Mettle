package app.mettle.ui

import java.util.Locale

/** Formatting helpers shared by every screen. */
object Fmt {
    fun km(meters: Long): String = String.format(Locale.US, "%.1f", meters / 1000.0)
    fun km2(meters: Long): String = String.format(Locale.US, "%.2f", meters / 1000.0)

    /** 1:05:09 or 33:20 */
    fun duration(totalS: Long): String {
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Hours and minutes for weekly totals: 2:58 */
    fun hoursMinutes(totalS: Long): String = String.format(Locale.US, "%d:%02d", totalS / 3600, (totalS % 3600) / 60)

    /** Pace in min:sec per km from seconds per km. */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm <= 0 || secPerKm.isInfinite()) return "–:––"
        val r = secPerKm.toLong()
        return String.format(Locale.US, "%d:%02d", r / 60, r % 60)
    }
}
