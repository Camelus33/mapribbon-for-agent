package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.geo.ElevationFile
import kr.tedin.maptalkdrive.ribbon.geo.ElevationProfile
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElevationTest {
    /** A profile from a height function, sampled every 50 m over `lengthM`. */
    private fun profile(lengthM: Int, height: (Double) -> Double) = ElevationProfile(
        ElevationFile(
            spec = ElevationProfile.SPEC, routeId = "T", stepM = 50, lengthM = lengthM, source = "test", attribution = "test",
            m = (0..lengthM / 50).map { height(it * 50.0).toInt() },
        ),
    )

    @Test fun `heights interpolate between samples and clamp to the route`() {
        val p = profile(1000) { it / 10 } // 0 m to 100 m
        assertEquals(12.5, p.heightAt(125.0), 0.01)
        assertEquals(0.0, p.heightAt(-50.0), 0.01)
        assertEquals(100.0, p.heightAt(5000.0), 0.01)
    }

    @Test fun `a long steady fall is one descent with its average grade`() {
        // 2 km level at 800 m, 10 km down at 6 %, 2 km level at 200 m.
        val p = profile(14_000) { a -> when { a < 2000 -> 800.0; a < 12_000 -> 800 - (a - 2000) * 0.06; else -> 200.0 } }
        val d = p.descents().single()
        assertTrue(abs(d.fromM - 2000) < 200, "starts near 2 km: ${d.fromM}")
        assertTrue(abs(d.toM - 12_000) < 200, "ends near 12 km: ${d.toM}")
        assertEquals(0.06, d.grade, 0.005)
    }

    @Test fun `a short dip and a gentle slope are not descents`() {
        val dip = profile(6000) { a -> if (a in 2000.0..3000.0) 500 - (a - 2000) * 0.08 else if (a > 3000) 420.0 else 500.0 }
        assertTrue(dip.descents().isEmpty(), "1 km dip is too short")
        val gentle = profile(10_000) { a -> 500 - a * 0.02 }
        assertTrue(gentle.descents().isEmpty(), "2 % is not steep")
    }

    @Test fun `a level bit inside a long fall does not break it in two`() {
        // 4 km down at 7 %, 300 m level, 4 km down at 7 %.
        val p = profile(8300) { a -> when { a < 4000 -> 1000 - a * 0.07; a < 4300 -> 720.0; else -> 720 - (a - 4300) * 0.07 } }
        val d = p.descents().single()
        assertTrue(d.lengthM > 7500, "one descent over the level bit: $d")
    }

    @Test fun `temperature falls 6,5 degrees per km of height`() {
        val p = profile(10_000) { a -> a / 10 } // 0 m to 1000 m
        assertEquals(7.0 - 6.5, p.tempAt(10_000.0, 0.0, 7.0), 0.01)
        assertEquals(400.0 + 1000, p.heightForTemp(3.0, 4000.0, 9.5), 0.5) // 6.5 °C colder is 1 km higher
    }

    @Test fun `cold stretches are where the predicted temperature reaches the threshold`() {
        // Up from 0 m to 1000 m at 5 km, down again to 0 m at 10 km. 7 °C at the start: 3 °C at about 615 m.
        val p = profile(10_000) { a -> if (a <= 5000) a / 5 else (10_000 - a) / 5 }
        val c = p.coldStretches(0.0, 7.0, 3.0).single()
        assertTrue(abs(c.fromM - 3077) < 100, "from ${c.fromM}")
        assertTrue(abs(c.toM - 6923) < 100, "to ${c.toM}")
        assertEquals(0.5, c.lowestC, 0.1)
        assertTrue(p.coldStretches(0.0, 20.0, 3.0).isEmpty(), "a warm day has none")
    }

    private val assets = File("../app/src/main/assets/routes/J1100S")

    @Test fun `the 1100 Road tops out at the 1100 m rest area and comes down in one long descent`() {
        val p = ElevationProfile.fromJson(File(assets, "elevation.json").readText())
        val route = RouteIndex.fromGeoJson(File(assets, "route.geojson").readText())
        assertTrue(abs(p.lengthM - route.lengthM) < 5, "profile covers the route: ${p.lengthM} vs ${route.lengthM}")
        // The 1100 m rest area (1100고지 휴게소) is at 18.6 km on this route.
        assertTrue(p.highestM in 1050.0..1150.0, "summit ${p.highestM}")
        assertTrue(abs(p.highestAtM - 18_600) < 600, "summit at ${p.highestAtM}")
        // From the summit to Jungmun the road falls about a kilometre: one long descent, steeper than 5 % on average.
        val down = p.descents().maxBy { it.lengthM }
        assertTrue(down.fromM in 18_000.0..21_000.0 && down.lengthM > 10_000, "long descent $down")
        assertTrue(down.grade > 0.05, "grade ${down.grade}")
        // The Jeju sleet scenario: 7 °C in Jeju City. 3 °C (where wet roads may freeze) is reached around the summit.
        val cold = p.coldStretches(1000.0, 7.0, 3.0)
        assertTrue(cold.any { it.fromM < 16_100 && it.toM > 20_100 }, "the ice segments 16.1–20.1 km are predicted cold: $cold")
    }

    private fun load(id: String) = ElevationProfile.fromJson(File("../app/src/main/assets/routes/$id/elevation.json").readText()) to
        RouteIndex.fromGeoJson(File("../app/src/main/assets/routes/$id/route.geojson").readText())

    @Test fun `every route has heights over its whole length`() {
        for (id in listOf("SY60E", "N44E", "J1100S", "J1135S")) {
            val (p, route) = load(id)
            assertTrue(abs(p.lengthM - route.lengthM) < 5, "$id: profile ${p.lengthM} vs route ${route.lengthM}")
            println("$id: ${p.lowestM.toInt()}–${p.highestM.toInt()} m, top at ${p.highestAtM.toInt()} m, descents ${p.descents()}")
        }
    }

    @Test fun `the expressway runs straight through the Inje-Yangyang tunnel, not over the mountain above it`() {
        val (p, _) = load("SY60E")
        val t = p.file.structures.first { it.kind == "tunnel" && it.fromM < 12_100 && it.toM > 22_900 }
        // The first and last stored heights inside the tunnel: the portals themselves fall between samples.
        val first = (t.fromM / 50 + 1) * 50; val last = (t.toM - 1) / 50 * 50
        val h0 = p.heightAt(first.toDouble()); val h1 = p.heightAt(last.toDouble())
        for (a in first..last step 500) {
            val line = h0 + (h1 - h0) * (a - first) / (last - first)
            assertTrue(abs(p.heightAt(a.toDouble()) - line) < 2, "inside the tunnel at $a m the road is on the straight line")
        }
        assertTrue(p.highestM < 500, "the mountain above (over 1,000 m) never shows: ${p.highestM}")
        assertTrue(p.descents().isEmpty(), "an expressway falls gently: ${p.descents()}")
    }

    @Test fun `Hangyeryeong tops out at the pass and drops steeply on the Osaek side`() {
        val (p, _) = load("N44E")
        assertTrue(p.highestM in 850.0..1000.0 && p.highestAtM in 27_000.0..30_000.0, "pass ${p.highestM} m at ${p.highestAtM}")
        val down = p.descents().maxBy { it.lengthM }
        // From the pass down to Osaek the road falls hard; below it the valley to Yangyang is gentler and not a descent.
        assertTrue(down.fromM in 27_000.0..31_000.0 && down.lengthM > 4_000 && down.grade > 0.06, "descent from the pass $down")
    }

    @Test fun `Pyeonghwa-ro is lower and gentler than the 1100 Road between the same ends`() {
        val (flat, _) = load("J1135S")
        val (mountain, _) = load("J1100S")
        assertTrue(flat.highestM < 600 && mountain.highestM > 1000, "${flat.highestM} vs ${mountain.highestM}")
        val steepest = { p: ElevationProfile -> p.descents().maxOfOrNull { it.grade } ?: 0.0 }
        assertTrue(steepest(flat) < steepest(mountain), "${flat.descents()} vs ${mountain.descents()}")
    }
}
