package kr.tedin.maptalkdrive.server.service

import kr.tedin.maptalkdrive.ribbon.route.AnchorBuilder
import kr.tedin.maptalkdrive.ribbon.route.FacilityCatalog
import kr.tedin.maptalkdrive.ribbon.route.PathFacts
import kr.tedin.maptalkdrive.ribbon.route.RouteMatching
import kr.tedin.maptalkdrive.ribbon.route.StableIds
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.LineFeature
import kr.tedin.maptalkdrive.ribbon.spec.LineGeometry
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import kr.tedin.maptalkdrive.server.routing.GhRouter
import java.util.Collections
import kotlin.math.roundToLong

class BadRequestException(message: String) : RuntimeException(message)

/**
 * Turns a navigation route request into a line with anchors: draw the candidate lines, take the one whose length
 * matches the navigation's (the router's first choice when it matches), hang anchors on it. Results are cached by
 * route id, which is a hash of the request, so a car that asks again (or the simulator asking the same thing) gets the
 * same route and the same id back, also after a server restart.
 */
class RouteService(
    private val draw: (RouteRequest) -> List<GhRouter.Candidate>,
    private val catalog: FacilityCatalog,
    val dataVersion: String,
    cacheSize: Int = 64,
    /** Called once per route drawn (not for cached answers): the elevation profile starts building here. */
    private val onDrawn: (routeId: String, facts: PathFacts) -> Unit = { _, _ -> },
) {
    private class Entry(val response: RouteResponse, val facts: PathFacts)

    private val cache: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > cacheSize
        },
    )

    val cached: Int get() = cache.size

    fun get(routeId: String): RouteResponse? = cache[routeId]?.response

    /** What the router said about the line of a cached route, for its elevation profile. */
    fun facts(routeId: String): PathFacts? = cache[routeId]?.facts

    fun route(req: RouteRequest): RouteResponse {
        validate(req)
        val id = routeId(req)
        cache[id]?.let { return it.response }
        val candidates = draw(req)
        val pick = RouteMatching.pick(candidates.map { it.distanceM }, req.naviDistanceM)
        val facts = candidates[pick].facts
        val dest = req.destinationName?.replace("→", " ")?.trim()?.takeIf { it.isNotEmpty() }
        val anchors = AnchorBuilder.build(
            routeId = id,
            routeName = "내비 경로: 출발지 → ${dest ?: "목적지"}",
            routeNameEn = "Navigation route: Start → ${dest ?: "Destination"}",
            facts = facts,
            catalog = catalog,
        )
        val response = RouteResponse(
            routeId = id,
            dataVersion = dataVersion,
            route = line(id, facts, anchors.lengthM),
            anchorSet = anchors,
            match = RouteMatching.match(anchors.lengthM, req.naviDistanceM, pick, candidates.size),
        )
        cache[id] = Entry(response, facts)
        onDrawn(id, facts)
        return response
    }

    /** Same request, same id: places to 1e-5 degrees, heading to 5°, the navigation's length to 100 m. */
    fun routeId(req: RouteRequest): String {
        fun p(x: LatLon) = "${(x.lat * 1e5).roundToLong()},${(x.lon * 1e5).roundToLong()}"
        val key = listOf(
            ALGO_VERSION, dataVersion, p(req.origin), req.originHeadingDeg?.let { (it / 5).roundToLong() } ?: "-",
            req.waypoints.joinToString(";") { p(it) }, p(req.destination), req.option.name,
            req.naviDistanceM?.let { (it / 100.0).roundToLong() } ?: "-",
        ).joinToString("|")
        return "L" + StableIds.hex(key, 10)
    }

    private fun validate(req: RouteRequest) {
        val points = listOf(req.origin) + req.waypoints + req.destination
        if (points.any { it.lat !in 33.0..39.0 || it.lon !in 124.0..132.0 }) throw BadRequestException("points must be in Korea")
        if (req.waypoints.size > 5) throw BadRequestException("at most 5 waypoints")
    }

    private fun line(id: String, facts: PathFacts, lengthM: Int) = LineFeature(
        properties = mapOf("routeId" to id, "lengthM" to lengthM.toString()),
        geometry = LineGeometry(coordinates = facts.path.map { listOf(it.lon, it.lat) }),
    )

    companion object {
        /** Part of every route id: bump when anchors or ids would come out differently for the same request. */
        const val ALGO_VERSION = "a2"
    }
}
