package kr.tedin.maptalkdrive.ribbon.agg

import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.Reason
import kotlin.math.roundToInt

/**
 * The one-line summary of a ribbon. Every number in it comes from the ribbon's own metrics or baseline; the words
 * come from fixed templates. Nothing is generated, so nothing can be invented.
 */
object Gist {
    fun windowLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes}분"

    fun notEnough(name: String, widestMin: Int, n: Int): String = "$name, 기록 부족 (최근 ${windowLabel(widestMin)} ${n}대)."

    fun of(name: String, minutes: Int, n: Int, top: Reason?): String {
        val w = "최근 ${windowLabel(minutes)} ${n}대"
        if (top == null) return "$name, $w, 평소와 비슷함."
        val usual = top.baseline?.let { " (평소 ${fmt(top.metric, it)})" } ?: ""
        val v = fmt(top.metric, top.value)
        return when (top.metric) {
            Metric.WIPER_TURNED_ON_RATE -> "$name 출구, $w 중 ${v}가 출구에서 와이퍼를 켬$usual."
            Metric.FOG_TURNED_ON_RATE -> "$name 출구, $w 중 ${v}가 출구에서 안개등을 켬$usual."
            Metric.LATE_LANE_CHANGE_RATE -> "$name 300m 전, $w 중 ${v}가 늦게 차로를 바꿈$usual."
            Metric.HARD_BRAKE_RATE -> "$name 구간, $w 중 ${v}가 급제동$usual."
            Metric.STOP_RATE -> "$name, $w 중 ${v}가 멈춤$usual."
            Metric.WIPER_ON_RATE -> "$name, $w 중 ${v}가 와이퍼 사용$usual."
            Metric.FOG_ON_RATE -> "$name, $w 중 ${v}가 안개등 사용$usual."
            Metric.WIPER_FAST_RATE -> "$name, $w 중 ${v}가 와이퍼를 빠르게 씀$usual."
            Metric.REAR_FOG_RATE -> "$name, $w 중 ${v}가 뒤 안개등을 켬$usual."
            Metric.DAY_LOW_BEAM_RATE -> "$name, $w 중 ${v}가 낮에 전조등을 켬$usual."
            Metric.ICE_RISK_RATE -> "$name, $w 중 ${v}가 얼기 쉬운 기온에서 와이퍼나 안개등을 씀$usual."
            Metric.ENTRY_DECEL_KMH -> "$name 진입 전, $w 평균 $v 감속$usual."
            else -> "$name, $w, ${top.metric} $v$usual."
        }
    }

    fun fmt(metric: String, value: Double): String =
        if (metric.endsWith("Kmh")) "${value.roundToInt()}km/h" else "${(value * 100).roundToInt()}%"
}
