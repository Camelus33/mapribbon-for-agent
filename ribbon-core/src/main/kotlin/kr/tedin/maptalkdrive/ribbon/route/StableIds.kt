package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import java.security.MessageDigest
import kotlin.math.roundToLong

/**
 * Anchor ids that belong to the road, not to the route. Two routes that drive the same stretch of road in the same
 * direction must give its anchors the same ids, so that knots tied on either route meet in one ribbon; the opposite
 * direction must give other ids. Every token here is built from road geometry only (OSM vertices, fixed grid lines,
 * tunnel portals), never from distances measured from a route's start.
 */
object StableIds {
    /** Characters an id may use: they pass through URIs (`ribbons/<id>`) and file names unescaped. */
    val SAFE = Regex("^[A-Za-z0-9:_.~-]+$")

    fun micro(deg: Double): Long = (deg * 1e6).roundToLong()

    /** A point to 1e-6 degrees, lat first. */
    fun point(p: LngLat): String = "${micro(p.lat)},${micro(p.lon)}"

    /** A directed edge: the same road drawn the other way gives another token. */
    fun edge(a: LngLat, b: LngLat): String = "${point(a)}>${point(b)}"

    /** The first `n` hex characters of SHA-1. */
    fun hex(text: String, n: Int): String {
        val d = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(n)
    }

    fun isSafe(id: String): Boolean = SAFE.matches(id)
}
