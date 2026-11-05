package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation

/**
 * Status is encoded three ways at once: shape (circle = somewhat, triangle with "!" = clearly), size, and a word next to
 * it. Marks are separated from each other and from the track by a ring in the surface colour, never by an outline.
 */
fun DrawScope.statusMark(center: Offset, d: Deviation, sizePx: Float, t: Tokens, ringPx: Float, surface: Color, exclaim: Boolean = true) {
    when (d) {
        Deviation.HIGH -> {
            drawPath(triangle(center, sizePx + ringPx * 2.4f), surface)
            drawPath(triangle(center, sizePx), t.high)
            if (exclaim) exclamation(center, sizePx, t.onStatus)
        }
        Deviation.SOME -> {
            drawCircle(surface, sizePx / 2 + ringPx, center)
            drawCircle(t.some, sizePx / 2, center)
        }
        Deviation.NONE -> Unit
    }
}

/** Not enough cars yet: a hollow circle. It must never look like "usual". */
fun DrawScope.notEnoughMark(center: Offset, sizePx: Float, strokePx: Float, t: Tokens, surface: Color) {
    drawCircle(surface, sizePx / 2 + strokePx, center)
    drawCircle(t.muted, sizePx / 2 - strokePx / 2, center, style = Stroke(strokePx))
}

/**
 * A hairpin on the road: a disc in the surface colour with an ink ring and the hairpin icon. Ink, not a status colour:
 * it is about the road, not about what cars did there.
 */
fun DrawScope.hairpinMark(center: Offset, sizePx: Float, t: Tokens, ringPx: Float) {
    drawCircle(t.surface, sizePx / 2, center)
    drawCircle(t.ink, sizePx / 2 - ringPx / 2, center, style = Stroke(ringPx))
    hairpinIcon(center, sizePx * 0.78f, t.ink)
}

/** An upward triangle with softened corners, centred on `c`, `s` wide. */
private fun triangle(c: Offset, s: Float): Path {
    val h = s * 0.866f
    val top = Offset(c.x, c.y - h * 0.62f)
    val left = Offset(c.x - s / 2, c.y + h * 0.38f)
    val right = Offset(c.x + s / 2, c.y + h * 0.38f)
    return Path().apply { moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(left.x, left.y); close() }
}

private fun DrawScope.exclamation(c: Offset, s: Float, ink: Color) {
    val w = s * 0.11f
    drawRoundRect(ink, Offset(c.x - w / 2, c.y - s * 0.22f), Size(w, s * 0.28f), CornerRadius(w / 2))
    drawCircle(ink, w * 0.62f, Offset(c.x, c.y + s * 0.17f))
}

/** Chevron pointing in the direction of travel, with a surface ring so it reads over marks and the track. */
fun DrawScope.chevron(center: Offset, sizePx: Float, t: Tokens, ringPx: Float) {
    fun path(s: Float) = Path().apply {
        moveTo(center.x - s * 0.45f, center.y - s * 0.5f)
        lineTo(center.x + s * 0.5f, center.y)
        lineTo(center.x - s * 0.45f, center.y + s * 0.5f)
        lineTo(center.x - s * 0.2f, center.y)
        close()
    }
    drawPath(path(sizePx + ringPx * 2.5f), t.positionRing)
    drawPath(path(sizePx), t.position)
}

/** Simple pictograms for the driving hero, drawn in `ink` inside a box of side `s` centred on `c`. */
fun DrawScope.pictogram(type: AnchorType, c: Offset, s: Float, ink: Color) {
    val w = s * 0.09f
    val stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (type) {
        AnchorType.TUNNEL -> {
            val r = Rect(c.x - s * 0.4f, c.y - s * 0.3f, c.x + s * 0.4f, c.y + s * 0.5f)
            drawArc(ink, 180f, 180f, false, r.topLeft, Size(r.width, r.height), style = stroke)
            drawLine(ink, Offset(r.left, c.y + s * 0.1f), Offset(r.left, c.y + s * 0.38f), w, StrokeCap.Round)
            drawLine(ink, Offset(r.right, c.y + s * 0.1f), Offset(r.right, c.y + s * 0.38f), w, StrokeCap.Round)
            drawLine(ink, Offset(c.x - s * 0.48f, c.y + s * 0.38f), Offset(c.x + s * 0.48f, c.y + s * 0.38f), w, StrokeCap.Round)
        }
        AnchorType.JUNCTION -> {
            val stem = Offset(c.x, c.y + s * 0.42f)
            val fork = Offset(c.x, c.y)
            drawLine(ink, stem, fork, w, StrokeCap.Round)
            drawLine(ink, fork, Offset(c.x - s * 0.3f, c.y - s * 0.42f), w, StrokeCap.Round)
            drawLine(ink, fork, Offset(c.x + s * 0.3f, c.y - s * 0.42f), w, StrokeCap.Round)
        }
        AnchorType.CAMERA -> {
            drawRoundRect(ink, Offset(c.x - s * 0.4f, c.y - s * 0.22f), Size(s * 0.8f, s * 0.5f), CornerRadius(s * 0.08f), style = stroke)
            drawCircle(ink, s * 0.13f, Offset(c.x, c.y + s * 0.03f), style = stroke)
            drawLine(ink, Offset(c.x - s * 0.2f, c.y - s * 0.32f), Offset(c.x + s * 0.05f, c.y - s * 0.32f), w, StrokeCap.Round)
        }
        AnchorType.TOLL -> {
            drawLine(ink, Offset(c.x - s * 0.4f, c.y + s * 0.42f), Offset(c.x - s * 0.4f, c.y - s * 0.3f), w, StrokeCap.Round)
            drawLine(ink, Offset(c.x - s * 0.4f, c.y - s * 0.1f), Offset(c.x + s * 0.45f, c.y - s * 0.1f), w, StrokeCap.Round)
        }
        AnchorType.SEGMENT -> {
            // A stretch of open road in perspective: two edges narrowing to the horizon, a dashed centre line.
            drawLine(ink, Offset(c.x - s * 0.42f, c.y + s * 0.42f), Offset(c.x - s * 0.12f, c.y - s * 0.42f), w, StrokeCap.Round)
            drawLine(ink, Offset(c.x + s * 0.42f, c.y + s * 0.42f), Offset(c.x + s * 0.12f, c.y - s * 0.42f), w, StrokeCap.Round)
            for ((y0, y1) in listOf(0.42f to 0.2f, 0.04f to -0.12f, -0.26f to -0.36f)) {
                drawLine(ink, Offset(c.x, c.y + s * y0), Offset(c.x, c.y + s * y1), w * 0.8f, StrokeCap.Round)
            }
        }
    }
}

/** A status mark as a standalone composable (headline, table rows, legend). */
@Composable
fun StatusGlyph(d: Deviation, size: Dp, t: Tokens, surface: Color, notEnough: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2 + if (d == Deviation.HIGH && !notEnough) this.size.height * 0.05f else 0f)
        val s = this.size.minDimension * 0.82f
        when {
            notEnough -> notEnoughMark(c, s * 0.7f, this.size.minDimension * 0.08f, t, surface)
            d == Deviation.NONE -> drawRect(t.muted, Offset(c.x - 1.5f, c.y - s * 0.35f), Size(3f, s * 0.7f))
            else -> statusMark(c, d, if (d == Deviation.HIGH) s else s * 0.72f, t, 0f, surface)
        }
    }
}
