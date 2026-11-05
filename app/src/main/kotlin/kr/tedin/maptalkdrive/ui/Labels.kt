package kr.tedin.maptalkdrive.ui

import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.Reason
import kotlin.math.roundToInt

/**
 * The words of the screens, one phrase per metric so the headline, the label, the table and the driving line never
 * describe the same thing three ways. Every number comes from the ribbon itself.
 */
object Words {
    /** Full issue phrase: headline, table, driving line. */
    fun issue(metric: String?): String = when (metric) {
        Metric.WIPER_TURNED_ON_RATE -> "와이퍼 켠 차 많음"
        Metric.FOG_TURNED_ON_RATE -> "안개등 켠 차 많음"
        Metric.LATE_LANE_CHANGE_RATE -> "늦은 끼어들기 많음"
        Metric.HARD_BRAKE_RATE -> "급제동 많음"
        Metric.ENTRY_DECEL_KMH -> "진입 급감속"
        Metric.STOP_RATE -> "멈춘 차 많음"
        else -> "평소와 다름"
    }

    /** Short issue word for a strip label. */
    fun issueShort(metric: String?): String = when (metric) {
        Metric.WIPER_TURNED_ON_RATE -> "와이퍼"
        Metric.FOG_TURNED_ON_RATE -> "안개등"
        Metric.LATE_LANE_CHANGE_RATE -> "늦은 끼어들기"
        Metric.HARD_BRAKE_RATE -> "급제동"
        Metric.ENTRY_DECEL_KMH -> "급감속"
        Metric.STOP_RATE -> "정체"
        else -> "평소와 다름"
    }

    /** The facility as a driver names it. Tunnel issues are about the exit; virtual cameras are just cameras here. */
    fun place(item: BriefingItem): String = when (item.anchor.type) {
        AnchorType.TUNNEL -> "${item.anchor.name} 출구"
        AnchorType.CAMERA -> "단속카메라"
        else -> item.anchor.name
    }

    fun kind(type: AnchorType): String = when (type) {
        AnchorType.TUNNEL -> "터널"
        AnchorType.JUNCTION -> "분기점"
        AnchorType.CAMERA -> "단속카메라"
        AnchorType.TOLL -> "요금소"
    }

    fun level(d: Deviation): String = when (d) {
        Deviation.HIGH -> "평소와 크게 다름"
        Deviation.SOME -> "평소와 조금 다름"
        Deviation.NONE -> "평소대로"
    }

    fun top(item: BriefingItem): Reason? = item.state.reasons.firstOrNull()

    /** "9대 중 6대": with eight or nine cars a percentage overstates precision, so say the count. */
    fun thisTime(item: BriefingItem): String {
        val r = top(item) ?: return "${item.state.n}대"
        return if (r.metric.endsWith("Kmh")) "평균 ${r.value.roundToInt()} km/h" else "${item.state.n}대 중 ${(r.value * item.state.n).roundToInt()}대"
    }

    fun usual(item: BriefingItem): String {
        val b = top(item)?.baseline ?: return "—"
        return if (top(item)!!.metric.endsWith("Kmh")) "${b.roundToInt()} km/h" else "${(b * 100).roundToInt()}%"
    }

    /** Position along the route, always one decimal. */
    fun at(m: Number): String = "%.1f km".format(m.toDouble() / 1000)

    /** Distance ahead for the driving line: whole km from 10 km, one decimal below, metres under 1 km, "곧" under 150 m. */
    fun ahead(m: Double): Pair<String, String> = when {
        m < 150 -> "곧" to ""
        m < 1000 -> "${((m / 100).roundToInt() * 100).coerceAtLeast(100)}" to "m 앞"
        m < 10_000 -> "%.1f".format(m / 1000) to "km 앞"
        else -> "${(m / 1000).roundToInt()}" to "km 앞"
    }

    /** Degrees without a negative zero. */
    fun degrees(v: Double?): String = v?.let { "%.1f°".format(if (kotlin.math.abs(it) < 0.05) 0.0 else it) } ?: "—"
}
