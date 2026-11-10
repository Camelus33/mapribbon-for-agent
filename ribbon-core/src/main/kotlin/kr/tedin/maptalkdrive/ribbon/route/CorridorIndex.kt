package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where a point lies relative to the route. `side` is +1 left of the direction of travel, -1 right, 0 on the line
 * (within 0.5 m). One projection per pass: a route that goes by the same place twice gives two.
 */
data class Projection(val pos: PathPos, val alongM: Double, val distanceM: Double, val side: Int)

/**
 * Projects points onto a route using only the edges near them (edges bucketed by 0.01° cells), so a 400 km route
 * with a thousand candidate nodes stays cheap.
 */
class CorridorIndex(private val path: List<LngLat>, private val cum: DoubleArray) {
    private val buckets = HashMap<Long, MutableList<Int>>()

    init {
        for (e in 0 until path.size - 1) {
            val a = path[e]; val b = path[e + 1]
            val y0 = cell(minOf(a.lat, b.lat)); val y1 = cell(maxOf(a.lat, b.lat))
            val x0 = cell(minOf(a.lon, b.lon)); val x1 = cell(maxOf(a.lon, b.lon))
            for (y in y0..y1) for (x in x0..x1) buckets.getOrPut(FacilityCatalog.key(y, x)) { mutableListOf() } += e
        }
    }

    /** The cells the route runs through, grown by one cell on every side. */
    fun cellsAround(): Set<Long> {
        val out = HashSet<Long>()
        for (k in buckets.keys) {
            val cy = Math.floorDiv(k + 500_000L, 1_000_000L)
            val cx = k - cy * 1_000_000L
            for (dy in -1..1) for (dx in -1..1) out += FacilityCatalog.key(cy + dy, cx + dx)
        }
        return out
    }

    /** Every pass of the route within `maxDistM` of `p`, nearest projection of each pass, in route order. */
    fun project(p: LngLat, maxDistM: Double): List<Projection> {
        val cy = cell(p.lat); val cx = cell(p.lon)
        val edges = sortedSetOf<Int>()
        for (dy in -1..1) for (dx in -1..1) buckets[FacilityCatalog.key(cy + dy, cx + dx)]?.let { edges += it }
        val hits = edges.mapNotNull { e -> projectOnEdge(p, e).takeIf { it.distanceM <= maxDistM } }.sortedBy { it.alongM }
        val passes = mutableListOf<MutableList<Projection>>()
        for (h in hits) {
            val last = passes.lastOrNull()?.last()
            if (last != null && h.alongM - last.alongM <= 2 * maxDistM + 50) passes.last() += h else passes += mutableListOf(h)
        }
        return passes.map { pass -> pass.minBy { it.distanceM } }
    }

    private fun projectOnEdge(p: LngLat, e: Int): Projection {
        val mx = 111_320 * cos(Math.toRadians(p.lat))
        val my = 110_540.0
        val a = path[e]; val b = path[e + 1]
        val ax = (a.lon - p.lon) * mx; val ay = (a.lat - p.lat) * my
        val dx = (b.lon - a.lon) * mx; val dy = (b.lat - a.lat) * my
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val ox = ax + t * dx; val oy = ay + t * dy // from p to the foot point
        val d = hypot(ox, oy)
        // Cross product of the edge direction and the vector from the foot point to p: > 0 means p is on the left.
        val cross = dx * (-oy) - dy * (-ox)
        val side = if (d < 0.5) 0 else if (cross > 0) 1 else -1
        return Projection(PathPos(e, t), cum[e] + t * (cum[e + 1] - cum[e]), d, side)
    }

    private fun cell(deg: Double): Long = floor(deg / FacilityCatalog.CELL_DEG).toLong()
}
