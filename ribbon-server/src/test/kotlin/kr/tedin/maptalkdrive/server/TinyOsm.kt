package kr.tedin.maptalkdrive.server

import com.graphhopper.GraphHopper
import kr.tedin.maptalkdrive.ribbon.route.FacilityCatalog
import kr.tedin.maptalkdrive.server.facility.FacilityScanner
import kr.tedin.maptalkdrive.server.routing.GhConfig
import kr.tedin.maptalkdrive.server.routing.GhRouter
import kr.tedin.maptalkdrive.server.service.RouteService
import java.io.File
import java.nio.file.Files

/**
 * A small piece of road network written as OSM XML, built once per test run:
 *
 * - a divided, tolled expressway (ref 60) from lon 128.300 to 128.330: the east-bound carriageway on lat 37.9000 with a
 *   named tunnel (시험터널) over its middle third and an exit node 시험IC, the west-bound one 44 m north on lat 37.9003 (33 m apart, so a point between them is within 20 m of both);
 * - a speed camera 15 m south of the east-bound carriageway (on its right) and a toll booth 20 m south of it;
 * - a free two-way national road (ref 44) 440 m south on lat 37.8960 with a named crossing 시험교차로;
 * - two-way connectors at both ends joining the three.
 */
object TinyOsm {
    private const val LON0 = 128.300
    private const val STEP = 0.001
    const val EB_LAT = 37.9000
    const val WB_LAT = 37.9003
    const val FREE_LAT = 37.8960
    private const val WIGGLE = 0.00003

    val file: File by lazy { write() }

    /** GraphHopper over the tiny network, imported once. */
    val hopper: GraphHopper by lazy {
        GhConfig.open(file, Files.createTempDirectory("gh-tiny").toFile(), small = true)
    }

    val service: RouteService by lazy {
        val facilities = FacilityScanner.scan(file)
        RouteService(GhRouter(hopper)::route, FacilityCatalog(facilities), facilities.dataVersion)
    }

    fun lon(i: Int) = LON0 + i * STEP

    private fun write(): File {
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<osm version='0.6' generator='test'>\n")
        fun node(id: Long, lat: Double, lon: Double, vararg tags: Pair<String, String>) {
            sb.append("  <node id='$id' version='1' lat='$lat' lon='$lon'>")
            tags.forEach { (k, v) -> sb.append("<tag k='$k' v='$v'/>") }
            sb.append("</node>\n")
        }
        fun way(id: Long, nodes: List<Long>, vararg tags: Pair<String, String>) {
            sb.append("  <way id='$id' version='1'>")
            nodes.forEach { sb.append("<nd ref='$it'/>") }
            tags.forEach { (k, v) -> sb.append("<tag k='$k' v='$v'/>") }
            sb.append("</way>\n")
        }
        // East-bound carriageway: nodes 100..130 west to east; west-bound: 200..230 east to west; free road: 300..330.
        for (i in 0..30) {
            // A wiggle of 3 m keeps every vertex: the importer drops points that lie on a straight line.
            val wiggle = if (i % 2 == 1) WIGGLE else 0.0
            if (i == 25) node(100L + i, EB_LAT + wiggle, lon(i), "highway" to "motorway_junction", "name" to "시험IC")
            else node(100L + i, EB_LAT + wiggle, lon(i))
            node(200L + i, WB_LAT + (if ((30 - i) % 2 == 1) WIGGLE else 0.0), lon(30 - i))
            if (i == 15) node(300L + i, FREE_LAT, lon(i), "name" to "시험교차로", "highway" to "traffic_signals")
            else node(300L + i, FREE_LAT, lon(i))
        }
        node(400, EB_LAT - 15 / 110_540.0, lon(5) + 0.0004, "highway" to "speed_camera")
        node(401, EB_LAT - 20 / 110_540.0, lon(8), "barrier" to "toll_booth", "name" to "시험요금소")
        node(402, 37.8980, lon(0)); node(403, 37.8980, lon(30))
        node(404, WB_LAT + 15 / 110_540.0, lon(12) + 0.0004, "highway" to "speed_camera") // on the west-bound side

        val motorway = arrayOf("highway" to "motorway", "oneway" to "yes", "ref" to "60", "name" to "시험고속도로", "toll" to "yes", "maxspeed" to "100")
        way(1, (100L..110L).toList(), *motorway)
        way(2, (110L..120L).toList(), *motorway, "tunnel" to "yes", "layer" to "-1", "tunnel:name" to "시험터널")
        way(3, (120L..130L).toList(), *motorway)
        way(4, (200L..230L).toList(), *motorway)
        way(5, (300L..330L).toList(), "highway" to "primary", "ref" to "44", "name" to "시험국도", "maxspeed" to "60")
        way(6, listOf(230L, 100L, 402L, 300L), "highway" to "secondary", "name" to "서쪽연결로")
        way(7, listOf(200L, 130L, 403L, 330L), "highway" to "secondary", "name" to "동쪽연결로")
        sb.append("</osm>\n")
        val f = Files.createTempFile("tiny-", ".osm").toFile()
        f.writeText(sb.toString())
        f.deleteOnExit()
        return f
    }
}
