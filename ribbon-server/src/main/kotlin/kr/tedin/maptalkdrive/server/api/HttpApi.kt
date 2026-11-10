package kr.tedin.maptalkdrive.server.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.KSerializer
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.geo.ElevationFile
import kr.tedin.maptalkdrive.ribbon.spec.ApiError
import kr.tedin.maptalkdrive.ribbon.spec.KnotUpload
import kr.tedin.maptalkdrive.ribbon.spec.KnotUploadResult
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonsResponse
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import kr.tedin.maptalkdrive.ribbon.spec.ServerHealth
import kr.tedin.maptalkdrive.server.elevation.ElevationService
import kr.tedin.maptalkdrive.server.routing.NoRouteException
import kr.tedin.maptalkdrive.server.service.BadRequestException
import kr.tedin.maptalkdrive.server.service.RouteService
import kr.tedin.maptalkdrive.server.store.KnotStore
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/**
 * The ribbon server's HTTP face. It opens at once; until the map is loaded `/v1/route` and `/v1/ribbons` answer 503
 * `loading`, so a car can tell "getting ready" from "not there".
 *
 * - `POST /v1/route` [RouteRequest] → [RouteResponse]
 * - `POST /v1/knots` [KnotUpload] → [KnotUploadResult]
 * - `DELETE /v1/knots?simulated=true` → forgets the simulator's knots (cars' knots stay) → [KnotUploadResult] (`rejected` = removed)
 * - `GET /v1/ribbons?route=<routeId>` → [RibbonsResponse]
 * - `GET /v1/elevation?route=<routeId>` → the road's height profile (`maptalk.elevation/1`), or 202 while it is built
 * - `GET /v1/health` → [ServerHealth]
 */
