package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End to end on the real demo assets: simulated cars → detector → engine → briefing, exactly as the app runs it. */
class DemoRouteTest {
    private val assets = File("../app/src/main/assets")
    private fun anchorsOf(routeId: String) = RibbonJson.decodeFromString<AnchorSet>(File(assets, "routes/$routeId/anchors.json").readText())
    private fun routeOf(routeId: String) = RouteIndex.fromGeoJson(File(assets, "routes/$routeId/route.geojson").readText())
    private val anchorSet = anchorsOf("SY60E")
    private val baseline = RibbonJson.decodeFromString<Baseline>(File(assets, "baseline.json").readText())
    private val now = 1_799_990_000_000L

    private fun run(name: String): Pair<List<Knot>, Map<String, RibbonState>> {
        val scenario = RibbonJson.decodeFromString<Scenario>(File(assets, "scenarios/$name.json").readText())
        val anchorSet = anchorsOf(scenario.routeId)
        val route = routeOf(scenario.routeId)
        val knots = scenario.vehicles.flatMap { v ->
            val detector = PassDetector(route, anchorSet.anchors, pseudonym = v.id, widthClass = v.widthClass)
            ScenarioDrive.samples(v, route, anchorSet.anchors, startMs = now - v.startMinutesAgo * 60_000L)
                .flatMap { detector.accept(it) }.toList()
        }
        val states = RibbonEngine(anchorSet.anchors, baseline).states(knots, now)
        // Facilities by name where it is unique, segments (named by their km) and the rest by id.
        return knots to states.associateBy { s -> s.anchor.name.takeIf { n -> s.anchor.type != AnchorType.SEGMENT && anchorSet.anchors.count { it.name == n } == 1 } ?: s.anchor.id }
    }

    @Test fun `every simulated car ties one knot per anchor`() {
        val (knots, _) = run("local-weather")
        assertEquals(9 * anchorSet.anchors.size, knots.size, knots.groupBy { it.anchorId }.mapValues { it.value.size }.toString())
    }

    @Test fun `local weather lights up the tunnel exit, the IC and the camera and nothing else`() {
        val (_, s) = run("local-weather")
        val tunnel = s.getValue("인제양양터널")
        assertEquals(RibbonStatus.PUBLISHED, tunnel.status)
        assertEquals(60, tunnel.window!!.minutes)
        assertEquals(9, tunnel.n)
        assertEquals(Deviation.HIGH, tunnel.deviation)
        assertEquals(Metric.WIPER_TURNED_ON_RATE, tunnel.reasons.first().metric)
        assertEquals("인제양양터널 출구, 최근 1시간 9대 중 67%가 출구에서 와이퍼를 켬 (평소 3%).", tunnel.gist)

        val ic = s.getValue("서양양IC")
        assertTrue(ic.deviation != Deviation.NONE && ic.reasons.first().metric == Metric.LATE_LANE_CHANGE_RATE, ic.toString())

        // The two segments past the tunnel, where three cars keep their fog lights on for 3 km; the rain itself covers
        // every segment east of the tunnel alike, so it is not a difference on any of them.
        val different = s.values.filter { it.deviation != Deviation.NONE }.map { it.anchor.id }.sorted()
        assertEquals(listOf("SY60E-C01", "SY60E-J01", "SY60E-S04", "SY60E-S05", "SY60E-T08"), different)
        for (id in listOf("SY60E-S04", "SY60E-S05")) {
            val seg = s.getValue(id)
            assertEquals(Metric.FOG_ON_RATE, seg.reasons.single().metric, seg.gist)
            assertEquals(Deviation.SOME, seg.deviation)
        }
        // The camera's hard braking stays with the camera: the segment around it saw none.
        assertEquals(0.0, s.getValue("SY60E-S03").metrics[Metric.HARD_BRAKE_RATE])

        val b = Briefing.build(anchorSet.anchors, s.values.toList())
        assertEquals("SY60E-T08", b.headline!!.anchor.id)
        assertEquals(3, b.labeled.size)
    }

