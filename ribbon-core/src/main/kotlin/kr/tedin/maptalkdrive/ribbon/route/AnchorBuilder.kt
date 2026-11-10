package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kotlin.math.abs
import kotlin.math.roundToInt

/** The rules for hanging anchors on a route; the defaults follow the scripts in tools/anchors, which built the bundled routes. */
data class BuildCfg(
    val junctionOnWayM: Double = 5.0,
    val namedNodeM: Double = 12.0,
    val tollM: Double = 80.0,
    val cameraOnWayM: Double = 5.0,
    val cameraSideM: Double = 40.0,
    val cameraDirectionToleranceDeg: Double = 60.0,
    val endsSkipM: Double = 300.0,
    val junctionSpacingM: Double = 800.0,
    val sameNameM: Double = 1_000.0,
    val tollSpacingM: Double = 300.0,
    val cameraSpacingM: Double = 200.0,
    val unnamedTunnelMinM: Double = 500.0,
    val tunnelMergeGapM: Double = 5.0,
    val segmentMinRunM: Double = 500.0,
    val sliverM: Double = 300.0,
    val splitAboveM: Double = 3_200.0,
    val stepM: Double = 2_000.0,
    val latStepMicro: Long = 20_000,
    val lonStepMicro: Long = 25_000,
)

/**
 * Hangs anchors on a route line: tunnels, junctions, toll gates and cameras from OSM, and segments of open road between
 * them. Ids come from the road (see [StableIds]); positions are metres along this route. Every anchor of one route
 * shares one `dir` (the compass direction from start to end), so the ribbon engine compares each anchor with its
 * neighbours along the whole route.
 */
object AnchorBuilder {
    const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL 1.0, https://www.openstreetmap.org/copyright) · route: GraphHopper"
    private val REST_AREA = Regex("휴게소|쉼터")

    fun build(
        routeId: String,
        routeName: String,
        routeNameEn: String?,
        facts: PathFacts,
        catalog: FacilityCatalog,
        cfg: BuildCfg = BuildCfg(),
        attribution: String = ATTRIBUTION,
    ): AnchorSet {
        val b = Builder(facts, catalog, cfg)
        return AnchorSet(
            spec = "maptalk.anchors/1",
            routeId = routeId,
            routeName = routeName,
            routeNameEn = routeNameEn,
            lengthM = b.route.lengthM.roundToInt(),
            attribution = attribution,
            anchors = b.anchors(),
        )
    }

    private class Builder(val facts: PathFacts, val catalog: FacilityCatalog, val cfg: BuildCfg) {
        val route = RouteIndex(facts.path)
        val cum = route.cum
        val length = route.lengthM
        val dir = compass(bearingDeg(facts.path.first(), facts.path.last()))
        val corridor = CorridorIndex(facts.path, cum)
        val segmenter = Segmenter(facts, cfg)
        val roadClass = facts.perEdge(facts.roadClass)
        val isLink = facts.perEdge(facts.roadClassLink)
        val streetRef = facts.perEdge(facts.streetRef)

        fun anchors(): List<Anchor> {
            val tunnels = tunnels()
            val nodes = catalog.nodesIn(corridor.cellsAround())
            val all = tunnels.map { it.anchor } + junctions(nodes) + tolls(nodes) + cameras(nodes) + segments(tunnels)
            val seen = HashMap<String, Int>()
            val unique = all.map { a ->
                val n = (seen[a.id] ?: 0) + 1
                seen[a.id] = n
                if (n == 1) a else a.copy(id = "${a.id}~$n")
            }
            return unique.sortedWith(compareBy<Anchor> { it.alongM }.thenBy { it.type.ordinal }.thenBy { it.id })
        }

        // ---- tunnels -------------------------------------------------------------------------------------------

        class Tunnel(val anchor: Anchor, val from: PathPos, val to: PathPos)

