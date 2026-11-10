package kr.tedin.maptalkdrive.server

import kr.tedin.maptalkdrive.ribbon.spec.ApiError
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.KnotUpload
import kr.tedin.maptalkdrive.ribbon.spec.KnotUploadResult
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kr.tedin.maptalkdrive.ribbon.spec.RibbonsResponse
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import kr.tedin.maptalkdrive.ribbon.spec.ServerHealth
import kr.tedin.maptalkdrive.server.api.HttpApi
import kr.tedin.maptalkdrive.server.store.KnotStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpApiTest {
    private var now = 1_800_000_000_000L
    private val store = KnotStore(clock = { now })
    private val api = HttpApi(store, clock = { now }, port = 0).also { it.start() }
    private val http = HttpClient.newHttpClient()
    private val base get() = "http://127.0.0.1:${api.port}"

    @AfterTest fun stop() = api.stop()

    private fun get(path: String, gzip: Boolean = false): HttpResponse<ByteArray> = http.send(
        HttpRequest.newBuilder(URI("$base$path")).apply { if (gzip) header("Accept-Encoding", "gzip") }.GET().build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )
    private fun post(path: String, body: String) = http.send(
        HttpRequest.newBuilder(URI("$base$path")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private val req = RouteRequest(origin = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(1)), originHeadingDeg = 90.0, destination = LatLon(TinyOsm.EB_LAT, TinyOsm.lon(29)))

    @Test fun `loading until the map is in, then route, knots and ribbons`() {
        val health = RibbonJson.decodeFromString(ServerHealth.serializer(), String(get("/v1/health").body()))
        assertFalse(health.ready)
        val loading = post("/v1/route", RibbonJson.encodeToString(RouteRequest.serializer(), req))
        assertEquals(503, loading.statusCode())
        assertEquals("loading", RibbonJson.decodeFromString(ApiError.serializer(), loading.body()).error)

        api.service.set(TinyOsm.service)
        val r = post("/v1/route", RibbonJson.encodeToString(RouteRequest.serializer(), req))
        assertEquals(200, r.statusCode(), r.body())
        val route = RibbonJson.decodeFromString(RouteResponse.serializer(), r.body())
        val anchors = route.anchorSet.anchors
        assertTrue(anchors.size >= 4)

        // Three cars, each with a knot on every anchor; a fourth sends the same knot twice.
        val knots = (1..3).flatMap { car ->
            anchors.map { a -> Knot(anchorId = a.id, dir = a.dir, pseudonym = "car$car", enteredAtMs = now - 600_000 - car, exitedAtMs = now - 580_000) }
        }
        val up = post("/v1/knots", RibbonJson.encodeToString(KnotUpload.serializer(), KnotUpload(simulated = true, knots = knots + knots.first())))
        val result = RibbonJson.decodeFromString(KnotUploadResult.serializer(), up.body())
        assertEquals(KnotUploadResult(knots.size, 1, 0), result)

        val rib = get("/v1/ribbons?route=${route.routeId}", gzip = true)
        assertEquals(200, rib.statusCode())
        val body = if (rib.headers().firstValue("Content-Encoding").orElse("") == "gzip") GZIPInputStream(rib.body().inputStream()).readBytes() else rib.body()
        val ribbons = RibbonJson.decodeFromString(RibbonsResponse.serializer(), String(body))
        assertTrue(ribbons.simulated)
        assertEquals(anchors.size, ribbons.ribbons.size)
        assertTrue(ribbons.ribbons.all { it.status == RibbonStatus.PUBLISHED && it.n == 3 })
    }

    @Test fun `bad requests, unknown routes and stale knots are refused`() {
        api.service.set(TinyOsm.service)
        assertEquals(404, get("/v1/ribbons?route=Lnope").statusCode())
        assertEquals(400, post("/v1/route", "{not json").statusCode())
        val outside = req.copy(destination = LatLon(48.0, 2.0))
        assertEquals(400, post("/v1/route", RibbonJson.encodeToString(RouteRequest.serializer(), outside)).statusCode())
        val old = Knot(anchorId = "S:abc", dir = "E", pseudonym = "p", enteredAtMs = now - 4 * 3_600_000L, exitedAtMs = now - 4 * 3_600_000L + 1)
        val unsafe = old.copy(anchorId = "S/../x", exitedAtMs = now - 1, enteredAtMs = now - 2)
        val up = post("/v1/knots", RibbonJson.encodeToString(KnotUpload.serializer(), KnotUpload(knots = listOf(old, unsafe))))
        assertEquals(KnotUploadResult(0, 0, 2), RibbonJson.decodeFromString(KnotUploadResult.serializer(), up.body()))
    }

    @Test fun `the simulator's knots can be cleared, cars' knots stay`() {
        val k = { p: String -> Knot(anchorId = "S:a", dir = "E", pseudonym = p, enteredAtMs = now - 2, exitedAtMs = now - 1) }
        store.add(KnotUpload(simulated = true, knots = listOf(k("sim-v1"), k("sim-v2"))))
        store.add(KnotUpload(simulated = false, knots = listOf(k("car"))))
        val del = http.send(HttpRequest.newBuilder(URI("$base/v1/knots?simulated=true")).DELETE().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(KnotUploadResult(0, 0, 2), RibbonJson.decodeFromString(KnotUploadResult.serializer(), del.body()))
        assertEquals(1, store.size)
        assertEquals(400, http.send(HttpRequest.newBuilder(URI("$base/v1/knots")).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
        // A cleared simulator knot may be sent again.
        assertEquals(1, store.add(KnotUpload(simulated = true, knots = listOf(k("sim-v1")))).accepted)
    }

    @Test fun `knots are kept for three hours and then forgotten`() {
        store.add(KnotUpload(knots = listOf(Knot(anchorId = "S:a", dir = "E", pseudonym = "p", enteredAtMs = now - 2, exitedAtMs = now - 1))))
        assertEquals(1, store.size)
        now += 3 * 3_600_000L + 1
        store.prune()
        assertEquals(0, store.size)
        // Forgotten means it can be sent again (the duplicate key went with it).
        now -= 3 * 3_600_000L + 1
        assertEquals(1, store.add(KnotUpload(knots = listOf(Knot(anchorId = "S:a", dir = "E", pseudonym = "p", enteredAtMs = now - 2, exitedAtMs = now - 1)))).accepted)
    }
}
