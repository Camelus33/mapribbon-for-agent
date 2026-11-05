package kr.tedin.maptalkdrive.ribbon.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** [lon, lat] in degrees, GeoJSON order. */
data class LngLat(val lon: Double, val lat: Double)

private const val R = 6_371_008.8
private fun rad(d: Double) = d * Math.PI / 180

/** Haversine distance in meters. Port of habitus33 trail-atlas `distanceM`. */
fun distanceM(a: LngLat, b: LngLat): Double {
    val dLat = rad(b.lat - a.lat)
    val dLon = rad(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
    return 2 * R * asin(min(1.0, sqrt(h)))
}

data class Snap(val point: LngLat, val distanceM: Double, val alongM: Double)

/**
 * A single route line with cumulative distances. Port of habitus33 `buildRouteIndex` / `snapToRoute`
 * (planar approximation around the query point, exact enough for segments of a few hundred meters).
 */
class RouteIndex(val path: List<LngLat>) {
    init { require(path.size >= 2) { "route needs at least 2 points" } }

    val cum: DoubleArray = DoubleArray(path.size).also { c ->
        for (i in 1 until path.size) c[i] = c[i - 1] + distanceM(path[i - 1], path[i])
    }
    val lengthM: Double get() = cum.last()

    fun snap(p: LngLat): Snap {
        val mx = 111_320 * cos(rad(p.lat))
        val my = 110_540.0
        var best = Snap(path[0], Double.MAX_VALUE, 0.0)
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            val ax = (a.lon - p.lon) * mx; val ay = (a.lat - p.lat) * my
            val dx = (b.lon - a.lon) * mx; val dy = (b.lat - a.lat) * my
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best.distanceM) {
                best = Snap(
                    LngLat(a.lon + t * (b.lon - a.lon), a.lat + t * (b.lat - a.lat)),
                    d,
                    cum[i] + t * (cum[i + 1] - cum[i]),
                )
            }
        }
        return best
    }

    /** The point `alongM` meters from the start, clamped to the line. */
    fun pointAt(alongM: Double): LngLat {
        if (alongM <= 0) return path.first()
        for (i in 1 until path.size) {
            if (cum[i] >= alongM) {
                val t = (alongM - cum[i - 1]) / maxOf(1e-9, cum[i] - cum[i - 1])
                val a = path[i - 1]; val b = path[i]
                return LngLat(a.lon + t * (b.lon - a.lon), a.lat + t * (b.lat - a.lat))
            }
        }
        return path.last()
    }

    companion object {
        /** Reads a GeoJSON Feature or bare geometry with a LineString. */
        fun fromGeoJson(text: String): RouteIndex {
            val root = Json.parseToJsonElement(text).jsonObject
            val geometry = (root["geometry"] as? JsonObject) ?: root
            require(geometry["type"]?.jsonPrimitive?.content == "LineString") { "expected a LineString" }
            val coords = geometry["coordinates"]!!.jsonArray.map {
                val xy = it.jsonArray
                LngLat(xy[0].jsonPrimitive.double, xy[1].jsonPrimitive.double)
            }
            return RouteIndex(coords)
        }
    }
}