        private data class TPiece(val from: Int, val to: Int, val wayId: Long?, val name: String?, val nameEn: String?)

        fun tunnels(): List<Tunnel> {
            val env = facts.perEdge(facts.roadEnvironment)
            val ways = facts.perEdge(facts.wayIds)
            val pieces = mutableListOf<TPiece>()
            for (e in 0 until facts.edges) {
                if (env[e] != "tunnel") continue
                val last = pieces.lastOrNull()
                if (last != null && last.to == e && last.wayId == ways[e]) { pieces[pieces.lastIndex] = last.copy(to = e + 1); continue }
                val tw = ways[e]?.let { catalog.tunnel(it) }
                pieces += TPiece(e, e + 1, ways[e], tw?.name, tw?.nameEn)
            }
            val merged = mutableListOf<TPiece>()
            for (p in pieces) {
                val last = merged.lastOrNull()
                if (last != null && last.name == p.name && cum[p.from] - cum[last.to] < cfg.tunnelMergeGapM) {
                    merged[merged.lastIndex] = last.copy(to = p.to, nameEn = last.nameEn ?: p.nameEn)
                } else merged += p
            }
            return merged.mapNotNull { p ->
                val lengthM = cum[p.to] - cum[p.from]
                if (p.name != null && p.name.endsWith("교")) return@mapNotNull null
                if (p.name == null && lengthM < cfg.unnamedTunnelMinM) return@mapNotNull null
                val name = p.name ?: "이름 없는 터널"
                val nameEn = p.nameEn ?: if (p.name == null) "Unnamed tunnel" else NameEn.of(p.name)
                val id = "T:w${p.wayId ?: 0}:${StableIds.hex(edgeToken(p.from), 6)}"
                val a = anchor(
                    id, AnchorType.TUNNEL, name, nameEn, cum[p.from], cum[p.to], 300, 300,
                    facts.path[p.from], "osm", p.wayId?.let { "way/$it" }, lengthM.roundToInt(),
                )
                Tunnel(a, PathPos(p.from, 0.0), PathPos(p.to - 1, 1.0))
            }
        }

        // ---- junctions -----------------------------------------------------------------------------------------

        private data class Hit(val node: FacilityNode, val p: Projection)

        private fun hits(nodes: List<FacilityNode>, kind: FacilityKind, maxM: Double): List<Hit> = nodes
            .filter { it.kind == kind }
            .flatMap { n -> corridor.project(LngLat(n.lon, n.lat), maxM).map { Hit(n, it) } }
            .sortedBy { it.p.alongM }

        fun junctions(nodes: List<FacilityNode>): List<Anchor> {
            val kept = mutableListOf<Anchor>()
            fun inside(m: Double) = m >= cfg.endsSkipM && m <= length - cfg.endsSkipM
            fun sameNameNear(name: String, m: Double) = kept.any { it.name == name && abs(it.alongM - m) < cfg.sameNameM }
            fun spaced(m: Double) = kept.none { abs(it.alongM - m) < cfg.junctionSpacingM }

            for (h in hits(nodes, FacilityKind.MOTORWAY_JUNCTION, cfg.junctionOnWayM)) {
                val name = h.node.name ?: continue
                if (REST_AREA.containsMatchIn(name) || !inside(h.p.alongM) || sameNameNear(name, h.p.alongM)) continue
                kept += junctionAt(h.p.pos, "J:n${h.node.id}", name, h.node.nameEn, "node/${h.node.id}")
            }
            for (h in hits(nodes, FacilityKind.NAMED_NODE, cfg.namedNodeM)) {
                val name = h.node.name ?: continue
                if (!inside(h.p.alongM) || sameNameNear(name, h.p.alongM) || !spaced(h.p.alongM)) continue
                kept += junctionAt(h.p.pos, "J:n${h.node.id}", name, h.node.nameEn, "node/${h.node.id}")
            }
            for ((vertex, ref) in refChanges()) {
                val pos = PathPos(vertex, 0.0)
                val m = cum[vertex]
                if (!inside(m) || !spaced(m)) continue
                val token = ref
                val p = facts.path[vertex]
                val key = "J:p${StableIds.micro(p.lat)}_${StableIds.micro(p.lon)}"
                kept += junctionAt(pos, key, "${token}번 도로 진입", "Onto route $token", null)
            }
            return kept
        }

