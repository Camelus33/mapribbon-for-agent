package kr.tedin.maptalkdrive.server.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.Parameters
import com.graphhopper.util.details.PathDetail
import com.graphhopper.util.shapes.GHPoint
import kr.tedin.maptalkdrive.ribbon.route.PathFacts
import kr.tedin.maptalkdrive.ribbon.route.Span
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kotlin.math.roundToLong

class NoRouteException(message: String) : RuntimeException(message)

/**
 * Draws a route through the request's points and reports, per stretch, what the router knows about it. The line keeps
 * every OSM vertex (no simplification) and is rounded to 1e-6 degrees, so the same road gives the same vertices on
 * every route: anchor ids depend on that.
 */
class GhRouter(private val hopper: GraphHopper) {
    data class Candidate(val facts: PathFacts, val distanceM: Double, val timeMs: Long)

    /** One or more candidate lines, the router's first choice first. */
    fun route(req: RouteRequest): List<Candidate> {
        val points = (listOf(req.origin) + req.waypoints + req.destination).map { GHPoint(it.lat, it.lon) }
        val gh = GHRequest(points)
            .setProfile(GhConfig.PROFILE)
            .setPathDetails(GhConfig.PATH_DETAILS)
            // A car on a bridge or in a tunnel must stay on it; the default would push its start onto a side road.
            .setSnapPreventions(emptyList())
            .putHint(Parameters.Routing.INSTRUCTIONS, false)
            .putHint(Parameters.Routing.CALC_POINTS, true)
            .putHint(Parameters.Routing.WAY_POINT_MAX_DISTANCE, 0)
        req.originHeadingDeg?.let { h -> gh.setHeadings(listOf(h) + List(points.size - 1) { Double.NaN }) }
        OptionModels.of(req.option)?.let { gh.setCustomModel(it) }
        if (req.waypoints.isEmpty() && req.naviDistanceM != null) {
            gh.setAlgorithm(Parameters.Algorithms.ALT_ROUTE)
                .putHint(Parameters.Algorithms.AltRoute.MAX_PATHS, 3)
                .putHint(Parameters.Algorithms.AltRoute.MAX_WEIGHT, 1.5)
                .putHint(Parameters.Algorithms.AltRoute.MAX_SHARE, 0.75)
        }
        val resp = hopper.route(gh)
        if (resp.hasErrors()) throw NoRouteException(resp.errors.joinToString("; ") { it.message ?: it.javaClass.simpleName })
        val paths = resp.all.filter { !it.hasErrors() && it.points.size() >= 2 }
        if (paths.isEmpty()) throw NoRouteException("no path")
        return paths.map { Candidate(facts(it), it.distance, it.time) }
    }

    private fun facts(p: ResponsePath): PathFacts {
        val pl = p.points
        val path = (0 until pl.size()).map { LngLat(r6(pl.getLon(it)), r6(pl.getLat(it))) }
        val d = p.pathDetails
        fun <T> spans(key: String, f: (Any?) -> T): List<Span<T>> =
            d[key].orEmpty().map { pd: PathDetail -> Span(pd.first, pd.last, f(pd.value)) }
        return PathFacts(
            path = path,
            wayIds = spans("osm_way_id") { (it as Number).toLong() },
            roadEnvironment = spans("road_environment") { it.toString().lowercase() },
            roadClass = spans("road_class") { it.toString().lowercase() },
            roadClassLink = spans("road_class_link") { it == true },
            streetRef = spans("street_ref") { (it as? String)?.takeIf { s -> s.isNotBlank() } },
            streetName = spans("street_name") { (it as? String)?.takeIf { s -> s.isNotBlank() } },
        )
    }

    private fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6
}
