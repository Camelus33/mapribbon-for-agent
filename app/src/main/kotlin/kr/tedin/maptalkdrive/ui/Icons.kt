package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kotlin.math.cos
import kotlin.math.sin

/**
 * What was different, as a pictogram. Wipers and fog lights use the shapes of the ISO 2575 dashboard symbols every
 * driver already knows; braking, late merges, stops and slowdowns have no dashboard symbol and are drawn plainly.
 * All are line drawings in one ink inside a square of side `s` centred on `c`.
 */
fun DrawScope.issueIcon(metric: String?, c: Offset, s: Float, ink: Color) {
    val w = s * 0.085f
    val stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        drawLine(ink, Offset(c.x + s * x0, c.y + s * y0), Offset(c.x + s * x1, c.y + s * y1), w, StrokeCap.Round)
    when (metric) {
        Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE -> {
            // Windscreen with a wiper arm swept up to the left from its pivot.
            val glass = Path().apply {
                moveTo(c.x - s * 0.46f, c.y - s * 0.2f)
                quadraticBezierTo(c.x, c.y - s * 0.44f, c.x + s * 0.46f, c.y - s * 0.2f)
                lineTo(c.x + s * 0.36f, c.y + s * 0.3f)
                quadraticBezierTo(c.x, c.y + s * 0.2f, c.x - s * 0.36f, c.y + s * 0.3f)
                close()
            }
            drawPath(glass, ink, style = stroke)
            line(0f, 0.2f, -0.24f, -0.2f)
            drawCircle(ink, w * 0.9f, Offset(c.x, c.y + s * 0.2f))
        }
        Metric.FOG_TURNED_ON_RATE -> {
            // Front fog lamp: a lamp facing left, three beams, a wavy line through the beams.
            val lamp = Path().apply {
                moveTo(c.x + s * 0.06f, c.y - s * 0.3f)
                cubicTo(c.x + s * 0.52f, c.y - s * 0.3f, c.x + s * 0.52f, c.y + s * 0.3f, c.x + s * 0.06f, c.y + s * 0.3f)
                close()
            }
            drawPath(lamp, ink, style = stroke)
            for (y in listOf(-0.18f, 0f, 0.18f)) line(-0.46f, y, -0.06f, y)
            val wave = Path().apply {
                moveTo(c.x - s * 0.26f, c.y - s * 0.36f)
                quadraticBezierTo(c.x - s * 0.16f, c.y - s * 0.18f, c.x - s * 0.26f, c.y)
                quadraticBezierTo(c.x - s * 0.36f, c.y + s * 0.18f, c.x - s * 0.26f, c.y + s * 0.36f)
            }
            drawPath(wave, ink, style = stroke)
        }
        Metric.HARD_BRAKE_RATE -> {
            // The brake disc of the dashboard brake symbol, pressed hard: a filled disc inside the bracket arcs.
            drawCircle(ink, s * 0.24f, c, style = stroke)
            drawCircle(ink, s * 0.11f, c)
            drawArc(ink, 125f, 110f, false, Offset(c.x - s * 0.44f, c.y - s * 0.36f), Size(s * 0.72f, s * 0.72f), style = stroke)
            drawArc(ink, -55f, 110f, false, Offset(c.x - s * 0.28f, c.y - s * 0.36f), Size(s * 0.72f, s * 0.72f), style = stroke)
        }
        Metric.LATE_LANE_CHANGE_RATE -> {
            // Two lanes; an arrow runs up the right lane and cuts across the lane line at the last moment.
            drawLine(ink, Offset(c.x, c.y - s * 0.46f), Offset(c.x, c.y + s * 0.46f), w * 0.8f, StrokeCap.Butt,
                PathEffect.dashPathEffect(floatArrayOf(s * 0.12f, s * 0.1f)))
            val p = Path().apply {
                moveTo(c.x + s * 0.24f, c.y + s * 0.44f)
                lineTo(c.x + s * 0.24f, c.y - s * 0.02f)
                lineTo(c.x - s * 0.22f, c.y - s * 0.34f)
            }
            drawPath(p, ink, style = stroke)
            arrowHead(Offset(c.x - s * 0.22f, c.y - s * 0.34f), Offset(c.x + s * 0.24f, c.y - s * 0.02f), s * 0.2f, ink, w)
        }
        Metric.STOP_RATE -> {
            // A queue: three cars nose to tail.
            for (i in 0 until 3) {
                val y = c.y - s * 0.42f + i * s * 0.3f
                drawRoundRect(ink, Offset(c.x - s * 0.17f, y), Size(s * 0.34f, s * 0.22f), CornerRadius(s * 0.06f), style = stroke)
            }
        }
        Metric.ENTRY_DECEL_KMH, Metric.SPEED_AT_KMH -> {
            // Speedometer with the needle dropped to the low end.
            drawArc(ink, 150f, 240f, false, Offset(c.x - s * 0.4f, c.y - s * 0.36f), Size(s * 0.8f, s * 0.8f), style = stroke)
            val a = Math.toRadians(160.0)
            val tip = Offset(c.x + (s * 0.3f * cos(a)).toFloat(), c.y + s * 0.04f + (s * 0.3f * sin(a)).toFloat())
            drawLine(ink, Offset(c.x, c.y + s * 0.04f), tip, w, StrokeCap.Round)
            drawCircle(ink, w, Offset(c.x, c.y + s * 0.04f))
        }
        else -> {
            // Unknown metric: a plain "not equal" sign.
            line(-0.3f, -0.1f, 0.3f, -0.1f); line(-0.3f, 0.12f, 0.3f, 0.12f); line(0.14f, -0.32f, -0.14f, 0.34f)
        }
    }
}

