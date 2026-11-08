package kr.tedin.maptalkdrive.ribbon.geo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A bridge or tunnel the profile crosses on a straight line instead of following the ground. */
@Serializable
data class Structure(val kind: String, val fromM: Int, val toM: Int, val name: String = "")

/**
 * `maptalk.elevation/1`: the height of the road every [stepM] metres from the start of the route, in whole metres.
 * Built from a terrain model by `tools/anchors/build-elevation.mjs`, with bridges and tunnels already bridged.
 */
@Serializable
data class ElevationFile(
    val spec: String,
    val routeId: String,
    val stepM: Int,
    val lengthM: Int,
    val source: String,
    val attribution: String,
    val structures: List<Structure> = emptyList(),
    val m: List<Int>,
)

/** A long way down: where it starts and ends, how far it drops and its average grade (0.065 = 6.5 %). */
@Serializable
data class Descent(val fromM: Double, val toM: Double, val dropM: Double) {
    val lengthM: Double get() = toM - fromM
    val grade: Double get() = dropM / lengthM
}

/** Where the air is predicted to be at or below a temperature, from one measured temperature and the heights. */
@Serializable
data class ColdStretch(val fromM: Double, val toM: Double, val lowestC: Double)

class ElevationProfile(val file: ElevationFile) {
    init {
        require(file.stepM > 0 && file.m.size >= 2) { "elevation profile needs a step and at least 2 heights" }
    }

    private val h = file.m.map { it.toDouble() }
    val stepM: Double get() = file.stepM.toDouble()
    val lengthM: Double get() = file.lengthM.toDouble()
    val lowestM: Double get() = h.min()
    val highestM: Double get() = h.max()
    val highestAtM: Double get() = h.indexOf(highestM) * stepM

    /** Height of the road `alongM` metres from the start, linear between samples, clamped to the route. */
    fun heightAt(alongM: Double): Double {
        val f = (alongM / stepM).coerceIn(0.0, (h.size - 1).toDouble())
        val i = minOf(h.size - 2, f.toInt())
        return h[i] + (f - i) * (h[i + 1] - h[i])
    }

    /** Grade at `alongM` over [GRADE_SPAN_M]: negative going down. */
    fun gradeAt(alongM: Double): Double {
        val a = (alongM - GRADE_SPAN_M / 2).coerceAtLeast(0.0)
        val b = (alongM + GRADE_SPAN_M / 2).coerceAtMost(lengthM)
        return if (b <= a) 0.0 else (heightAt(b) - heightAt(a)) / (b - a)
    }

    /**
     * Long ways down: stretches where the road keeps falling at [STEEP_GRADE] or more, with level or rising bits under
     * [DESCENT_GAP_M] counted in, that are at least [DESCENT_MIN_M] long and fall [DESCENT_MIN_GRADE] or more on
     * average. A descent this long is where brakes heat and speed builds; a short dip is not.
     */
    fun descents(): List<Descent> {
        val runs = mutableListOf<Pair<Double, Double>>()
        var start: Double? = null
        var a = 0.0
        while (a <= lengthM) {
            val down = gradeAt(a) <= -STEEP_GRADE
            if (down && start == null) start = a
            if (!down && start != null) { runs += start to a; start = null }
            a += stepM
        }
        if (start != null) runs += start to lengthM
        val merged = mutableListOf<Pair<Double, Double>>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.second < DESCENT_GAP_M && heightAt(r.first) <= heightAt(last.second)) merged[merged.size - 1] = last.first to r.second
            else merged += r
        }
        return merged.map { (from, to) -> Descent(from, to, heightAt(from) - heightAt(to)) }
            .filter { it.lengthM >= DESCENT_MIN_M && it.grade >= DESCENT_MIN_GRADE }
    }

    /** Predicted air temperature at `alongM`, given `refTempC` measured at `refAlongM`, by the standard lapse rate. */
    fun tempAt(alongM: Double, refAlongM: Double, refTempC: Double): Double =
        refTempC - LAPSE_C_PER_KM * (heightAt(alongM) - heightAt(refAlongM)) / 1000

    /** The height at which the predicted temperature reaches `targetC`. */
    fun heightForTemp(targetC: Double, refAlongM: Double, refTempC: Double): Double =
        heightAt(refAlongM) + (refTempC - targetC) / LAPSE_C_PER_KM * 1000

    /** Stretches at least [COLD_MIN_M] long where the predicted temperature is at or below `thresholdC`. */
    fun coldStretches(refAlongM: Double, refTempC: Double, thresholdC: Double): List<ColdStretch> {
        val out = mutableListOf<ColdStretch>()
        var from: Double? = null
        var lowest = Double.MAX_VALUE
        var a = 0.0
        while (a <= lengthM + stepM / 2) {
            val at = a.coerceAtMost(lengthM)
            val t = tempAt(at, refAlongM, refTempC)
            val cold = t <= thresholdC && a <= lengthM
            if (cold) { if (from == null) { from = at; lowest = t } else lowest = minOf(lowest, t) }
            if (!cold && from != null) { if (at - from >= COLD_MIN_M) out += ColdStretch(from, at, lowest); from = null }
            a += stepM
        }
        return out
    }

    companion object {
        const val SPEC = "maptalk.elevation/1"
        /** Air cools by about this much per km of height (the standard atmosphere). */
        const val LAPSE_C_PER_KM = 6.5
        const val GRADE_SPAN_M = 250.0
        /** A grade a driver feels as going down: 3 %. */
        const val STEEP_GRADE = 0.03
        const val DESCENT_GAP_M = 400.0
        const val DESCENT_MIN_M = 2_000.0
        const val DESCENT_MIN_GRADE = 0.04
        const val COLD_MIN_M = 200.0

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): ElevationProfile {
            val f = json.decodeFromString(ElevationFile.serializer(), text)
            require(f.spec == SPEC) { "expected $SPEC, got ${f.spec}" }
            return ElevationProfile(f)
        }
    }
}