        /**
         * Vertices where the route leaves one numbered road for another (link roads in between are skipped). Roads
         * that share a stretch carry several numbers ("31, 44, 46"); the route changes road only where the numbers
         * before and after have none in common. Returns the vertex and the number of the road it turns onto.
         */
        private fun refChanges(): List<Pair<Int, String>> {
            fun refs(s: String?): List<String> = s?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val out = mutableListOf<Pair<Int, String>>()
            var current: List<String> = emptyList()
            var lastEnd = -1
            for (e in 0 until facts.edges) {
                if (isLink[e] == true) continue
                val r = refs(streetRef[e])
                if (r.isNotEmpty() && current.isNotEmpty() && r.none { it in current } && lastEnd >= 0) out += lastEnd to r.first()
                if (r.isNotEmpty()) current = r
                lastEnd = e + 1
            }
            return out
        }

        private fun junctionAt(pos: PathPos, key: String, name: String, nameEn: String?, ref: String?): Anchor {
            val m = alongOf(pos)
            val motorway = roadClassAt(pos) == "motorway"
            return anchor(
                "$key:${StableIds.hex(incomingToken(pos), 6)}", AnchorType.JUNCTION, name, nameEn ?: NameEn.of(name),
                m, m, if (motorway) 1000 else 400, if (motorway) 200 else 150, pointOf(pos), "osm", ref, null,
            )
        }

        // ---- tolls and cameras ---------------------------------------------------------------------------------

        fun tolls(nodes: List<FacilityNode>): List<Anchor> {
            val kept = mutableListOf<Anchor>()
            for (h in hits(nodes, FacilityKind.TOLL, cfg.tollM)) {
                if (kept.any { abs(it.alongM - h.p.alongM) < cfg.tollSpacingM }) continue
                val name = h.node.name ?: "요금소"
                val motorway = roadClassAt(h.p.pos) == "motorway"
                kept += anchor(
                    "G:n${h.node.id}:${StableIds.hex(incomingToken(h.p.pos), 6)}", AnchorType.TOLL, name,
                    h.node.nameEn ?: if (h.node.name == null) "Tollgate" else NameEn.of(name),
                    h.p.alongM, h.p.alongM, if (motorway) 1000 else 500, 100, pointOf(h.p.pos), "osm", "node/${h.node.id}", null,
                )
            }
            return kept
        }

        fun cameras(nodes: List<FacilityNode>): List<Anchor> {
            val kept = mutableListOf<Anchor>()
            for (h in hits(nodes, FacilityKind.CAMERA, cfg.cameraSideM)) {
                val onWay = h.p.distanceM <= cfg.cameraOnWayM
                // Korea drives on the right: a camera beside the road watches the carriageway on its side.
                if (!onWay && h.p.side != -1) continue
                val facing = h.node.directionDeg
                if (facing != null) {
                    val travel = bearingDeg(facts.path[h.p.pos.edge], facts.path[h.p.pos.edge + 1])
                    if (angleDiff(facing, travel) > cfg.cameraDirectionToleranceDeg) continue
                }
                if (kept.any { abs(it.alongM - h.p.alongM) < cfg.cameraSpacingM }) continue
                val motorway = roadClassAt(h.p.pos) == "motorway"
                kept += anchor(
                    "C:n${h.node.id}:${StableIds.hex(incomingToken(h.p.pos), 6)}", AnchorType.CAMERA,
                    h.node.name ?: "단속카메라", h.node.nameEn ?: "Speed camera",
                    h.p.alongM, h.p.alongM, if (motorway) 400 else 300, 100, pointOf(h.p.pos), "osm", "node/${h.node.id}", null,
                )
            }
            return kept
        }

