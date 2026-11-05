package kr.tedin.maptalkdrive.ui

import androidx.compose.ui.graphics.Color
import kr.tedin.maptalkdrive.ribbon.agg.Gist
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kotlin.math.roundToInt

/** Colours of the in-car screens (dark, matching the proposal mock-ups). */
object Palette {
    val bg = Color(0xFF0F1513)
    val panel = Color(0xFF161D1A)
    val ink = Color(0xFFE8EFEC)
    val dim = Color(0xFF8FA19A)
    val faint = Color(0xFF6F7D77)
    val track = Color(0xFF3A4541)
    val red = Color(0xFFEF6A5B)
    val redBg = Color(0xFF2A1715)
    val yellow = Color(0xFFE6B34A)
    val yellowBg = Color(0xFF2B2410)
    val teal = Color(0xFF5CC3E0)
    val sim = Color(0xFFB98CF0)
}

fun glyph(type: AnchorType) = when (type) {
    AnchorType.TUNNEL -> "터"
    AnchorType.JUNCTION -> "분"
    AnchorType.TOLL -> "톨"
    AnchorType.CAMERA -> "카"
}

fun colorOf(item: BriefingItem): Color = when {
    item.state.status == RibbonStatus.NOT_ENOUGH -> Palette.faint
    item.state.deviation == Deviation.HIGH -> Palette.red
    item.state.deviation == Deviation.SOME -> Palette.yellow
    else -> Palette.dim
}

fun km(m: Number) = "%.1f km".format(m.toDouble() / 1000)

/** Where on the strip an anchor sits: the same point the driving view counts down to (a tunnel's exit). */
fun stripAlong(item: BriefingItem): Int = item.pointM

/** Short title for a label or the driving line, from the ribbon's top reason. Numbers only from the ribbon. */
fun titleOf(item: BriefingItem): String {
    val name = item.anchor.name
    return when (item.state.reasons.firstOrNull()?.metric) {
        Metric.WIPER_TURNED_ON_RATE -> "$name 출구 젖음 의심"
        Metric.FOG_TURNED_ON_RATE -> "$name 출구 안개 의심"
        Metric.LATE_LANE_CHANGE_RATE -> "$name 직전 끼어들기"
        Metric.HARD_BRAKE_RATE -> "$name 급제동 잦음"
        Metric.ENTRY_DECEL_KMH -> "$name 진입 급감속"
        Metric.STOP_RATE -> "$name 정체"
        else -> name
    }
}

fun detailOf(item: BriefingItem): String {
    val r = item.state.reasons.firstOrNull() ?: return item.state.gist
    val v = Gist.fmt(r.metric, r.value)
    val usual = r.baseline?.let { " (평소 ${Gist.fmt(r.metric, it)})" } ?: ""
    val what = when (r.metric) {
        Metric.WIPER_TURNED_ON_RATE -> "${item.state.n}대 중 $v 와이퍼"
        Metric.FOG_TURNED_ON_RATE -> "${item.state.n}대 중 $v 안개등"
        Metric.LATE_LANE_CHANGE_RATE -> "${item.state.n}대 중 $v 늦은 차로 변경"
        Metric.HARD_BRAKE_RATE -> "${item.state.n}대 중 $v 급제동"
        else -> v
    }
    return "$what$usual · ${km(stripAlong(item))}"
}

fun headlineOf(item: BriefingItem): String {
    val r = item.state.reasons.first()
    val pct = (r.value * 100).roundToInt()
    val at = "%.0f km".format(stripAlong(item) / 1000.0)
    return when (r.metric) {
        Metric.WIPER_TURNED_ON_RATE -> "$at ${item.anchor.name} 출구, 와이퍼 켠 차 $pct%"
        Metric.FOG_TURNED_ON_RATE -> "$at ${item.anchor.name} 출구, 안개등 켠 차 $pct%"
        Metric.LATE_LANE_CHANGE_RATE -> "$at ${item.anchor.name} 직전, 늦은 끼어들기 $pct%"
        Metric.HARD_BRAKE_RATE -> "$at ${item.anchor.name}, 급제동 $pct%"
        else -> "$at ${titleOf(item)}"
    }
}
