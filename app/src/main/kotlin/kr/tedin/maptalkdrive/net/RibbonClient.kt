package kr.tedin.maptalkdrive.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kr.tedin.maptalkdrive.ribbon.geo.ElevationFile
import kr.tedin.maptalkdrive.ribbon.spec.ApiError
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.KnotUpload
import kr.tedin.maptalkdrive.ribbon.spec.KnotUploadResult
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonsResponse
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the ribbon server (ribbon-server, see its HttpApi): asks for the navigation's route drawn with anchors,
 * reads the ribbons on it, sends this car's knots. Plain HttpURLConnection, which asks for and unpacks gzip by itself.
 */
class RibbonClient(private val base: String) {
    sealed class Failure(message: String) : Exception(message) {
        /** No answer at all: the server is not running, or `adb reverse` is missing. */
        class Unreachable(cause: IOException) : Failure(cause.toString())
        /** The server is up but still loading the map. */
        class Loading : Failure("loading")
        class NotFound : Failure("not found")
        /** The server could not draw a route between the points. */
        class NoRoute(message: String) : Failure(message)
        class Http(val code: Int, message: String) : Failure("$code $message")
    }

    suspend fun route(req: RouteRequest): RouteResponse =
        call("POST", "/v1/route", RibbonJson.encodeToString(RouteRequest.serializer(), req), RouteResponse.serializer())

    suspend fun ribbons(routeId: String): RibbonsResponse =
        call("GET", "/v1/ribbons?route=" + URLEncoder.encode(routeId, "UTF-8"), null, RibbonsResponse.serializer())

    suspend fun upload(knots: List<Knot>, simulated: Boolean = false): KnotUploadResult =
        call("POST", "/v1/knots", RibbonJson.encodeToString(KnotUpload.serializer(), KnotUpload(simulated = simulated, knots = knots)), KnotUploadResult.serializer())

    /** The road's height along a route the server drew; null while the server is still building it (202). */
    suspend fun elevation(routeId: String): ElevationFile? =
        call("GET", "/v1/elevation?route=" + URLEncoder.encode(routeId, "UTF-8"), null, ElevationFile.serializer(), nullOn202 = true)

    private suspend fun <T> call(
        method: String, path: String, body: String?, serializer: KSerializer<T>, nullOn202: Boolean = false,
    ): T = withContext(Dispatchers.IO) {
        val conn = try {
            (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
        } catch (e: IOException) {
            throw Failure.Unreachable(e)
        }
        try {
            val code = try { conn.responseCode } catch (e: IOException) { throw Failure.Unreachable(e) }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            @Suppress("UNCHECKED_CAST")
            if (code == 202 && nullOn202) return@withContext null as T
            if (code in 200..299) return@withContext RibbonJson.decodeFromString(serializer, text)
            val err = runCatching { RibbonJson.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            throw when {
                code == 503 -> Failure.Loading()
                code == 404 -> Failure.NotFound()
                code == 422 -> Failure.NoRoute(err?.message ?: "no route")
                else -> Failure.Http(code, err?.error ?: text.take(200))
            }
        } finally {
            conn.disconnect()
        }
    }
}
