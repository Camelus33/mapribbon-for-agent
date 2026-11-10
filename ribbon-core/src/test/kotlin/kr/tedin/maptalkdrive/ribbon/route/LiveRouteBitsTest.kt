package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.live.NaviRouteDraft
import kr.tedin.maptalkdrive.ribbon.live.TestInput
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.MatchVerdict
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveRouteBitsTest {
    @Test fun `length decides whether the line is the navigation's route`() {
        assertEquals(MatchVerdict.SAME, RouteMatching.match(34_512, 34_508).verdict)
        assertEquals(1_035, RouteMatching.match(34_512, 34_508).toleranceM)
        assertEquals(MatchVerdict.DIFFERENT, RouteMatching.match(36_000, 34_508).verdict)
        assertEquals(MatchVerdict.UNKNOWN, RouteMatching.match(36_000, null).verdict)
        assertEquals(1_000, RouteMatching.toleranceM(5_000))
        assertEquals(0, RouteMatching.pick(listOf(34_600.0, 34_500.0), 34_508))
        assertEquals(1, RouteMatching.pick(listOf(41_200.0, 34_700.0, 52_000.0), 34_508))
        assertEquals(0, RouteMatching.pick(listOf(41_200.0, 34_700.0), null))
    }

    @Test fun `english names follow the build tools`() {
        assertEquals("Inje-Yangyang Tunnel", NameEn.of("인제양양터널"))
        assertEquals("Girin 5 Tunnel", NameEn.of("기린5터널"))
        assertEquals("Seoyangyang IC", NameEn.of("서양양IC"))
        assertEquals("Nohyeong Junction", NameEn.of("노형교차로"))
    }

    @Test fun `navigation answers come in any order and the length has a priority`() {
        val d = NaviRouteDraft()
        assertNull(d.result())
        d.destination = LatLon(38.06, 128.6); d.destinationName = "양양JC"
        d.remainDistanceM = 35_000
        assertFalse(d.complete())
        d.current = LatLon(37.91, 128.29); d.waypoints = emptyList(); d.option = RouteOption.FREE
        assertTrue(d.complete())
        assertEquals(35_000, d.result()!!.naviDistanceM)
        d.tripDistanceM = 34_600
        assertEquals(34_600, d.result()!!.naviDistanceM)
        d.startDistanceM = 34_508
        val r = d.result()!!
        assertEquals(34_508, r.naviDistanceM)
        assertEquals(RouteSource.NAVI, r.source)
        assertTrue(r.originIsCurrent)
        assertEquals(RouteOption.FREE, r.option)
    }

    @Test fun `test input is parsed and labelled as test`() {
        val r = TestInput.route("37.914718,128.290595", 65.0, "37.95,128.4;bad;38.0,128.5", "38.063314, 128.596721", "양양JC", "short_distance", 34_508)!!
        assertEquals(LatLon(37.914718, 128.290595), r.origin)
        assertFalse(r.originIsCurrent)
        assertEquals(2, r.waypoints.size)
        assertEquals(RouteOption.SHORT_DISTANCE, r.option)
        assertEquals(RouteSource.TEST, r.source)
        assertNull(TestInput.route(null, null, null, "not a point", null, null, null))
        val here = TestInput.route(null, null, null, "38.0,128.5", null, "nonsense", null)!!
        assertTrue(here.originIsCurrent)
        assertEquals(RouteOption.RECOMMENDED, here.option)
        assertNull(here.request(null))
        assertEquals(LatLon(37.0, 128.0), here.request(LatLon(37.0, 128.0))!!.origin)
        assertTrue(r.samePlaces(r.copy(destinationName = "다른 이름", naviDistanceM = 1)))
        assertFalse(r.samePlaces(r.copy(option = RouteOption.FREE)))
    }

    @Test fun `wire types survive json`() {
        val req = RouteRequest(origin = LatLon(37.9, 128.2), destination = LatLon(38.0, 128.5), naviDistanceM = 34_508)
        val text = RibbonJson.encodeToString(RouteRequest.serializer(), req)
        assertEquals(req, RibbonJson.decodeFromString(RouteRequest.serializer(), text))
        val withExtra = text.dropLast(1) + ",\"future\":1}"
        assertEquals(req, RibbonJson.decodeFromString(RouteRequest.serializer(), withExtra))
    }
}
