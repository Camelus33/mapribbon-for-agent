package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus

/**
 * The route laid out as one line, start to destination. Each facility stands on it as a small sign; only the ones
 * that are different from usual get colour and a label. Grey means usual, dashed means not enough cars yet.
 */
@Composable
fun RouteStrip(briefing: Briefing, lengthM: Double, myAlongM: Double?, modifier: Modifier = Modifier, labels: Boolean = true) {
    val tm = rememberTextMeasurer()
    // While driving the strip carries no labels: one glance, one line of text above it.
    Canvas(modifier.fillMaxWidth().height(if (labels) 300.dp else 130.dp)) {
        val left = 60f
        val right = size.width - 60f
        val baseY = if (labels) 190f else 30f
        fun x(along: Number) = left + (along.toFloat() / lengthM.toFloat()) * (right - left)

        // Road and tunnel spans.
        drawRoundRect(Palette.track, Offset(left, baseY - 5), Size(right - left, 10f), CornerRadius(5f))
        for (item in briefing.items.filter { it.anchor.type == AnchorType.TUNNEL }) {
            drawRect(Color(0xFF26302C), Offset(x(item.anchor.alongM), baseY - 9), Size(x(item.anchor.endAlongM) - x(item.anchor.alongM), 18f))
        }
        if (myAlongM != null) {
            drawRect(Palette.teal.copy(alpha = 0.35f), Offset(left, baseY - 5), Size(x(myAlongM) - left, 10f))
        }
        drawCircle(Palette.teal, 12f, Offset(left, baseY))
        drawCircle(Palette.teal, 14f, Offset(right, baseY), style = Stroke(5f))

        // Signs. Usual ones first so coloured ones are drawn on top.
        val ordered = briefing.items.sortedBy { if (it.different) 1 else 0 }
        for (item in ordered) {
            val cx = x(stripAlong(item))
            val c = colorOf(item)
            val big = item.state.deviation == Deviation.HIGH
            val s = if (big) 40f else 30f
            val tl = Offset(cx - s / 2, baseY - s / 2)
            val fill = when (item.state.deviation) { Deviation.HIGH -> Palette.redBg; Deviation.SOME -> Palette.yellowBg; else -> Palette.bg }
            drawRoundRect(fill, tl, Size(s, s), CornerRadius(6f))
            val dashed = item.state.status == RibbonStatus.NOT_ENOUGH
            drawRoundRect(c, tl, Size(s, s), CornerRadius(6f), style = Stroke(if (big) 4f else 2.5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) else null))
            centered(tm, glyph(item.anchor.type), cx, baseY - (if (big) 12f else 9f), TextStyle(color = c, fontSize = if (big) 17.sp else 13.sp, fontWeight = FontWeight.Bold))
        }

        // Labels for the different ones, spread by their measured width so they never overlap or leave the screen.
        val labeled = if (labels) briefing.labeled else emptyList()
        val titles = labeled.map { tm.measure(titleOf(it), TextStyle(color = colorOf(it), fontSize = 22.sp, fontWeight = FontWeight.Bold)) }
        val details = labeled.map { tm.measure(detailOf(it), TextStyle(color = Palette.dim, fontSize = 16.sp)) }
        val widest = (titles + details).maxOfOrNull { it.size.width.toFloat() } ?: 0f
        val slots = spread(labeled.map { x(stripAlong(it)) }, minGap = widest + 32f, lo = widest / 2 + 8f, hi = size.width - widest / 2 - 8f)
        labeled.forEachIndexed { i, item ->
            val c = colorOf(item)
            val ax = x(stripAlong(item))
            val lx = slots[i]
            val path = Path().apply { moveTo(lx, 112f); lineTo(lx, 132f); lineTo(ax, 132f); lineTo(ax, baseY - 24f) }
            drawPath(path, c, style = Stroke(2.5f))
            drawText(titles[i], topLeft = Offset(lx - titles[i].size.width / 2f, 40f))
            drawText(details[i], topLeft = Offset(lx - details[i].size.width / 2f, 76f))
        }

        // Kilometre ticks.
        val lengthKm = lengthM / 1000
        for (k in 0..lengthKm.toInt() step 5) centered(tm, "$k", x(k * 1000), baseY + 40f, TextStyle(color = Palette.faint, fontSize = 14.sp))
        centered(tm, "%.1f km".format(lengthKm), right, baseY + 40f, TextStyle(color = Palette.faint, fontSize = 14.sp))
        centered(tm, "출발", left, baseY + 70f, TextStyle(color = Palette.teal, fontSize = 15.sp))
        centered(tm, "도착", right, baseY + 70f, TextStyle(color = Palette.teal, fontSize = 15.sp))
    }
}

private fun DrawScope.centered(tm: TextMeasurer, text: String, cx: Float, top: Float, style: TextStyle) {
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, top))
}

/** Keeps label centres at least `minGap` apart, as close to their anchors as possible, inside [lo, hi]. */
internal fun spread(wanted: List<Float>, minGap: Float, lo: Float, hi: Float): List<Float> {
    if (wanted.isEmpty()) return wanted
    val order = wanted.indices.sortedBy { wanted[it] }
    val xs = FloatArray(wanted.size)
    var prev = Float.NEGATIVE_INFINITY
    for (i in order) { xs[i] = maxOf(wanted[i].coerceIn(lo, hi), prev + minGap); prev = xs[i] }
    var next = Float.POSITIVE_INFINITY
    for (i in order.reversed()) { xs[i] = minOf(xs[i], next - minGap, hi); next = xs[i] }
    return xs.toList()
}
