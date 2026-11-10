package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.ElevationProfile
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElevationBuilderTest {
    private val facts = Winding.slice(0)
    private val route = RouteIndex(facts.path)

    @Test fun `samples every 50 m and at the end`() {
        val a = ElevationBuilder.sampleAlongs(route)
        assertEquals(0.0, a.first())
        assertEquals(route.lengthM, a.last(), 1e-6)
        assertTrue(a.zipWithNext().dropLast(1).all { (x, y) -> abs(y - x - 50) < 1e-9 })
    }

    @Test fun `the tunnel from the path details, named, and short crossings left out`() {
        val s = ElevationBuilder.structures(facts, route.cum) { Winding.catalog.tunnel(it)?.name }
        assertEquals(1, s.size)
        assertEquals("tunnel", s[0].kind)
        assertEquals("시험터널", s[0].name)
        assertTrue(s[0].toM - s[0].fromM in 1_400..1_600)
    }

    @Test fun `a mountain over the tunnel is crossed on a straight line, the rest follows the smoothed ground`() {
        val s = ElevationBuilder.structures(facts, route.cum)
        val tunnel = s.single()
        val alongs = ElevationBuilder.sampleAlongs(route)
        // Ground rises 1 m per 100 m, with a 300 m mountain over the tunnel.
        val raw = alongs.map { a -> a / 100 + if (a > tunnel.fromM && a < tunnel.toM) 300.0 else 0.0 }
        val file = ElevationBuilder.build("R", route, s, raw)
        val p = ElevationProfile(file)
        assertEquals(alongs.size, file.m.size)
        assertEquals(50, file.stepM)
        // Inside the tunnel the profile is one straight line (the ends take their heights from the smoothed ground,
        // as the bundled profiles do), and the car never goes over the top.
        val a = tunnel.fromM + 100.0; val b = (tunnel.fromM + tunnel.toM) / 2.0; val c = tunnel.toM - 100.0
        val onLine = p.heightAt(a) + (p.heightAt(c) - p.heightAt(a)) * (b - a) / (c - a)
        assertEquals(onLine, p.heightAt(b), 2.0)
        assertTrue(p.highestM < 300, "the car does not climb the mountain: ${p.highestM}")
        assertEquals(1_000 / 100.0, p.heightAt(1_000.0), 1.0)
    }

    @Test fun `every other sample is asked for and the rest filled in`() {
        val alongs = listOf(0.0, 50.0, 100.0, 150.0, 200.0, 230.0)
        val asked = ElevationBuilder.askedIndices(alongs.size)
        assertEquals(listOf(0, 2, 4, 5), asked)
        assertEquals(listOf(10.0, 15.0, 20.0, 25.0, 30.0, 33.0), ElevationBuilder.fill(alongs, asked, listOf(10.0, 20.0, 30.0, 33.0)))
    }
}
