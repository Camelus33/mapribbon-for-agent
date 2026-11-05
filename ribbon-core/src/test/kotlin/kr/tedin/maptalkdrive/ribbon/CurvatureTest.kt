package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.geo.CurveClass
import kr.tedin.maptalkdrive.ribbon.geo.Curvature
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurvatureTest {
    /** Builds a route from local metres (x east, y north) near 38°N. */
    private fun route(points: List<Pair<Double, Double>>): RouteIndex {
        val lat0 = 38.0
        val kx = 111_320 * cos(lat0 * PI / 180)
        return RouteIndex(points.map { (x, y) -> LngLat(128.0 + x / kx, lat0 + y / 110_540) })
    }

    /** Straight east, a left arc of `radius` through `turnDeg`, straight on. Nodes every `straightStep` / `arcStep` metres. */
    private fun bend(radius: Double, turnDeg: Double, straightStep: Double = 100.0, arcStep: Double = 20.0): RouteIndex {
        val pts = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        while (x < 2000) { pts += x to 0.0; x += straightStep }
        val turn = turnDeg * PI / 180
        val n = maxOf(2, (radius * turn / arcStep).toInt())
        for (i in 0..n) {
            val a = turn * i / n
            pts += (2000 + radius * sin(a)) to (radius * (1 - cos(a)))
        }
        val (ex, ey) = pts.last()
        var d = straightStep
        while (d <= 2000) { pts += (ex + d * cos(turn)) to (ey + d * sin(turn)); d += straightStep }
        return route(pts)
    }

    @Test fun `a 300 m curve between straights is one sharp stretch with its radius`() {
        val s = Curvature.stretches(bend(300.0, 90.0))
        assertEquals(listOf(CurveClass.STRAIGHT, CurveClass.SHARP, CurveClass.STRAIGHT), s.map { it.curve }, s.toString())
        assertTrue(s[1].minRadiusM in 255.0..345.0, s[1].toString())
        assertTrue(s[1].tightestAtM in 2000.0..2000.0 + 300 * PI / 2, s[1].toString())
    }

    @Test fun `a 1 km curve reads as a curve, not sharp`() {
        val s = Curvature.stretches(bend(1000.0, 60.0))
        assertEquals(listOf(CurveClass.STRAIGHT, CurveClass.CURVE, CurveClass.STRAIGHT), s.map { it.curve }, s.toString())
        assertTrue(s[1].minRadiusM in 850.0..1150.0, s[1].toString())
    }

    @Test fun `a short hairpin on a dense mountain road is kept, not merged into the straights`() {
        val s = Curvature.stretches(bend(30.0, 180.0, straightStep = 20.0, arcStep = 8.0))
        val sharp = s.filter { it.curve == CurveClass.SHARP }
        assertEquals(1, sharp.size, s.toString())
        assertTrue(sharp[0].minRadiusM < 100, sharp.toString())
    }

    @Test fun `a 3 m wobble on a sparse straight stays straight`() {
        val pts = (0..20).map { i -> (i * 140.0) to if (i == 10) 3.0 else 0.0 }
        val s = Curvature.stretches(route(pts))
        assertEquals(listOf(CurveClass.STRAIGHT), s.map { it.curve }, s.toString())
    }

    @Test fun `the demo expressway has no sharp curve and is mostly straight`() {
        val r = RouteIndex.fromGeoJson(File("../app/src/main/assets/routes/SY60E/route.geojson").readText())
        val s = Curvature.stretches(r)
        assertTrue(s.none { it.curve == CurveClass.SHARP }, s.toString())
        val straight = s.filter { it.curve == CurveClass.STRAIGHT }.sumOf { it.lengthM }
        assertTrue(straight / r.lengthM > 0.7, "straight share ${straight / r.lengthM}")
        assertTrue(s.count { it.curve == CurveClass.CURVE } >= 3, s.toString())
        assertEquals(0.0, s.first().fromM); assertEquals(r.lengthM, s.last().toM, 1e-6)
        s.zipWithNext().forEach { (a, b) -> assertEquals(a.toM, b.fromM, 1e-6) }
    }

    @Test fun `the Hangyeryeong road has sharp curves, most of them on the pass`() {
        val r = RouteIndex.fromGeoJson(File("../app/src/main/assets/routes/N44E/route.geojson").readText())
        val s = Curvature.stretches(r)
        val sharp = s.filter { it.curve == CurveClass.SHARP }
        assertTrue(sharp.size >= 10, "sharp stretches: ${sharp.size}")
        assertTrue(sharp.minOf { it.minRadiusM } < 150, sharp.toString())
    }

    @Test fun `a road that turns back on itself is a hairpin, a 90 degree curve is not`() {
        val hp = Curvature.hairpins(bend(30.0, 180.0, straightStep = 20.0, arcStep = 8.0))
        assertEquals(1, hp.size, hp.toString())
        assertTrue(hp[0].atM in 2000.0..2000.0 + 30 * PI, hp.toString())
        assertTrue(hp[0].turnDeg >= 150, hp.toString())
        assertTrue(Curvature.hairpins(bend(300.0, 90.0)).isEmpty())
        assertTrue(Curvature.hairpins(bend(60.0, 90.0, straightStep = 20.0, arcStep = 8.0)).isEmpty(), "a tight right angle is sharp, not a hairpin")
    }

    @Test fun `an S-bend does not add up to a hairpin`() {
        // Left 90 then right 90 within 200 m: tight, but the car ends up heading the same way.
        val pts = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        while (x < 1000) { pts += x to 0.0; x += 20 }
        val r = 40.0
        for (i in 0..10) { val a = PI / 2 * i / 10; pts += (1000 + r * sin(a)) to (r * (1 - cos(a))) }
        for (i in 1..10) { val a = PI / 2 * i / 10; pts += (1000 + r + r * (1 - cos(a))) to (r + r * sin(a)) }
        val (ex, ey) = pts.last()
        for (d in 1..50) pts += (ex + d * 20.0) to ey
        assertTrue(Curvature.hairpins(route(pts)).isEmpty(), Curvature.hairpins(route(pts)).toString())
    }

    @Test fun `the Hangyeryeong pass has its hairpins on the pass, the expressway none`() {
        val n44 = RouteIndex.fromGeoJson(File("../app/src/main/assets/routes/N44E/route.geojson").readText())
        val hp = Curvature.hairpins(n44)
        assertTrue(hp.size in 6..16, "hairpins: $hp")
        assertTrue(hp.all { it.atM in 28_000.0..37_000.0 }, hp.toString())
        hp.zipWithNext().forEach { (a, b) -> assertTrue(b.atM - a.atM >= 250, "$a $b") }
        val sy60 = RouteIndex.fromGeoJson(File("../app/src/main/assets/routes/SY60E/route.geojson").readText())
        assertTrue(Curvature.hairpins(sy60).isEmpty())
    }
}
