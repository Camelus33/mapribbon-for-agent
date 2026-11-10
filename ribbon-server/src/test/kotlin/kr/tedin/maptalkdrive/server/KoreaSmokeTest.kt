package kr.tedin.maptalkdrive.server

import kr.tedin.maptalkdrive.ribbon.route.FacilityCatalog
import kr.tedin.maptalkdrive.ribbon.route.RouteMatching
import kr.tedin.maptalkdrive.ribbon.route.bearingDeg
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.MatchVerdict
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.server.facility.FacilityScanner
import kr.tedin.maptalkdrive.server.routing.GhConfig
import kr.tedin.maptalkdrive.server.routing.GhRouter
import kr.tedin.maptalkdrive.server.service.RouteService
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On the real Korea graph (run with `./gradlew :ribbon-server:test -Pkorea` after the server has imported it once):
 * the server's line for the bundled expressway's two ends is the bundled route, and its ids behave on real roads.
 */
@EnabledIfSystemProperty(named = "ribbon.korea.data", matches = ".+")
class KoreaSmokeTest {
    private val assets = File("../app/src/main/assets/routes")

    private val service: RouteService by lazy {
        val data = File(System.getProperty("ribbon.korea.data"))
        val osm = File(data, "south-korea-latest.osm.pbf")
        val file = FacilityScanner.loadOrScan(osm)
        RouteService(GhRouter(GhConfig.open(osm, File(data, "graph-cache-${GhConfig.GRAPH_VERSION}")))::route, FacilityCatalog(file), file.dataVersion)
    }

    private fun bundled(id: String): Pair<RouteIndex, AnchorSet> = RouteIndex.fromGeoJson(File(assets, "$id/route.geojson").readText()) to
        RibbonJson.decodeFromString(AnchorSet.serializer(), File(assets, "$id/anchors.json").readText())

    private fun request(line: RouteIndex, fromM: Double = 0.0, naviM: Int? = null): RouteRequest {
        val a = line.pointAt(fromM); val b = line.pointAt(fromM + 60)
        val end = line.path.last()
        return RouteRequest(
            origin = LatLon(a.lat, a.lon), originHeadingDeg = bearingDeg(a, b), destination = LatLon(end.lat, end.lon), naviDistanceM = naviM,
        )
    }

    @Test fun `the expressway from Inje IC to Yangyang JC is the bundled route`() {
        val (line, bundle) = bundled("SY60E")
        val r = service.route(request(line, naviM = bundle.lengthM))
        assertEquals(MatchVerdict.SAME, r.match.verdict)
        assertTrue(abs(r.anchorSet.lengthM - bundle.lengthM) <= RouteMatching.toleranceM(bundle.lengthM))
        val ours = r.anchorSet.anchors.filter { it.type == AnchorType.TUNNEL }.map { it.name }.toSet()
        val theirs = bundle.anchors.filter { it.type == AnchorType.TUNNEL }.map { it.name }.toSet()
        assertTrue((ours intersect theirs).size >= 12, "${theirs - ours}")
        assertTrue(r.anchorSet.anchors.any { it.name == "서양양IC" })
    }

    @Test fun `starting 5 km later shares the ids, the other direction none`() {
        val (line, _) = bundled("SY60E")
        val a = service.route(request(line)).anchorSet
        val b = service.route(request(line, fromM = 5_000.0)).anchorSet
        val ids = a.anchors.map { it.id }.toSet()
        val bSegs = b.anchors.filter { it.type == AnchorType.SEGMENT }
        val firstBase = bSegs.first().id.substringBefore('-')
        val interior = b.anchors.filter { it.type != AnchorType.SEGMENT || it.id.substringBefore('-') != firstBase }
        assertTrue(interior.all { it.id in ids }, interior.filter { it.id !in ids }.map { "${it.type} ${it.name}" }.toString())

        val back = RouteIndex(line.path.reversed())
        val r = service.route(request(back)).anchorSet
        assertTrue(r.anchors.none { it.id in ids }, r.anchors.filter { it.id in ids }.map { it.name }.toString())
    }

    @Test fun `the four bundled courses`() {
        for (id in listOf("SY60E", "N44E", "J1100S", "J1135S")) {
            val (line, bundle) = bundled(id)
            val r = service.route(request(line, naviM = bundle.lengthM))
            println("$id: navi ${bundle.lengthM} m, ours ${r.anchorSet.lengthM} m, ${r.match.verdict}, candidate ${r.match.alternativeIndex + 1}/${r.match.alternatives}, " +
                r.anchorSet.anchors.groupingBy { it.type }.eachCount())
            assertTrue(r.anchorSet.anchors.isNotEmpty())
        }
    }

    private fun LngLat.toLatLon() = LatLon(lat, lon)
}
