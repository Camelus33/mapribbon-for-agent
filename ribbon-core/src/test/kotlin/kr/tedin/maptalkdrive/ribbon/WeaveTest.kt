package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.Cautions
import kr.tedin.maptalkdrive.ribbon.brief.Weave
import kr.tedin.maptalkdrive.ribbon.brief.WeaveOutcome
import kr.tedin.maptalkdrive.ribbon.brief.WeavePlan
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.geo.Curvature
import kr.tedin.maptalkdrive.ribbon.geo.ElevationProfile
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "knots become a ribbon" replay, planned from the ribbons of the Jeju demo scenarios. */
class WeaveTest {
    private val now = 1_799_990_000_000L
    private val assets = File("../app/src/main/assets")
    private val baseline = RibbonJson.decodeFromString<Baseline>(File(assets, "baseline.json").readText())

    private fun planOf(name: String, withBaseline: Boolean = true): WeavePlan {
        val sc = RibbonJson.decodeFromString<Scenario>(File(assets, "scenarios/$name.json").readText())
        val set = RibbonJson.decodeFromString<AnchorSet>(File(assets, "routes/${sc.routeId}/anchors.json").readText())
        val route = RouteIndex.fromGeoJson(File(assets, "routes/${sc.routeId}/route.geojson").readText())
        val elev = ElevationProfile.fromJson(File(assets, "routes/${sc.routeId}/elevation.json").readText())
        val knots = sc.vehicles.flatMap { v ->
            val d = PassDetector(route, set.anchors, v.id, v.widthClass)
            ScenarioDrive.samples(v, route, set.anchors, now - v.startMinutesAgo * 60_000L).flatMap { d.accept(it) }.toList()
        }
        val engine = if (withBaseline) RibbonEngine(set.anchors, baseline) else RibbonEngine(set.anchors)
        val b = Briefing.build(set.anchors, engine.states(knots, now))
        val cautions = Cautions.build(b, Curvature.stretches(route), Curvature.hairpins(route), now)
        return Weave.plan(b, cautions, route.lengthM, elev.highestAtM)
    }

    @Test fun `the ice at the summit - seven of nine cars, a ribbon ties, standing out from neighbours and usual`() {
        val p = planOf("jeju-1100-ice")
        assertEquals(Metric.ICE_RISK_RATE, p.metric)
        assertEquals(9, p.cars)
        // A place judged on a shorter window counts fewer cars; the replay says so rather than "nine cars everywhere".
        assertTrue(p.fewestCars in p.minCars until p.cars, "${p.fewestCars}")
        val f = p.focus!!
        assertEquals(7, f.hits)
        assertTrue(f.pointM in 16_000..20_200, "${f.pointM}")
        assertTrue(p.tied)
        assertEquals(WeaveOutcome.TIED, p.outcome)
        assertEquals(2, p.tiedIds.size)
        assertEquals(0.0, p.neighbourRate!!, 0.05)
        assertTrue(p.usualRate!! < 0.05)
        // Every place but the two at the summit has no coloured knot, and those two tie as their ribbons do.
        assertEquals(p.tiedIds, p.places.filter { it.hits > 0 }.map { it.anchorId }.toSet())
        assertTrue(p.places.filter { it.anchorId in p.tiedIds }.all { it.deviation != Deviation.NONE })
        assertTrue(p.places.filter { it.anchorId !in p.tiedIds }.all { it.deviation == Deviation.NONE })
    }

    @Test fun `fog in the forest - the story is fog, and its ribbon ties`() {
        val p = planOf("jeju-1100-fog")
        assertTrue(p.metric in setOf(Metric.FOG_ON_RATE, Metric.FOG_TURNED_ON_RATE, Metric.REAR_FOG_RATE), "${p.metric}")
        assertEquals(WeaveOutcome.TIED, p.outcome)
    }

    @Test fun `the same signal on the whole road - every knot coloured, no ribbon`() {
        val p = planOf("jeju-1100-sleet-everywhere")
        assertEquals(Metric.ICE_RISK_RATE, p.metric)
        assertFalse(p.tied)
        assertEquals(WeaveOutcome.LIKE_NEIGHBOURS, p.outcome)
        assertTrue(p.tiedIds.isEmpty())
        assertTrue(p.places.filter { it.n > 0 }.all { it.hits == it.n })
        assertEquals(1.0, p.neighbourRate!!, 0.05)
        // Among equals the focus is the top of the climb.
        assertTrue(p.focus!!.pointM in 16_000..20_200, "${p.focus!!.pointM}")
    }

    @Test fun `without usual values the verdict still has the neighbours`() {
        val p = planOf("jeju-1100-ice", withBaseline = false)
        assertTrue(p.tied)
        assertEquals(null, p.usualRate)
        assertEquals(0.0, p.neighbourRate!!, 0.05)
    }

    @Test fun `timing - every knot is down before the verdict, the counter ends at the ribbon's count`() {
        val p = planOf("jeju-1100-ice")
        val fi = p.places.indexOf(p.focus)
        assertEquals(0, p.hitsBy(fi, 0.0))
        assertEquals(p.focus!!.hits, p.hitsBy(fi, p.evalS))
        assertEquals(p.focus!!.n, p.passedBy(fi, p.evalS))
        assertEquals(fi, p.focusIndex)
        for (i in p.places.indices) for (c in 0 until p.cars) assertTrue(p.dropS(i, c) < p.evalS)
        assertEquals(null, p.carAlong(8, 0.0))
        assertTrue(p.endS > p.evalS + p.barsS + p.drawS)
        // Each place's coloured knots are its ribbon's count, whoever drops them.
        for (i in p.places.indices) assertEquals(p.places[i].hits, (0 until p.cars).count { p.passes(i, it) && p.colored(i, it) })
    }
}
