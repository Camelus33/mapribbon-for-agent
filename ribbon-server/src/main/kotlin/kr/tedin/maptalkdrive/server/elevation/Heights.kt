package kr.tedin.maptalkdrive.server.elevation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/** Ground heights in metres for points, in the same order. */
fun interface HeightSource {
    fun heights(points: List<LngLat>): List<Double>
}

/** One answer from the elevation API: its body, and what is left of the rate limit (points per window). */
data class ApiAnswer(val status: Int, val body: String, val remaining: Double? = null, val resetEpochS: Long? = null)

/**
 * Ground heights from the Copernicus DEM GLO-90 through the Open-Meteo elevation API, the source the bundled routes'
 * profiles were built from. The free API counts every point as one call and allows 600 a minute, so points are asked
 * for 100 at a time, one request after another, waiting for the next minute when the budget runs out (or the API
 * says 429). Points are rounded to 1e-4 degrees (about 11 m; the model's cells are about 90 m), and every height
 * received is kept in an append-only file next to the OSM data, so a road asked about once is never asked about
 * again, across restarts too.
 */
class OpenMeteoHeights(
    private val cacheFile: File,
    private val get: (String) -> ApiAnswer = ::httpGet,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
) : HeightSource {
    private val cache = ConcurrentHashMap<Long, Double>()
    private val lock = Any()

    init {
        if (cacheFile.isFile) cacheFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 3) {
                val lat = p[0].toLongOrNull(); val lon = p[1].toLongOrNull(); val h = p[2].toDoubleOrNull()
                if (lat != null && lon != null && h != null) cache[key(lat, lon)] = h
            }
        }
    }

    val cached: Int get() = cache.size

    override fun heights(points: List<LngLat>): List<Double> {
        val q = points.map { (it.lat * 1e4).roundToLong() to (it.lon * 1e4).roundToLong() }
        // One route at a time: the rate limit is shared, and two routes on the same road share points.
        synchronized(lock) {
            val missing = q.distinct().filter { (lat, lon) -> !cache.containsKey(key(lat, lon)) }
            for (chunk in missing.chunked(BATCH)) fetch(chunk)
        }
        return q.map { (lat, lon) -> cache[key(lat, lon)] ?: error("no height for $lat,$lon") }
    }

    private fun fetch(chunk: List<Pair<Long, Long>>) {
        fun deg(v: Long) = "%.4f".format(java.util.Locale.ROOT, v / 1e4)
        val url = "$API?latitude=${chunk.joinToString(",") { deg(it.first) }}&longitude=${chunk.joinToString(",") { deg(it.second) }}"
        var failures = 0
        var waits = 0
        while (true) {
            val a = try { get(url) } catch (e: Exception) { ApiAnswer(-1, e.toString()) }
            if (a.status == 429 && waits++ < 15) {
                sleep(untilReset(a.resetEpochS))
                continue
            }
            if (a.status == 200) {
                val h = Json.parseToJsonElement(a.body).jsonObject["elevation"]!!.jsonArray.map { it.jsonPrimitive.double }
                check(h.size == chunk.size) { "short answer" }
                val lines = StringBuilder()
                chunk.forEachIndexed { i, (lat, lon) ->
                    cache[key(lat, lon)] = h[i]
                    lines.append(lat).append('\t').append(lon).append('\t').append(h[i]).append('\n')
                }
                cacheFile.appendText(lines.toString())
                // Not enough left for the next batch in this window: wait for the next one rather than be refused.
                if (a.remaining != null && a.remaining < BATCH) sleep(untilReset(a.resetEpochS))
                return
            }
            if (++failures >= 4) throw IllegalStateException("elevation API failed: ${a.status} ${a.body.take(120)}")
            sleep(2_000L * failures)
        }
    }

    private fun untilReset(resetEpochS: Long?): Long =
        resetEpochS?.let { ((it - nowS()) * 1000 + 1_000).coerceIn(1_000, 61_000) } ?: 61_000

    companion object {
        const val API = "https://api.open-meteo.com/v1/elevation"
        const val BATCH = 100
        private fun key(lat: Long, lon: Long) = (lat shl 32) or (lon and 0xffffffffL)
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        fun httpGet(url: String): ApiAnswer {
            val r = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "maptalk-ribbon-server/0.1").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            return ApiAnswer(
                r.statusCode(), r.body(),
                r.headers().firstValue("X-RateLimit-Remaining").orElse(null)?.toDoubleOrNull(),
                r.headers().firstValue("X-RateLimit-Reset").orElse(null)?.toDoubleOrNull()?.toLong(),
            )
        }
    }
}