private fun DrawScope.arrowHead(tip: Offset, from: Offset, len: Float, ink: Color, w: Float) {
    val d = tip - from
    val n = d / d.getDistance()
    val side = Offset(-n.y, n.x)
    drawLine(ink, tip, tip - n * len + side * len * 0.6f, w, StrokeCap.Round)
    drawLine(ink, tip, tip - n * len - side * len * 0.6f, w, StrokeCap.Round)
}

/** A clock: how recent the records are. */
fun DrawScope.clockIcon(c: Offset, s: Float, ink: Color) {
    val w = s * 0.09f
    drawCircle(ink, s * 0.4f, c, style = Stroke(w))
    drawLine(ink, c, Offset(c.x, c.y - s * 0.26f), w, StrokeCap.Round)
    drawLine(ink, c, Offset(c.x + s * 0.18f, c.y + s * 0.08f), w, StrokeCap.Round)
}

/** Play (preview run) and stop. */
fun DrawScope.playIcon(c: Offset, s: Float, ink: Color) {
    drawPath(Path().apply {
        moveTo(c.x - s * 0.28f, c.y - s * 0.36f); lineTo(c.x + s * 0.38f, c.y); lineTo(c.x - s * 0.28f, c.y + s * 0.36f); close()
    }, ink)
}

fun DrawScope.stopIcon(c: Offset, s: Float, ink: Color) =
    drawRoundRect(ink, Offset(c.x - s * 0.3f, c.y - s * 0.3f), Size(s * 0.6f, s * 0.6f), CornerRadius(s * 0.06f))

/** Facility pictogram or issue icon as a composable, for rows and the headline. */
@Composable
fun IconBox(size: Dp, draw: DrawScope.(Offset, Float) -> Unit) {
    Canvas(Modifier.size(size)) { draw(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension) }
}

@Composable
fun IssueIcon(metric: String?, size: Dp, ink: Color) = IconBox(size) { c, s -> issueIcon(metric, c, s, ink) }

@Composable
fun FacilityIcon(type: AnchorType, size: Dp, ink: Color) = IconBox(size) { c, s -> pictogram(type, c, s, ink) }
