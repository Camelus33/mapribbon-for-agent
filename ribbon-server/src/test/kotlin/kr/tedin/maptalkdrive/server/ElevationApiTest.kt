package kr.tedin.maptalkdrive.server

import kr.tedin.maptalkdrive.ribbon.geo.ElevationFile
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import kr.tedin.maptalkdrive.server.api.HttpApi
import kr.tedin.maptalkdrive.server.elevation.ApiAnswer
import kr.tedin.maptalkdrive.server.elevation.ElevationService
import kr.tedin.maptalkdrive.server.elevation.HeightSource
import kr.tedin.maptalkdrive.server.elevation.OpenMeteoHeights
import kr.tedin.maptalkdrive.server.store.KnotStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElevationApiTest {
    private val api = HttpApi(KnotStore(), port = 0).also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest fun stop() = api.stop()

    private fun get(path: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${api.port}$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test fun `the profile of a drawn route is built in the background and served when ready`() {
        // The ground rises 1 m per 10 m of longitude step; the tunnel stretch gets a straight line regardless.
        val el = ElevationService(HeightSource { pts -> pts.map { (it.lon - 128.3) * 10_000 } }, tunnelName = { if (it == 2L) "시험터널" else null })
        api.elevation.set(el)
        api.service.set(TinyOsm.service)
        val req = RouteRequest(origin = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(1)), originHeadingDeg = 90.0, destination = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(29)))
        val route = RibbonJson.decodeFromString(
            RouteResponse.serializer(),
            http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${api.port}/v1/route")).POST(HttpRequest.BodyPublishers.ofString(RibbonJson.encodeToString(RouteRequest.serializer(), req))).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        )
        var r = get("/v1/elevation?route=${route.routeId}")
        val until = System.currentTimeMillis() + 5_000
        while (r.statusCode() == 202 && System.currentTimeMillis() < until) { Thread.sleep(50); r = get("/v1/elevation?route=${route.routeId}") }
        assertEquals(200, r.statusCode(), r.body())
        val file = RibbonJson.decodeFromString(ElevationFile.serializer(), r.body())
        assertEquals(50, file.stepM)
        assertEquals(route.anchorSet.lengthM, file.lengthM)
        assertEquals(route.anchorSet.lengthM / 50 + 2, file.m.size, "every 50 m and the end")
        assertEquals(listOf("tunnel"), file.structures.map { it.kind })
        assertEquals("시험터널", file.structures.single().name)
        assertEquals(404, get("/v1/elevation?route=Lnope").statusCode())
    }

    @Test fun `heights are asked for once, kept on disk, and the rate limit is waited out`() {
        val file = Files.createTempFile("heights", ".tsv").toFile().also { it.delete(); it.deleteOnExit() }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val slept = mutableListOf<Long>()
        val fake = { url: String ->
            val n = url.substringAfter("latitude=").substringBefore('&').split(',').size
            // The second call is refused once, as the free API does when a minute's 600 points are used up.
            if (calls.incrementAndGet() == 2) ApiAnswer(429, "{}", 0.0, 1_000L)
            else ApiAnswer(200, """{"elevation":[${List(n) { "123.0" }.joinToString(",")}]}""", 500.0, 1_000L)
        }
        val pts = (0 until 250).map { LngLat(128.3 + it * 0.001, 37.9) }
        val first = OpenMeteoHeights(file, fake, sleep = { slept += it }, nowS = { 990L })
        assertEquals(List(250) { 123.0 }, first.heights(pts))
        assertEquals(4, calls.get(), "100 points per request, one refused and asked again")
        assertEquals(listOf(11_000L), slept, "waited until the window reset")
        first.heights(pts)
        assertEquals(4, calls.get(), "the second time from memory")
        val again = OpenMeteoHeights(file, { error("must not be asked") })
        assertEquals(250, again.cached)
        assertTrue(again.heights(pts.take(10)).all { it == 123.0 })
    }

}
