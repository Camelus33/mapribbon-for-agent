package kr.tedin.maptalkdrive.ribbon.live

import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteSource
import kotlin.math.roundToLong

/**
 * Where the navigation is going, as far as the car can tell: points, the navigation's own length and route option, but
 * no line. `origin` null means "where the car is now", filled in from the car's own position when the request is made.
 */
data class NaviRoute(
    val origin: LatLon?,
    /** True when the origin is the car's position (from the navigation or the car), false for a typed test origin. */
    val originIsCurrent: Boolean,
    val originHeadingDeg: Double?,
    val waypoints: List<LatLon>,
    val destination: LatLon,
    val destinationName: String?,
    val option: RouteOption,
    val naviDistanceM: Int?,
    val source: RouteSource,
) {
    /** The request for the ribbon server, or null when there is no origin and no fallback position. */
    fun request(fallbackOrigin: LatLon?): RouteRequest? {
        val o = origin ?: fallbackOrigin ?: return null
        return RouteRequest(
            origin = o, originHeadingDeg = originHeadingDeg, waypoints = waypoints, destination = destination,
            option = option, naviDistanceM = naviDistanceM, destinationName = destinationName, source = source,
        )
    }

    /** Same places and option, to 1e-5 degrees: the same route asked for again. */
    fun samePlaces(other: NaviRoute): Boolean =
        key(origin) == key(other.origin) && waypoints.map(::key) == other.waypoints.map(::key) &&
            key(destination) == key(other.destination) && option == other.option && source == other.source

    private fun key(p: LatLon?): String = p?.let { "${(it.lat * 1e5).roundToLong()},${(it.lon * 1e5).roundToLong()}" } ?: "-"
}

/**
 * Collects the navigation's answers to one route event. The SDK answers each question through its own callback and in
 * no fixed order, so the answers are gathered here and the route is complete when all have come in (or, after a
 * timeout, as soon as the destination is known). The navigation's length is taken from the route start event if there
 * was one, else from a trip estimate, else from the remaining distance to the destination.
 */
class NaviRouteDraft {
    var destination: LatLon? = null
    var destinationName: String? = null
    var waypoints: List<LatLon>? = null
    var current: LatLon? = null
    var headingDeg: Double? = null
    var option: RouteOption? = null
    var startDistanceM: Int? = null
    var tripDistanceM: Int? = null
    var remainDistanceM: Int? = null

    val distanceM: Int? get() = (startDistanceM ?: tripDistanceM ?: remainDistanceM)?.takeIf { it > 0 }

    fun complete(): Boolean =
        destination != null && current != null && waypoints != null && option != null && distanceM != null

    /** The route so far, or null without a destination. */
    fun result(): NaviRoute? {
        val dest = destination ?: return null
        return NaviRoute(
            origin = current, originIsCurrent = true, originHeadingDeg = headingDeg, waypoints = waypoints.orEmpty(),
            destination = dest, destinationName = destinationName, option = option ?: RouteOption.RECOMMENDED,
            naviDistanceM = distanceM, source = RouteSource.NAVI,
        )
    }
}

/** Reads the test-input extras: "lat,lon" points, "lat,lon;lat,lon" lists, route options by name. */
object TestInput {
    fun point(text: String?): LatLon? {
        val parts = text?.split(',')?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

    fun points(text: String?): List<LatLon> =
        text?.split(';')?.filter { it.isNotBlank() }?.mapNotNull { point(it) }.orEmpty()

    fun option(text: String?): RouteOption =
        text?.let { t -> RouteOption.entries.firstOrNull { it.name.equals(t.trim(), ignoreCase = true) } } ?: RouteOption.RECOMMENDED

    /** A test route; without an origin it starts where the car is. */
    fun route(
        origin: String?, headingDeg: Double?, via: String?, destination: String?, destinationName: String?,
        option: String?, naviDistanceM: Int?,
    ): NaviRoute? {
        val dest = point(destination) ?: return null
        val o = point(origin)
        return NaviRoute(
            origin = o, originIsCurrent = o == null, originHeadingDeg = headingDeg, waypoints = points(via),
            destination = dest, destinationName = destinationName?.takeIf { it.isNotBlank() }, option = option(option),
            naviDistanceM = naviDistanceM?.takeIf { it > 0 }, source = RouteSource.TEST,
        )
    }
}
