package kr.tedin.maptalkdrive.ribbon.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sun stands, from time and place alone (no network): the low-precision solar position of the Astronomical
 * Almanac, good to about a tenth of a degree for this century. Used to tell whether low beams were on in daylight.
 */
object Sun {
    /** The sun counts as up from this elevation: past civil twilight, headlights are a choice, not a need. */
    const val DAY_FROM_DEG = 6.0

    fun elevationDeg(epochMs: Long, lat: Double, lon: Double): Double {
        val d = epochMs / 86_400_000.0 - 10_957.5 // days since J2000.0 (2000-01-01 12:00 UTC)
        val g = Math.toRadians(norm(357.529 + 0.98560028 * d))
        val q = norm(280.459 + 0.98564736 * d)
        val l = Math.toRadians(norm(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstDeg = norm(280.46061837 + 360.98564736629 * d)
        val ha = Math.toRadians(gmstDeg + lon) - ra
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
    }

    fun isDay(epochMs: Long, lat: Double, lon: Double): Boolean = elevationDeg(epochMs, lat, lon) > DAY_FROM_DEG

    private fun norm(deg: Double) = ((deg % 360) + 360) % 360
}
