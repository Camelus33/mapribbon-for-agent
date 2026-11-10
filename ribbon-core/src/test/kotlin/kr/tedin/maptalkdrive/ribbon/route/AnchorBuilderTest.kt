package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.demo.SimVehicle
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnchorBuilderTest {
    private val set = AnchorBuilder.build("R", "test", null, Winding.slice(0), Winding.catalog)
    private fun named(name: String) = set.anchors.filter { it.name == name }

    @Test fun `a tunnel over two ways of the same name is one tunnel`() {
        val t = set.anchors.filter { it.type == AnchorType.TUNNEL }
        assertEquals(1, t.size)
        assertEquals("시험터널", t[0].name)
        assertEquals("Siheom Tunnel", t[0].nameEn)
        assertTrue(t[0].lengthM!! in 1_400..1_600, "${t[0].lengthM}")
        assertEquals(300, t[0].zoneBeforeM)
    }

    @Test fun `bridges tagged as tunnels and short unnamed tunnels are dropped, long unnamed ones kept`() {
        fun tunnels(name: String?) = AnchorBuilder.build(
            "R", "test", null, Winding.slice(0),
            FacilityCatalog(Winding.catalog.file.copy(tunnels = listOf(TunnelWay(1010, name), TunnelWay(1011, name)))),
        ).anchors.filter { it.type == AnchorType.TUNNEL }
        assertTrue(tunnels("시험교").isEmpty())
        assertEquals("이름 없는 터널", tunnels(null).single().name) // 1.5 km: long enough to keep
    }

    @Test fun `junctions on the carriageway only, no rest areas, nothing at the very ends`() {
        val j = set.anchors.filter { it.type == AnchorType.JUNCTION }
        assertEquals(listOf("시험IC"), j.map { it.name })
        assertEquals(1000, j[0].zoneBeforeM)
        assertEquals(200, j[0].zoneAfterM)
        assertTrue(named("반대IC").isEmpty(), "a junction on the other carriageway 20 m away is not ours")
        assertTrue(named("시험휴게소").isEmpty())
        assertTrue(named("끝IC").isEmpty(), "within 300 m of the start")
    }

    @Test fun `cameras on the right of the road count, on the left not`() {
        val c = set.anchors.filter { it.type == AnchorType.CAMERA }
        assertEquals(1, c.size)
        assertTrue(c[0].id.startsWith("C:n3:"))
        assertEquals(400, c[0].zoneBeforeM)
    }

    @Test fun `a toll booth 60 m away is on the route`() {
        assertEquals(1, named("시험요금소").size)
    }

    @Test fun `segments cover the open road, never a tunnel, 300 m to 3_2 km each`() {
        val t = set.anchors.single { it.type == AnchorType.TUNNEL }
        val segs = set.anchors.filter { it.type == AnchorType.SEGMENT }
        assertTrue(segs.none { it.alongM < t.endAlongM && it.endAlongM > t.alongM })
        assertTrue(segs.all { it.lengthM!! in 300..3_200 }, segs.map { it.lengthM }.toString())
        val before = segs.filter { it.endAlongM <= t.alongM }
        val after = segs.filter { it.alongM >= t.endAlongM }
        assertEquals(0, before.first().alongM)
        assertEquals(t.alongM, before.last().endAlongM)
        assertEquals(t.endAlongM, after.first().alongM)
        assertEquals(set.lengthM, after.last().endAlongM)
        for (run in listOf(before, after)) run.zipWithNext { x, y -> assertEquals(x.endAlongM, y.alongM) }
    }

    @Test fun `one direction for the whole route`() {
        assertEquals(setOf("E"), set.anchors.map { it.dir }.toSet())
    }

    @Test fun `a car driving the built route ties one knot per anchor and the engine reads them`() {
        val route = RouteIndex(Winding.path)
        val v = SimVehicle(id = "v", startMinutesAgo = 10, kmh = 90.0)
        val knots = (1..3).flatMap { car ->
            val d = PassDetector(route, set.anchors, pseudonym = "car$car")
            ScenarioDrive.samples(v, route, set.anchors, startMs = 1_000_000_000L + car * 60_000L).flatMap { d.accept(it) }.toList()
        }
        val perCar = knots.groupBy { it.pseudonym }.mapValues { (_, k) -> k.map { it.anchorId }.toSet() }
        val expected = set.anchors.map { it.id }.toSet()
        for ((car, ids) in perCar) assertEquals(expected, ids, "car $car missed ${expected - ids}")
        val states = RibbonEngine(set.anchors).states(knots, knots.maxOf { it.exitedAtMs } + 1)
        assertTrue(states.all { it.status == RibbonStatus.PUBLISHED })
    }
}
