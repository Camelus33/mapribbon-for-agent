package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.geo.ElevationFile
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.geo.Structure
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The height of the road along a routed line, by the same rules as tools/anchors/build-elevation.mjs, which built the
 * bundled routes' profiles: ground heights every [STEP_M] metres, smoothed over ±[SMOOTH_M] metres, and on a bridge or
 * through a tunnel a straight line between the heights at its two ends (a terrain model gives the ground, which would
 * put the car on top of the mountain in a tunnel and at the bottom of the valley on a bridge).
 *
 * The bridges and tunnels come from the router's own path details here, not from a separate OSM query.
 */
object ElevationBuilder {
    const val STEP_M = 50
    const val SMOOTH_M = 150
    /** Shorter crossings are culverts and small bridges a 90 m model cannot see anyway. */
    const val MIN_STRUCTURE_M = 60
    const val SOURCE = "Copernicus DEM GLO-90 (Open-Meteo elevation API)"
    const val ATTRIBUTION = "© DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA"

    /** Where heights are needed: every [STEP_M] metres from the start, and the end. */
    fun sampleAlongs(route: RouteIndex): List<Double> {
        val length = route.lengthM
        val out = (0..floor(length / STEP_M).toInt()).map { it * STEP_M.toDouble() }.toMutableList()
        if (out.last() < length) out += length
        return out
    }

    fun samplePoints(route: RouteIndex): List<LngLat> = sampleAlongs(route).map { route.pointAt(it) }

    /**
     * Which of `n` samples to ask the terrain model for: every other one (100 m apart; the model's cells are about
     * 90 m, so the samples between add nothing) and the last. Halves the requests to a rate-limited service.
     */
    fun askedIndices(n: Int): List<Int> = (0 until n).filter { it % 2 == 0 || it == n - 1 }

    /** Heights at every sample from those asked for, linear in between. */
    fun fill(alongs: List<Double>, asked: List<Int>, heights: List<Double>): List<Double> {
        require(asked.size == heights.size && asked.first() == 0 && asked.last() == alongs.lastIndex)
        val out = DoubleArray(alongs.size)
        asked.forEachIndexed { j, i -> out[i] = heights[j] }
        for ((j, i) in asked.withIndex().drop(1)) {
            val p = asked[j - 1]
            for (k in p + 1 until i) out[k] = out[p] + (out[i] - out[p]) * (alongs[k] - alongs[p]) / (alongs[i] - alongs[p])
        }
        return out.toList()
    }

    /**
     * Stretches of the route on a bridge or in a tunnel, in metres along it. Pieces closer than one step merge (a way
     * split in two, a bridge right before a tunnel); where they overlap the tunnel wins.
     */
    fun structures(facts: PathFacts, cum: DoubleArray, tunnelName: (Long) -> String? = { null }): List<Structure> {
        val ways = facts.perEdge(facts.wayIds)
        data class S(var from: Double, var to: Double, var kind: String, var name: String)
        val raw = facts.roadEnvironment.filter { it.value == "tunnel" || it.value == "bridge" }.mapNotNull { span ->
            val from = cum[span.from.coerceIn(0, cum.size - 1)]
            val to = cum[span.to.coerceIn(0, cum.size - 1)]
            if (to - from < MIN_STRUCTURE_M) return@mapNotNull null
            val name = if (span.value == "tunnel") (span.from until span.to).firstNotNullOfOrNull { e -> ways.getOrNull(e)?.let(tunnelName) } else null
            S(from, to, span.value, name.orEmpty())
        }.sortedBy { it.from }
        val merged = mutableListOf<S>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && s.from <= last.to + STEP_M) {
                last.to = maxOf(last.to, s.to)
                if (s.kind == "tunnel") last.kind = "tunnel"
                if (last.name.isEmpty()) last.name = s.name
            } else merged += S(s.from, s.to, s.kind, s.name)
        }
        return merged.map { Structure(it.kind, it.from.roundToInt(), it.to.roundToInt(), it.name) }
    }

    /** The profile from raw ground heights at [sampleAlongs]. */
    fun build(routeId: String, route: RouteIndex, structures: List<Structure>, raw: List<Double>): ElevationFile {
        val alongs = sampleAlongs(route)
        require(raw.size == alongs.size) { "need ${alongs.size} heights, got ${raw.size}" }
        // Smooth the ground first, so a bridge or tunnel takes its ends' heights from the smoothed line.
        val k = SMOOTH_M / STEP_M
        val smooth = raw.indices.map { i ->
            val lo = maxOf(0, i - k); val hi = minOf(raw.size - 1, i + k)
            (lo..hi).sumOf { raw[it] } / (hi - lo + 1)
        }
        fun heightAt(a: Double): Double {
            val f = a / STEP_M
            val i = minOf(smooth.size - 2, floor(f).toInt()).coerceAtLeast(0)
            return smooth[i] + (f - i) * (smooth[i + 1] - smooth[i])
        }
        val m = alongs.mapIndexed { i, a ->
            val s = structures.firstOrNull { a > it.fromM && a < it.toM } ?: return@mapIndexed smooth[i]
            val h0 = heightAt(s.fromM.toDouble()); val h1 = heightAt(s.toM.toDouble())
            h0 + (h1 - h0) * (a - s.fromM) / (s.toM - s.fromM)
        }
        return ElevationFile(
            spec = "maptalk.elevation/1", routeId = routeId, stepM = STEP_M, lengthM = route.lengthM.roundToInt(),
            source = SOURCE, attribution = ATTRIBUTION, structures = structures, m = m.map { it.roundToInt() },
        )
    }
}
