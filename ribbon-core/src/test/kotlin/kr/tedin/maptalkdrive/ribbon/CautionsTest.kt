package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.brief.CautionAction
import kr.tedin.maptalkdrive.ribbon.brief.Cautions
import kr.tedin.maptalkdrive.ribbon.brief.Reading
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.geo.CurveClass
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.Curvature
import kr.tedin.maptalkdrive.ribbon.geo.Hairpin
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorRef
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.Reason
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kr.tedin.maptalkdrive.ribbon.spec.Window
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CautionsTest {
    private val now = 1_799_990_000_000L
    private val min = 60_000L

    // ---------------------------------------------------------------- on the demo scenarios

    private val assets = File("../app/src/main/assets")
    private val baseline = RibbonJson.decodeFromString<Baseline>(File(assets, "baseline.json").readText())

    private fun cautionsOf(name: String) = run {
        val sc = RibbonJson.decodeFromString<Scenario>(File(assets, "scenarios/$name.json").readText())
        val set = RibbonJson.decodeFromString<AnchorSet>(File(assets, "routes/${sc.routeId}/anchors.json").readText())
        val route = RouteIndex.fromGeoJson(File(assets, "routes/${sc.routeId}/route.geojson").readText())
        val knots = sc.vehicles.flatMap { v ->
            val d = PassDetector(route, set.anchors, v.id, v.widthClass)
            ScenarioDrive.samples(v, route, set.anchors, now - v.startMinutesAgo * min).flatMap { d.accept(it) }.toList()
        }
        val b = Briefing.build(set.anchors, RibbonEngine(set.anchors, baseline).states(knots, now))
        Cautions.build(b, Curvature.stretches(route), Curvature.hairpins(route), now)
    }

    @Test fun `on the expressway the downpour comes first, then the rain past the tunnel, then the exit`() {
        val cs = cautionsOf("local-weather")
        assertEquals(listOf(Reading.HEAVY_RAIN, Reading.RAIN, Reading.EXIT_BUSY), cs.map { it.reading })
        assertEquals(listOf(listOf("SY60E-S06"), listOf("SY60E-T08"), listOf("SY60E-J01")), cs.map { it.anchorIds })
        assertEquals(listOf(1, 2, 3), cs.map { it.rank })
        assertTrue(cs[1].tunnelExit && cs[1].sudden, "rain meets the driver at the tunnel exit")
        assertEquals(listOf(CautionAction.CHOOSE_LANE_EARLY), cs[2].actions)
    }

    @Test fun `over Hangyeryeong the fog past the tunnel is one caution, and the hairpin braking weighs for the curves`() {
        val cs = cautionsOf("hangyeryeong-fog")
        assertEquals(listOf(Reading.THICK_FOG, Reading.HOLDUP, Reading.CAMERA_BRAKE), cs.map { it.reading })
        val fog = cs[0]
        assertEquals(listOf("N44E-T01", "N44E-S25", "N44E-S26"), fog.anchorIds, "tunnel exit and the two segments after it")
        assertEquals(48_037, fog.fromM); assertEquals(fog.pointM, fog.fromM, "met first at the tunnel exit")
        assertTrue(fog.tunnelExit)
        val hairpin = cs[1]
        assertTrue(hairpin.hairpins > 0 && hairpin.onSharpCurves, hairpin.toString())
        assertEquals(4, hairpin.cars); assertEquals(9, hairpin.n)
    }

    @Test fun `sleet over the summit is a single ice caution on the hairpins`() {
        val cs = cautionsOf("hangyeryeong-sleet")
        val ice = cs.single()
        assertEquals(Reading.ICE, ice.reading)
        assertEquals(listOf("N44E-S15", "N44E-S16"), ice.anchorIds)
        assertTrue(ice.hairpins > 0)
        assertEquals(listOf(CautionAction.SLOW_DOWN_EARLY, CautionAction.AVOID_SUDDEN_INPUTS), ice.actions)
    }

    @Test fun `stopped cars on open road are a queue that comes suddenly, rain everywhere is nothing to take care of`() {
        val queue = cautionsOf("stopped-cars").single()
        assertEquals(Reading.QUEUE, queue.reading)
        assertTrue(queue.sudden)
        assertTrue(cautionsOf("rain-everywhere").isEmpty())
    }

    // ---------------------------------------------------------------- the rules, one by one

    private fun item(id: String, type: AnchorType, from: Int, to: Int = from, metric: String? = null, d: Deviation = Deviation.NONE,
                     status: RibbonStatus = RibbonStatus.PUBLISHED, lastMinAgo: Long = 10): BriefingItem {
        val anchor = Road.anchor(id, type, from, endAlongM = to, before = 0, after = 0)
        val reasons = if (metric == null) emptyList() else listOf(Reason(metric, 0.6, 0.03, 0.0, d))
        val window = if (status == RibbonStatus.PUBLISHED) Window(now, 60, now - lastMinAgo * min) else null
        return BriefingItem(anchor, RibbonState(anchor = AnchorRef(id, type, "E", id, false), status = status, window = window, n = 9, deviation = d, reasons = reasons, gist = id))
    }

    private fun briefing(vararg items: BriefingItem) = Briefing(items.toList(), null, emptyList())

    @Test fun `places with the same kind of thing are one caution only when nothing usual or unknown lies between them`() {
        val fogA = item("A", AnchorType.SEGMENT, 0, 2_000, Metric.FOG_ON_RATE, Deviation.HIGH)
        val fogB = item("B", AnchorType.SEGMENT, 2_000, 4_000, Metric.FOG_ON_RATE, Deviation.HIGH)
        assertEquals(listOf(listOf("A", "B")), Cautions.build(briefing(fogA, fogB), emptyList(), emptyList(), now).map { it.anchorIds })

        val usual = item("U", AnchorType.SEGMENT, 2_000, 4_000)
        val fogC = item("C", AnchorType.SEGMENT, 4_000, 6_000, Metric.FOG_ON_RATE, Deviation.HIGH)
        assertEquals(2, Cautions.build(briefing(fogA, usual, fogC), emptyList(), emptyList(), now).size)
        val unknown = item("K", AnchorType.SEGMENT, 2_000, 4_000, status = RibbonStatus.NOT_ENOUGH)
        assertEquals(2, Cautions.build(briefing(fogA, unknown, fogC), emptyList(), emptyList(), now).size)
    }

    @Test fun `ice on a hairpin outranks heavier-looking rain, a somewhat-different place weighs less, and old news less again`() {
        val pin = listOf(Hairpin(9_000.0, 40.0, 170.0))
        val sharp = listOf(CurveStretch(8_000.0, 10_000.0, CurveClass.SHARP, 40.0, 9_000.0))
        val rain = item("R", AnchorType.SEGMENT, 0, 2_000, Metric.WIPER_FAST_RATE, Deviation.HIGH)
        val ice = item("I", AnchorType.SEGMENT, 8_000, 10_000, Metric.ICE_RISK_RATE, Deviation.SOME)
        val cs = Cautions.build(briefing(rain, item("U", AnchorType.SEGMENT, 2_000, 8_000), ice), sharp, pin, now)
        assertEquals(listOf("I", "R"), cs.map { it.anchorIds.single() })
        assertEquals(5.0 * 0.6 * 1.5 * 1.3, cs[0].score, 0.01)

        val stale = item("R", AnchorType.SEGMENT, 0, 2_000, Metric.WIPER_FAST_RATE, Deviation.HIGH, lastMinAgo = 120)
        assertEquals(3.0 * 0.75, Cautions.build(briefing(stale), emptyList(), emptyList(), now).single().score, 0.01)
    }

    @Test fun `a place reads as its most severe reason, not its most outstanding one`() {
        // At dawn fewer cars count for low beams by day, so that share can stand out more than the fast wipers that
        // cause it. The place is still heavy rain, shown by the fast wipers.
        val base = item("S", AnchorType.SEGMENT, 0, 2_000, Metric.DAY_LOW_BEAM_RATE, Deviation.HIGH)
        val s = base.copy(state = base.state.copy(reasons = listOf(
            Reason(Metric.DAY_LOW_BEAM_RATE, 0.667, 0.05, 0.0, Deviation.HIGH),
            Reason(Metric.WIPER_FAST_RATE, 0.444, 0.02, 0.0, Deviation.HIGH),
        )))
        assertEquals(Reading.HEAVY_RAIN, Reading.of(s))
        assertEquals(Metric.WIPER_FAST_RATE, Reading.evidence(s)!!.metric)
        val c = Cautions.build(briefing(s), emptyList(), emptyList(), now).single()
        assertEquals(Reading.HEAVY_RAIN, c.reading)
        assertEquals(Metric.WIPER_FAST_RATE, c.metric)
        assertEquals(4, c.cars)
    }

    @Test fun `road shape alone never makes a caution, and there are at most three`() {
        val pins = (1..10).map { Hairpin(it * 1_000.0, 40.0, 170.0) }
        val sharp = listOf(CurveStretch(0.0, 20_000.0, CurveClass.SHARP, 40.0, 5_000.0))
        assertTrue(Cautions.build(briefing(item("U", AnchorType.SEGMENT, 0, 2_000)), sharp, pins, now).isEmpty())
        val many = (0 until 6).flatMap { i ->
            listOf(item("D$i", AnchorType.SEGMENT, i * 4_000, i * 4_000 + 2_000, Metric.STOP_RATE, Deviation.HIGH), item("U$i", AnchorType.SEGMENT, i * 4_000 + 2_000, i * 4_000 + 4_000))
        }
        assertEquals(3, Cautions.build(briefing(*many.toTypedArray()), emptyList(), emptyList(), now).size)
    }
}