        // ---- segments ------------------------------------------------------------------------------------------

        fun segments(tunnels: List<Tunnel>): List<Anchor> {
            val origin = facts.path.first(); val dest = facts.path.last()
            var start = Segmenter.Cut(PathPos(0, 0.0), "o${round4(origin.lat)},${round4(origin.lon)}")
            val runs = mutableListOf<Pair<Segmenter.Cut, Segmenter.Cut>>()
            for (t in tunnels.sortedBy { it.anchor.alongM }) {
                runs += start to Segmenter.Cut(t.from, "t${t.anchor.id}<")
                start = Segmenter.Cut(t.to, "t${t.anchor.id}>")
            }
            runs += start to Segmenter.Cut(PathPos(facts.edges - 1, 1.0), "d${round4(dest.lat)},${round4(dest.lon)}")
            return runs.flatMap { (a, b) -> segmenter.run(a, b) }.map { p ->
                val f = alongOf(p.from); val t = alongOf(p.to)
                val name = "%.1f–%.1f km".format(java.util.Locale.ROOT, f / 1000, t / 1000)
                anchor(p.id, AnchorType.SEGMENT, name, "km %.1f–%.1f".format(java.util.Locale.ROOT, f / 1000, t / 1000), f, t, 0, 0,
                    route.pointAt((f + t) / 2), "route", null, (t - f).roundToInt())
            }
        }

        // ---- helpers -------------------------------------------------------------------------------------------

        fun alongOf(pos: PathPos): Double = cum[pos.edge] + pos.t * (cum[pos.edge + 1] - cum[pos.edge])

        fun pointOf(pos: PathPos): LngLat {
            val a = facts.path[pos.edge]; val b = facts.path[pos.edge + 1]
            return LngLat(a.lon + pos.t * (b.lon - a.lon), a.lat + pos.t * (b.lat - a.lat))
        }

        fun roadClassAt(pos: PathPos): String? = roadClass[pos.edge.coerceIn(0, facts.edges - 1)]

        /**
         * Which way the road is driven on edge `e`: the vertex it runs to and its compass direction. A route that
         * starts part-way along an edge has a different first vertex but the same end vertex and direction, so a
         * facility on that edge keeps its id; the other carriageway or direction ends elsewhere or points the other way.
         * Zero-length edges (two vertices rounded together) borrow the edge before them.
         */
        fun edgeToken(e: Int): String {
            var k = e
            while (k > 0 && facts.edgeLengthM(k) < 0.01) k--
            return "${StableIds.point(facts.path[e + 1])}@${compass(bearingDeg(facts.path[k], facts.path[k + 1]))}"
        }

        /** The edge that arrives at a position: at a vertex, the edge ending there; inside an edge, that edge. */
        fun incomingToken(pos: PathPos): String {
            val len = facts.edgeLengthM(pos.edge)
            val e = if (pos.t * len < 0.05 && pos.edge >= 1) pos.edge - 1 else pos.edge
            return edgeToken(e)
        }

        fun anchor(
            id: String, type: AnchorType, name: String, nameEn: String?, fromM: Double, toM: Double,
            before: Int, after: Int, p: LngLat, source: String, sourceRef: String?, lengthM: Int?,
        ) = Anchor(
            id = id, type = type, name = name, dir = dir, alongM = fromM.roundToInt(), endAlongM = toM.roundToInt(),
            zoneBeforeM = before, zoneAfterM = after, lat = round6(p.lat), lon = round6(p.lon), source = source,
            sourceRef = sourceRef, virtual = false, lengthM = lengthM, nameEn = nameEn,
        )

        private fun round4(v: Double) = "%.4f".format(java.util.Locale.ROOT, v)
        private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
    }
}