class HttpApi(
    private val knots: KnotStore,
    private val clock: () -> Long = System::currentTimeMillis,
    host: String = "127.0.0.1",
    port: Int = 8787,
) {
    val service = AtomicReference<RouteService?>(null)
    val elevation = AtomicReference<ElevationService?>(null)
    val phase = AtomicReference("loading")

    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/v1/health") { ex -> handle(ex) { health() } }
        createContext("/v1/route") { ex -> handle(ex) { route(ex) } }
        createContext("/v1/knots") { ex -> handle(ex) { upload(ex) } }
        createContext("/v1/ribbons") { ex -> handle(ex) { ribbons(ex) } }
        createContext("/v1/elevation") { ex -> handle(ex) { elevation(ex) } }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()
    fun stop() { server.stop(0); (server.executor as? java.util.concurrent.ExecutorService)?.shutdown() }

    private class Reply(val status: Int, val body: String)

    private class HttpError(val status: Int, val error: String, message: String? = null) : RuntimeException(message)

    private fun <T> ok(serializer: KSerializer<T>, value: T) = Reply(200, RibbonJson.encodeToString(serializer, value))

    private fun health() = ok(
        ServerHealth.serializer(),
        ServerHealth(
            ready = service.get() != null, phase = phase.get(), dataVersion = service.get()?.dataVersion,
            routesCached = service.get()?.cached ?: 0, knots = knots.size,
        ),
    )

    private fun route(ex: HttpExchange): Reply {
        if (ex.requestMethod != "POST") throw HttpError(405, "method_not_allowed")
        val svc = service.get() ?: throw HttpError(503, "loading", phase.get())
        val req = parse(ex, RouteRequest.serializer())
        val res = try {
            svc.route(req)
        } catch (e: BadRequestException) {
            throw HttpError(400, "bad_request", e.message)
        } catch (e: NoRouteException) {
            throw HttpError(422, "no_route", e.message)
        }
        return ok(RouteResponse.serializer(), res)
    }

    private fun upload(ex: HttpExchange): Reply {
        if (ex.requestMethod == "DELETE") {
            if (query(ex)["simulated"] != "true") throw HttpError(400, "bad_request", "only simulated knots can be removed")
            return ok(KnotUploadResult.serializer(), KnotUploadResult(accepted = 0, duplicates = 0, rejected = knots.removeSimulated()))
        }
        if (ex.requestMethod != "POST") throw HttpError(405, "method_not_allowed")
        val up = parse(ex, KnotUpload.serializer())
        if (up.spec != KnotUpload.KNOT_UPLOAD_SPEC) throw HttpError(400, "bad_request", "spec")
        if (up.knots.size > MAX_KNOTS) throw HttpError(400, "bad_request", "at most $MAX_KNOTS knots per upload")
        return ok(KnotUploadResult.serializer(), knots.add(up))
    }

    private fun ribbons(ex: HttpExchange): Reply {
        if (ex.requestMethod != "GET") throw HttpError(405, "method_not_allowed")
        val svc = service.get() ?: throw HttpError(503, "loading", phase.get())
        val id = query(ex)["route"] ?: throw HttpError(400, "bad_request", "route")
        val r = svc.get(id) ?: throw HttpError(404, "unknown_route", id)
        val anchors = r.anchorSet.anchors
        val (list, simulated) = knots.knotsFor(anchors.map { it.id })
        val now = clock()
        val states = RibbonEngine(anchors).states(list, now)
        return ok(RibbonsResponse.serializer(), RibbonsResponse(routeId = id, nowMs = now, simulated = simulated, knots = list.size, ribbons = states))
    }

    private fun elevation(ex: HttpExchange): Reply {
        if (ex.requestMethod != "GET") throw HttpError(405, "method_not_allowed")
        val svc = service.get() ?: throw HttpError(503, "loading", phase.get())
        val el = elevation.get() ?: throw HttpError(503, "loading", phase.get())
        val id = query(ex)["route"] ?: throw HttpError(400, "bad_request", "route")
        val facts = svc.facts(id) ?: throw HttpError(404, "unknown_route", id)
        return when (val s = el.ensure(id, facts)) {
            is ElevationService.State.Ready -> ok(ElevationFile.serializer(), s.file)
            ElevationService.State.Pending -> Reply(202, RibbonJson.encodeToString(ApiError.serializer(), ApiError("pending")))
            is ElevationService.State.Failed -> throw HttpError(502, "elevation_unavailable", s.reason)
        }
    }

    private fun <T> parse(ex: HttpExchange, serializer: KSerializer<T>): T {
        val bytes = ex.requestBody.use { it.readNBytes(MAX_BODY + 1) }
        if (bytes.size > MAX_BODY) throw HttpError(413, "too_large")
        return try {
            RibbonJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw HttpError(400, "bad_request", e.message?.take(200))
        }
    }

    private fun query(ex: HttpExchange): Map<String, String> = ex.requestURI.rawQuery.orEmpty().split('&')
        .filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

    private fun handle(ex: HttpExchange, f: () -> Reply) {
        val reply = try {
            f()
        } catch (e: HttpError) {
            Reply(e.status, RibbonJson.encodeToString(ApiError.serializer(), ApiError(e.error, e.message)))
        } catch (e: Throwable) {
            // Errors too (a class missing after the jars were replaced under a running server, out of memory): the
            // client must always get an answer, never a connection left open.
            System.err.println("ribbon-server: ${ex.requestMethod} ${ex.requestURI.path} failed: $e")
            Reply(500, RibbonJson.encodeToString(ApiError.serializer(), ApiError("internal", e.javaClass.simpleName)))
        }
        ex.use {
            var body = reply.body.toByteArray(Charsets.UTF_8)
            it.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            if (it.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true && body.size > 1024) {
                body = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { g -> g.write(body) } }.toByteArray()
                it.responseHeaders.add("Content-Encoding", "gzip")
            }
            it.sendResponseHeaders(reply.status, body.size.toLong())
            it.responseBody.use { out -> out.write(body) }
        }
    }

    companion object {
        const val MAX_KNOTS = 500
        const val MAX_BODY = 2 * 1024 * 1024
    }
}
