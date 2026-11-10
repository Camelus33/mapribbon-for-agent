package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A winding expressway heading east from 37.90 N 128.30 E: a vertex about every 50 m, 400 vertices (about 20 km),
 * rounded to 1e-6 like a router's output. It wiggles across latitude 37.90 (a grid line), so it leaves and re-enters
 * grid cells. Ways of 20 vertices (ids 1000+), a named tunnel over vertices 200..230 that spans two ways.
 */
object Winding {
    private const val N = 400
    private val dLon = 50.0 / (111_320 * cos(Math.toRadians(37.9)))

    val path: List<LngLat> = (0 until N).map { i -> LngLat(r6(128.30 + i * dLon), r6(37.90 + 0.004 * sin(i / 25.0))) }

    fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6

    /** `metres` north (+) or south (-) of vertex `i`. */
    fun offset(i: Int, metres: Double) = LngLat(path[i].lon, path[i].lat + metres / 110_540)

    const val TUNNEL_FROM = 200
    const val TUNNEL_TO = 230

    fun wayAt(i: Int): Long = 1000L + i / 20

    /** Facts for a line made of `pts`, where `vertexOf[k]` is the index on [path] of point k (or -1 if not on it). */
    fun facts(pts: List<LngLat>, vertexOf: List<Int>, tunnel: Boolean = true): PathFacts {
        val ways = mutableListOf<Span<Long>>()
        val env = mutableListOf<Span<String>>()
        for (k in 0 until pts.size - 1) {
            // The road edge this edge lies on: from its start vertex, or (for a snapped start) the one before its end.
            val v = if (vertexOf[k] >= 0) vertexOf[k] else vertexOf[k + 1] - 1
            val way = wayAt(v.coerceAtLeast(0))
            val last = ways.lastOrNull()
            if (last != null && last.value == way && last.to == k) ways[ways.lastIndex] = last.copy(to = k + 1) else ways += Span(k, k + 1, way)
            val inTunnel = tunnel && v in TUNNEL_FROM until TUNNEL_TO
            val e = if (inTunnel) "tunnel" else "road"
            val le = env.lastOrNull()
            if (le != null && le.value == e && le.to == k) env[env.lastIndex] = le.copy(to = k + 1) else env += Span(k, k + 1, e)
        }
        val n = pts.size - 1
        return PathFacts(
            path = pts, wayIds = ways, roadEnvironment = env,
            roadClass = listOf(Span(0, n, "motorway")), roadClassLink = listOf(Span(0, n, false)),
            streetRef = listOf(Span(0, n, "60")), streetName = listOf(Span(0, n, "시험고속도로")),
        )
    }

    /** Vertices `from`..`to` of the road, east-bound. */
    fun slice(from: Int, to: Int = N - 1): PathFacts = facts(path.subList(from, to + 1), (from..to).toList())

    /** The whole road west-bound. */
    fun reversed(): PathFacts {
        val pts = path.reversed()
        val vs = (N - 1 downTo 0).toList()
        val ways = mutableListOf<Span<Long>>()
        val env = mutableListOf<Span<String>>()
        for (k in 0 until pts.size - 1) {
            val v = vs[k + 1]
            val way = wayAt(v)
            val last = ways.lastOrNull()
            if (last != null && last.value == way) ways[ways.lastIndex] = last.copy(to = k + 1) else ways += Span(k, k + 1, way)
            val e = if (v in TUNNEL_FROM until TUNNEL_TO) "tunnel" else "road"
            val le = env.lastOrNull()
            if (le != null && le.value == e) env[env.lastIndex] = le.copy(to = k + 1) else env += Span(k, k + 1, e)
        }
        val n = pts.size - 1
        return PathFacts(pts, ways, env, listOf(Span(0, n, "motorway")), listOf(Span(0, n, false)), listOf(Span(0, n, "60")), listOf(Span(0, n, null)))
    }

    /** Starts halfway between vertices `i` and `i+1`, like a router snapping a car's position onto an edge. */
    fun fromMidEdge(i: Int): PathFacts {
        val a = path[i]; val b = path[i + 1]
        val mid = LngLat(r6((a.lon + b.lon) / 2), r6((a.lat + b.lat) / 2))
        return facts(listOf(mid) + path.subList(i + 1, N), listOf(-1) + (i + 1 until N).toList())
    }

    /** Follows the road to vertex `at`, then turns north-east on a road of its own for about 5 km. */
    fun branching(at: Int): PathFacts {
        val base = path.subList(0, at + 1)
        val last = base.last()
        val off = (1..100).map { k -> LngLat(r6(last.lon + k * dLon * 0.7), r6(last.lat + k * 0.7 * 50 / 110_540)) }
        val pts = base + off
        val vs = (0..at).toList() + List(off.size) { -1 }
        val f = facts(pts, vs.map { if (it < 0) at else it })
        val n = pts.size - 1
        // The branch is a different way: give its edges ways of their own.
        val ways = f.wayIds.filter { it.to <= at } + Span(at, n, 9000L)
        return PathFacts(f.path, ways, f.roadEnvironment.map { if (it.from >= at) it.copy(value = "road") else it }, f.roadClass, f.roadClassLink, f.streetRef, f.streetName)
    }

    val catalog = FacilityCatalog(
        FacilityFile(
            dataVersion = "test",
            nodes = listOf(
                FacilityNode(1, FacilityKind.MOTORWAY_JUNCTION, path[150].lat, path[150].lon, "시험IC"),
                FacilityNode(2, FacilityKind.MOTORWAY_JUNCTION, offset(300, 20.0).lat, offset(300, 20.0).lon, "반대IC"),
                FacilityNode(3, FacilityKind.CAMERA, offset(260, -25.0).lat, offset(260, -25.0).lon),
                FacilityNode(4, FacilityKind.CAMERA, offset(275, 25.0).lat, offset(275, 25.0).lon),
                FacilityNode(5, FacilityKind.TOLL, offset(350, -60.0).lat, offset(350, -60.0).lon, "시험요금소"),
                FacilityNode(6, FacilityKind.MOTORWAY_JUNCTION, path[120].lat, path[120].lon, "시험휴게소"),
                FacilityNode(7, FacilityKind.MOTORWAY_JUNCTION, path[3].lat, path[3].lon, "끝IC"),
            ),
            tunnels = listOf(TunnelWay(1010, "시험터널"), TunnelWay(1011, "시험터널")),
        ),
    )
}