    @Test fun `cars stopping on open road light up that segment and nothing else`() {
        val (knots, s) = run("stopped-cars")
        assertEquals(9 * anchorSet.anchors.size, knots.size)
        val different = s.values.filter { it.deviation != Deviation.NONE }
        assertEquals(listOf("SY60E-S06"), different.map { it.anchor.id })
        val seg = different.single()
        assertEquals(Deviation.HIGH, seg.deviation)
        assertEquals(setOf(Metric.HARD_BRAKE_RATE, Metric.STOP_RATE), seg.reasons.map { it.metric }.toSet(), seg.gist)
        assertEquals(4.0 / 9, seg.metrics.getValue(Metric.STOP_RATE), 0.001)
        // Stopping is further above its usual (2%) than hard braking (3%), so it leads.
        assertEquals(Metric.STOP_RATE, seg.reasons.first().metric)
        assertEquals("26.0–27.3 km, 최근 1시간 9대 중 44%가 멈춤 (평소 2%).", seg.gist)
    }

    @Test fun `rain over the whole road is reported as context, never as a difference`() {
        val (_, s) = run("rain-everywhere")
        assertTrue(s.values.all { it.status == RibbonStatus.PUBLISHED }, "every anchor has nine cars")
        assertTrue(s.values.all { it.deviation == Deviation.NONE }, s.values.filter { it.deviation != Deviation.NONE }.joinToString { it.gist })
        assertTrue(s.values.all { it.metrics[Metric.WIPER_ON_RATE] == 1.0 })
    }

    @Test fun `on the Hangyeryeong road fog past the tunnel and braking downhill light up, the valley junctions do not`() {
        val (knots, s) = run("hangyeryeong-fog")
        val set = anchorsOf("N44E")
        assertEquals(9 * set.anchors.size, knots.size, knots.groupBy { it.anchorId }.mapValues { it.value.size }.toString())
        val tunnel = s.getValue("남설악터널")
        assertEquals(Deviation.HIGH, tunnel.deviation, tunnel.gist)
        assertEquals(Metric.FOG_TURNED_ON_RATE, tunnel.reasons.first().metric)
        val camera = s.values.single { it.anchor.type == AnchorType.CAMERA }
        assertEquals(Metric.HARD_BRAKE_RATE, camera.reasons.firstOrNull()?.metric, camera.gist)
        // Between facilities: the hairpin segment on the pass, and the two segments past the tunnel where the fog stays.
        val different = s.values.filter { it.deviation != Deviation.NONE }.map { it.anchor.id }.sorted()
        assertEquals(listOf("N44E-C01", "N44E-S17", "N44E-S25", "N44E-S26", "N44E-T01"), different)
        val hairpin = s.getValue("N44E-S17")
        assertEquals(Deviation.HIGH, hairpin.deviation, hairpin.gist)
        assertEquals(Metric.HARD_BRAKE_RATE, hairpin.reasons.first().metric)
        assertTrue(set.anchors.single { it.id == "N44E-S17" }.let { 32_425 in it.alongM..it.endAlongM }, "the segment holds the 32.4 km hairpin")
        for (id in listOf("N44E-S25", "N44E-S26")) assertEquals(Metric.FOG_ON_RATE, s.getValue(id).reasons.first().metric)
        // The fog past the tunnel is labeled once; the other labels go to the hairpin and the camera.
        val b = Briefing.build(set.anchors, s.values.toList())
        assertEquals(listOf("N44E-S17", "N44E-C01", "N44E-T01"), b.labeled.map { it.anchor.id })
        val cameraAt = set.anchors.single { it.id == camera.anchor.id }.alongM
        val aroundCamera = set.anchors.single { it.type == AnchorType.SEGMENT && cameraAt in it.alongM..it.endAlongM }
        assertEquals(0.0, s.getValue(aroundCamera.id).metrics[Metric.HARD_BRAKE_RATE], "the camera's braking stays with the camera")
    }

    @Test fun `segments tile the open road between tunnels without gaps or overlaps`() {
        for (id in listOf("SY60E", "N44E")) {
            val set = anchorsOf(id)
            val segs = set.anchors.filter { it.type == AnchorType.SEGMENT }.sortedBy { it.alongM }
            val tunnels = set.anchors.filter { it.type == AnchorType.TUNNEL }
            assertTrue(segs.isNotEmpty())
            for (sg in segs) {
                assertTrue(sg.endAlongM - sg.alongM in 500..3000, sg.toString())
                assertTrue(tunnels.none { t -> sg.alongM < t.endAlongM && sg.endAlongM > t.alongM }, "$sg overlaps a tunnel")
                assertEquals(0, sg.zoneBeforeM); assertEquals(0, sg.zoneAfterM)
            }
            segs.zipWithNext().forEach { (a, b) -> assertTrue(b.alongM >= a.endAlongM, "$a / $b") }
        }
    }
}
