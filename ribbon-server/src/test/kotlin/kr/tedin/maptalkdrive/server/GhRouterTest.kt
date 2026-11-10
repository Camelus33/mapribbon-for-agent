package kr.tedin.maptalkdrive.server

import kr.tedin.maptalkdrive.ribbon.route.FacilityKind
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.MatchVerdict
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.server.facility.FacilityScanner
import kr.tedin.maptalkdrive.server.routing.GhRouter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GhRouterTest {
    private val router = GhRouter(TinyOsm.hopper)
    private val west = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(1))
    private val east = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(29))
    private fun req(origin: LatLon = west, dest: LatLon = east, heading: Double? = 90.0, option: RouteOption = RouteOption.RECOMMENDED, navi: Int? = null) =
        RouteRequest(origin = origin, originHeadingDeg = heading, destination = dest, option = option, naviDistanceM = navi)

    @Test fun `path details come back per stretch with lowercase values`() {
        val f = router.route(req()).first().facts
        assertTrue(f.roadEnvironment.any { it.value == "tunnel" }, f.roadEnvironment.toString())
        assertTrue(f.roadClass.all { it.value == "motorway" }, f.roadClass.toString())
        assertEquals(setOf(1L, 2L, 3L), f.wayIds.map { it.value }.toSet())
        assertEquals(setOf("60"), f.streetRef.mapNotNull { it.value }.toSet())
        // Every OSM vertex kept: 27 nodes between the two snapped ends.
        assertTrue(f.path.size >= 28, "${f.path.size}")
    }

    @Test fun `the same road gives the same vertices on two requests`() {
        val a = router.route(req()).first().facts.path
        val b = router.route(req(origin = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(3) + 0.0003))).first().facts.path
        val shared = b.drop(1)
        assertTrue(shared.all { it in a })
    }

    @Test fun `the heading picks the carriageway`() {
        // Halfway between the two carriageways at lon(5), where both have their 3 m wiggle: 16.6 m from each.
        val between = LatLon((TinyOsm.EB_LAT + TinyOsm.WB_LAT) / 2 + 0.00003, TinyOsm.lon(5))
        val east = router.route(req(origin = between, heading = 90.0)).first()
        val westward = router.route(req(origin = between, heading = 270.0)).first()
        assertEquals(TinyOsm.EB_LAT, east.facts.path.first().lat, 5e-5)
        assertEquals(TinyOsm.WB_LAT, westward.facts.path.first().lat, 5e-5)
        assertTrue(westward.distanceM > east.distanceM + 500)
    }

    @Test fun `every route option routes, and free roads avoid the toll`() {
        for (opt in RouteOption.entries) assertTrue(router.route(req(option = opt)).isNotEmpty(), "$opt")
        // From the west connector to the east one: the expressway is faster, the national road is free.
        val a = LatLon(37.8975, TinyOsm.lon(0)); val b = LatLon(37.8975, TinyOsm.lon(30))
        val fast = router.route(req(origin = a, dest = b, heading = null)).first().facts
        assertTrue(fast.roadClass.any { it.value == "motorway" }, fast.roadClass.toString())
        val free = router.route(req(origin = a, dest = b, heading = null, option = RouteOption.FREE)).first().facts
        assertTrue(free.roadClass.none { it.value == "motorway" }, free.roadClass.toString())
        assertTrue(free.streetRef.any { it.value == "44" })
    }

    @Test fun `with the navigation's length the closer candidate is taken`() {
        val r = TinyOsm.service.route(req(navi = 2_500))
        assertEquals(MatchVerdict.SAME, r.match.verdict)
        val far = TinyOsm.service.route(req(origin = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(2)), navi = 9_000))
        assertEquals(MatchVerdict.DIFFERENT, far.match.verdict)
        assertTrue(abs(far.match.diffM!!) > far.match.toleranceM!!)
    }

    @Test fun `the scan finds the junction, crossing, toll, cameras and tunnel name`() {
        val f = FacilityScanner.scan(TinyOsm.file)
        assertEquals(listOf("시험IC"), f.nodes.filter { it.kind == FacilityKind.MOTORWAY_JUNCTION }.map { it.name })
        assertEquals(listOf("시험교차로"), f.nodes.filter { it.kind == FacilityKind.NAMED_NODE }.map { it.name })
        assertEquals(1, f.nodes.count { it.kind == FacilityKind.TOLL })
        assertEquals(2, f.nodes.count { it.kind == FacilityKind.CAMERA })
        assertEquals(listOf("시험터널"), f.tunnels.map { it.name })
    }

    @Test fun `anchors on a routed line, ids shared with an overlapping route and not with the other direction`() {
        val a = TinyOsm.service.route(req())
        val types = a.anchorSet.anchors.groupBy { it.type }
        assertEquals(listOf("시험터널"), types[AnchorType.TUNNEL]?.map { it.name })
        assertEquals(listOf("시험IC"), types[AnchorType.JUNCTION]?.map { it.name })
        assertEquals(1, types[AnchorType.CAMERA]?.size, "only the camera on the east-bound side")
        assertEquals(listOf("시험요금소"), types[AnchorType.TOLL]?.map { it.name })

        val b = TinyOsm.service.route(req(origin = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(3))))
        val facilities = { r: kr.tedin.maptalkdrive.ribbon.spec.RouteResponse -> r.anchorSet.anchors.filter { it.type != AnchorType.SEGMENT }.map { it.id }.toSet() }
        assertEquals(facilities(a), facilities(b))

        val back = TinyOsm.service.route(req(origin = LatLon(TinyOsm.WB_LAT, TinyOsm.lon(29)), dest = LatLon(TinyOsm.WB_LAT, TinyOsm.lon(1)), heading = 270.0))
        assertTrue(back.anchorSet.anchors.none { x -> a.anchorSet.anchors.any { it.id == x.id } })
    }

    @Test fun `route ids are a hash of the request`() {
        val s = TinyOsm.service
        assertEquals(s.routeId(req()), s.routeId(req().copy(destinationName = "다른 이름")))
        assertNotEquals(s.routeId(req()), s.routeId(req(option = RouteOption.FREE)))
        assertEquals(s.route(req()).routeId, s.route(req()).routeId)
    }
}
