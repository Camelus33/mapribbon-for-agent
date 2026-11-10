package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.distanceM
import kotlin.math.atan2
import kotlin.math.cos

/**
 * A value that holds over the route's vertices `from`..`to` (the edges from..to-1), the way a router reports path
 * details. Vertex indices refer to [PathFacts.path].
 */
data class Span<T>(val from: Int, val to: Int, val value: T)

/**
 * A route line as the router drew it, with what the router knows about each stretch: which OSM way it runs on, whether
 * it is in a tunnel, the road class and number. Coordinates are rounded to 1e-6 degrees once, by whoever builds this,
 * so the same road gives the same vertices on every route.
 */
class PathFacts(
    val path: List<LngLat>,
    val wayIds: List<Span<Long>> = emptyList(),
    val roadEnvironment: List<Span<String>> = emptyList(),
    val roadClass: List<Span<String>> = emptyList(),
    val roadClassLink: List<Span<Boolean>> = emptyList(),
    val streetRef: List<Span<String?>> = emptyList(),
    val streetName: List<Span<String?>> = emptyList(),
) {
    init { require(path.size >= 2) { "route needs at least 2 points" } }

    val edges: Int get() = path.size - 1

    /** The value on each edge (index = edge), or null where no span covers it. */
    fun <T> perEdge(spans: List<Span<T>>): List<T?> {
        val out = MutableList<T?>(edges) { null }
        for (s in spans) for (e in s.from.coerceAtLeast(0) until s.to.coerceAtMost(edges)) out[e] = s.value
        return out
    }

    /** Length of one edge in metres; depends only on its two vertices, never on where the route started. */
    fun edgeLengthM(e: Int): Double = distanceM(path[e], path[e + 1])
}

/** A point on the route: `t` of the way along edge `edge`. */
data class PathPos(val edge: Int, val t: Double)

/** Bearing from a to b in degrees clockwise from north, on a local plane (fine for a few km). */
fun bearingDeg(a: LngLat, b: LngLat): Double {
    val east = (b.lon - a.lon) * 111_320 * cos(Math.toRadians((a.lat + b.lat) / 2))
    val north = (b.lat - a.lat) * 110_540
    return (Math.toDegrees(atan2(east, north)) + 360) % 360
}

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** The 8-point compass name of a bearing. */
fun compass(deg: Double): String = COMPASS[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]

/** Smallest difference between two bearings, 0..180. */
fun angleDiff(a: Double, b: Double): Double {
    val d = ((a - b) % 360 + 360) % 360
    return if (d > 180) 360 - d else d
}
