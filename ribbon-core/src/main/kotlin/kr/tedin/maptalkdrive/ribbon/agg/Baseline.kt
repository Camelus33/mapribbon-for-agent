package kr.tedin.maptalkdrive.ribbon.agg

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * Usual values per anchor, per hour of day (all weekdays pooled: early fleets are too thin to split by weekday).
 * In the demo these come from a generated file; in service they come from four weeks of published ribbons.
 */
@Serializable
data class Baseline(
    val spec: String = "maptalk.baseline/1",
    val weeks: Int = 4,
    /** True for the generated demo baseline; the app labels anything derived from it as simulation. */
    val simulated: Boolean = false,
    /** anchorId -> hour "0".."23" -> metric -> value */
    val values: Map<String, Map<String, Map<String, Double>>> = emptyMap(),
) {
    fun get(anchorId: String, hour: Int, metric: String): Double? = values[anchorId]?.get(hour.toString())?.get(metric)

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        fun hourOf(epochMs: Long, zone: ZoneId = SEOUL): Int = Instant.ofEpochMilli(epochMs).atZone(zone).hour
    }
}
