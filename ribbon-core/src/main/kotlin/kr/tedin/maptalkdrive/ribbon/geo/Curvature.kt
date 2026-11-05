package kr.tedin.maptalkdrive.ribbon.geo

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * How a stretch of road bends, in three classes a driver feels at expressway speed:
 * - STRAIGHT: radius 2 km or more, practically straight.
 * - CURVE: 500 m .. 2 km, the wheel turns but 100 km/h is comfortable.
 * - SHARP: under 500 m, a car at 100 km/h would have to slow down (lateral acceleration over ~0.15 g).
 * The classes are absolute, so two routes can be compared: an expressway shows no SHARP stretch, a mountain pass many.
 */
@Serializable
enum class CurveClass { STRAIGHT, CURVE, SHARP }

/** One stretch of a single class, with the tightest radius inside it and where that is. */
@Serializable
data class CurveStretch(val fromM: Double, val toM: Double, val curve: CurveClass, val minRadiusM: Double, val tightestAtM: Double) {
    val lengthM: Double get() = toM - fromM
}

object Curvature {
    const val STRAIGHT_FROM_M = 2_000.0
    const val SHARP_BELOW_M = 500.0
    /** Radii are capped here: a dead straight line reports 100 km, which JSON can carry and Infinity cannot. */
    const val MAX_RADIUS_M = 100_000.0

    fun classOf(radiusM: Double): CurveClass = when {
        radiusM >= STRAIGHT_FROM_M -> CurveClass.STRAIGHT
        radiusM >= SHARP_BELOW_M -> CurveClass.CURVE
        else -> CurveClass.SHARP
    }

    /** Radius of the circle through the road points `halfChordM` before and after `alongM` (infinite on a straight). */
    fun radiusAt(route: RouteIndex, alongM: Double, halfChordM: Double): Double {
        val lat0 = route.pointAt(alongM).lat
        val kx = 111_320 * cos(lat0 * PI / 180)
        val ky = 110_540.0
        fun xy(m: Double) = route.pointAt(m.coerceIn(0.0, route.lengthM)).let { doubleArrayOf(it.lon * kx, it.lat * ky) }
        val a = xy(alongM - halfChordM); val b = xy(alongM); val c = xy(alongM + halfChordM)
        val ab = hypot(b[0] - a[0], b[1] - a[1]); val bc = hypot(c[0] - b[0], c[1] - b[1]); val ac = hypot(c[0] - a[0], c[1] - a[1])
        val twiceArea = abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]))
        return if (twiceArea < 1e-6) Double.POSITIVE_INFINITY else ab * bc * ac / (2 * twiceArea)
    }

    /**
     * The half chord used at `alongM`: a little more than the local node spacing, between 30 and 150 m. A chord shorter
     * than the spacing would turn every polyline vertex into a tight curve; a much longer one would cut through hairpins.
     * Expressway nodes in OSM sit about 140 m apart (chord 150 m), mountain-pass nodes 10 to 30 m (chord 30 m).
     */
    fun halfChordAt(route: RouteIndex, alongM: Double): Double {
        val cum = route.cum
        val near = (1 until cum.size).filter { cum[it] >= alongM - 200 && cum[it - 1] <= alongM + 200 }
        val spacing = if (near.isEmpty()) 150.0 else near.sumOf { cum[it] - cum[it - 1] } / near.size
        return (spacing * 1.2).coerceIn(30.0, 150.0)
    }

    /**
     * Effective radius along the route every `stepM`: curvature (1/R) from [radiusAt] averaged over `smoothM` of road,
     * so where the polyline happens to have a vertex does not decide the class. A 94 m hairpin of radius 30 m averages
     * to about 65 m over 200 m: smaller numbers than the true radius never appear, but a hairpin stays clearly SHARP.
     */
    fun profile(route: RouteIndex, stepM: Double = 25.0, smoothM: Double = 200.0): List<Pair<Double, Double>> {
        val length = route.lengthM
        val at = (generateSequence(0.0) { it + stepM }.takeWhile { it < length } + sequenceOf(length)).toList()
        val k = at.map { 1 / radiusAt(route, it, halfChordAt(route, it)) }
        val half = (smoothM / 2 / stepM).toInt()
        return at.indices.map { i ->
            val w = k.subList(maxOf(0, i - half), minOf(k.size, i + half + 1))
            val mean = w.average()
            at[i] to if (mean <= 1 / MAX_RADIUS_M) MAX_RADIUS_M else 1 / mean
        }
    }

    /**
     * The route cut into class stretches. A stretch shorter than `minLengthM` joins its sharper neighbour, so a
     * near-threshold wobble disappears; a short SHARP stretch only ever joins another SHARP one, so a hairpin is never
     * painted over as straight.
     */
    fun stretches(route: RouteIndex, stepM: Double = 25.0, minLengthM: Double = 200.0): List<CurveStretch> {
        val samples = profile(route, stepM)

        class Run(var from: Double, var to: Double, val curve: CurveClass)
        val runs = mutableListOf<Run>()
        for ((i, s) in samples.withIndex()) {
            val c = classOf(s.second)
            val to = samples.getOrNull(i + 1)?.first ?: s.first
            val last = runs.lastOrNull()
            if (last != null && last.curve == c) last.to = to else runs += Run(s.first, to, c)
        }
        fun len(r: Run) = r.to - r.from
        while (true) {
            val candidates = runs.indices.filter { i ->
                len(runs[i]) < minLengthM && (runs[i].curve != CurveClass.SHARP || listOfNotNull(runs.getOrNull(i - 1), runs.getOrNull(i + 1)).any { it.curve == CurveClass.SHARP })
            }
            val i = candidates.minByOrNull { len(runs[it]) } ?: break
            val short = runs[i]
            val left = runs.getOrNull(i - 1)
            val into = listOfNotNull(left, runs.getOrNull(i + 1))
                .maxWithOrNull(compareBy<Run> { it.curve }.thenBy { len(it) })!!
            if (into === left) into.to = short.to else into.from = short.from
            runs.removeAt(i)
            // Two neighbours of one class may now touch: join them.
            var j = 1
            while (j < runs.size) {
                if (runs[j].curve == runs[j - 1].curve) { runs[j - 1].to = runs[j].to; runs.removeAt(j) } else j++
            }
        }
        return runs.map { r ->
            val tight = samples.filter { it.first >= r.from && it.first <= r.to }.minByOrNull { it.second }
            // Whole metres: the radius is an estimate from OSM geometry, decimals would claim a precision it does not have.
            CurveStretch(r.from, r.to, r.curve, Math.round(tight?.second ?: MAX_RADIUS_M).toDouble(), tight?.first ?: r.from)
        }
    }
}
