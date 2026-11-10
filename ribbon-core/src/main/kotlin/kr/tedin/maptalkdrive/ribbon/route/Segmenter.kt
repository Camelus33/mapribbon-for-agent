package kr.tedin.maptalkdrive.ribbon.route

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Cuts the open road (between tunnels) into segments of about 2 km whose ids belong to the road.
 *
 * Every cut is a property of the road, not of the route: the places where the road crosses a fixed national grid
 * (0.02° of latitude by 0.025° of longitude, about 2.2 km square in Korea) and the tunnel portals. Pieces shorter than
 * [BuildCfg.sliverM] join the next piece; pieces longer than [BuildCfg.splitAboveM] are split evenly. Both rules look
 * only at the piece itself, so two routes that cover a stretch of road completely cut it the same way. A route's first
 * and last piece end at the route's own origin and destination and get ids of their own.
 */
internal class Segmenter(private val facts: PathFacts, private val cfg: BuildCfg) {
    data class Cut(val pos: PathPos, val token: String)
    data class Piece(val id: String, val from: PathPos, val to: PathPos, val lengthM: Double)

    private data class RawPiece(val start: Cut, val end: Cut, val lengthM: Double)

    /** The segments of one open run of road from `start` to `end`; none if the run is shorter than the minimum. */
    fun run(start: Cut, end: Cut): List<Piece> {
        if (lengthBetween(start.pos, end.pos) < cfg.segmentMinRunM) return emptyList()
        val cuts = listOf(start) + crossings(start.pos, end.pos) + end
        val raw = cuts.zipWithNext { a, b -> RawPiece(a, b, lengthBetween(a.pos, b.pos)) }
        return mergeSlivers(raw).flatMap { split(it) }
    }

    private fun mergeSlivers(raw: List<RawPiece>): List<RawPiece> {
        val out = mutableListOf<RawPiece>()
        var carry: RawPiece? = null
        for (p in raw) {
            val cur = carry?.let { RawPiece(it.start, p.end, it.lengthM + p.lengthM) } ?: p
            carry = null
            if (cur.lengthM < cfg.sliverM) carry = cur else out += cur
        }
        carry?.let { c ->
            if (out.isEmpty()) out += c
            else { val last = out.removeAt(out.lastIndex); out += RawPiece(last.start, c.end, last.lengthM + c.lengthM) }
        }
        return out
    }

    private fun split(p: RawPiece): List<Piece> {
        val base = "S:" + StableIds.hex("${p.start.token}|${p.end.token}|${(p.lengthM / 10).roundToLong()}", 10)
        if (p.lengthM <= cfg.splitAboveM) return listOf(Piece(base, p.start.pos, p.end.pos, p.lengthM))
        val n = ceil(p.lengthM / cfg.stepM).toInt()
        val marks = listOf(p.start.pos) + (1 until n).map { advance(p.start.pos, p.lengthM * it / n) } + p.end.pos
        return marks.zipWithNext().mapIndexed { j, (a, b) -> Piece("$base-${j + 1}of$n", a, b, p.lengthM / n) }
    }

    /** Where the road crosses a grid line strictly between `from` and `to`, in route order. */
    private fun crossings(from: PathPos, to: PathPos): List<Cut> {
        val out = mutableListOf<Cut>()
        for (e in from.edge..minOf(to.edge, facts.edges - 1)) {
            for ((t, token) in edgeCrossings(e)) {
                val pos = PathPos(e, t)
                if (after(pos, from) && after(to, pos)) out += Cut(pos, token)
            }
        }
        return out
    }

    /** Grid crossings on one edge, sorted by position along it; computed from the edge's own vertices only. */
    private fun edgeCrossings(e: Int): List<Pair<Double, String>> {
        val a = facts.path[e]; val b = facts.path[e + 1]
        val la = StableIds.micro(a.lat); val lb = StableIds.micro(b.lat)
        val oa = StableIds.micro(a.lon); val ob = StableIds.micro(b.lon)
        val out = mutableListOf<Pair<Double, String>>()
        val ya = Math.floorDiv(la, cfg.latStepMicro); val yb = Math.floorDiv(lb, cfg.latStepMicro)
        for (k in minOf(ya, yb) + 1..maxOf(ya, yb)) {
            val t = (k * cfg.latStepMicro - la).toDouble() / (lb - la)
            out += t to "h$k@${((oa + t * (ob - oa)) / 10).roundToLong()}"
        }
        val xa = Math.floorDiv(oa, cfg.lonStepMicro); val xb = Math.floorDiv(ob, cfg.lonStepMicro)
        for (m in minOf(xa, xb) + 1..maxOf(xa, xb)) {
            val t = (m * cfg.lonStepMicro - oa).toDouble() / (ob - oa)
            out += t to "v$m@${((la + t * (lb - la)) / 10).roundToLong()}"
        }
        return out.sortedBy { it.first }
    }

    /** Metres along the road between two positions, from edge lengths only (no running sums from the route start). */
    fun lengthBetween(a: PathPos, b: PathPos): Double {
        if (!after(b, a)) return 0.0
        if (a.edge == b.edge) return (b.t - a.t) * facts.edgeLengthM(a.edge)
        var sum = (1 - a.t) * facts.edgeLengthM(a.edge)
        for (e in a.edge + 1 until b.edge) sum += facts.edgeLengthM(e)
        return sum + b.t * facts.edgeLengthM(b.edge)
    }

    /** The position `metres` further along the road than `from`. */
    private fun advance(from: PathPos, metres: Double): PathPos {
        var left = metres
        var e = from.edge
        var t = from.t
        while (e < facts.edges) {
            val len = facts.edgeLengthM(e)
            val rest = (1 - t) * len
            if (left <= rest || e == facts.edges - 1) return PathPos(e, (t + if (len > 0) left / len else 0.0).coerceAtMost(1.0))
            left -= rest
            e++; t = 0.0
        }
        return PathPos(facts.edges - 1, 1.0)
    }

    private fun after(a: PathPos, b: PathPos): Boolean = a.edge > b.edge || (a.edge == b.edge && a.t > b.t)
}
