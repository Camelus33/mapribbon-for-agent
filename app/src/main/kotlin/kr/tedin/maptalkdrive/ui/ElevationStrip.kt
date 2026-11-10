package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.demo.WeaveFrame
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.geo.ElevationProfile
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** A measured outside temperature on the route: where (metres along it) and how warm. */
data class TempRef(val alongM: Double, val tempC: Double)

/**
 * The temperature the cold line starts from: the median outside temperature the passing cars reported on the earliest
 * segment that has one. It is measured on this road and recently; a parked car's own sensor reads the car park and
 * the sun on the bonnet. Null when no car reported a temperature.
 */
fun tempRef(b: Briefing): TempRef? = b.items
    .filter { it.anchor.type == AnchorType.SEGMENT }
    .mapNotNull { i -> i.state.metrics[Metric.OUTSIDE_TEMP_C]?.let { TempRef(i.pointM.toDouble(), it) } }
    .minByOrNull { it.alongM }

/**
 * The height of the road under the map, on the same kilometres. It shows why the places on the map are where they
 * are rather than adding places of its own:
 * - a long way down ([ElevationProfile.descents]) is drawn darker and thicker, as the map draws a sharper bend, with its
 *   length and average grade;
 * - when the cars reported a temperature, the height where the air is expected to reach [Metric.ICE_TEMP_C] (wet roads
 *   may freeze) is a dashed line, and the road above it is tinted;
 * - different segments are the same status bands as on the map; a tunnel is the same quiet band under the road as on
 *   the map, where the line is drawn straight between the portals rather than over the mountain.
 * The vertical scale is exaggerated (a 6 % road would be a flat line at true scale) and says by how much.
 */
@Composable
fun ElevationStrip(
    profile: ElevationProfile,
    briefing: Briefing,
    t: Tokens,
    w: Words,
    preview: State<Double?>,
    modifier: Modifier = Modifier,
    weave: State<WeaveFrame?>? = null,
) {
    val tm = rememberTextMeasurer()
    val descents = remember(profile) { profile.descents() }
    val ref = remember(briefing) { tempRef(briefing) }
    // Where the air is expected at the icing temperature, for the swell when the replay ties an ice ribbon.
    val coldAt = remember(profile, ref) { ref?.let { profile.heightForTemp(Metric.ICE_TEMP_C, it.alongM, it.tempC) } }
    val lo = floor(profile.lowestM / 100) * 100
    val hi = ceil(profile.highestM / 100) * 100
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val boxW = maxWidth
        val plotH = maxHeight - NoteHeight
        // The plot area and the two scales, shared by the strip, the preview overlay and the note under them.
        val p = with(density) {
            Plot(
                left = 120.dp.toPx(), right = boxW.toPx() - 56.dp.toPx(),
                top = 56.dp.toPx(), bottom = plotH.toPx() - 36.dp.toPx(),
                lengthM = profile.lengthM, lo = lo, hi = hi,
            )
        }
        Column {
        Box(Modifier.fillMaxWidth().height(plotH)) {
            Spacer(
                Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
                    val step = profile.stepM
                    val n = (profile.lengthM / step).toInt()
                    fun along(i: Int) = (i * step).coerceAtMost(profile.lengthM)
                    val line = Path().apply {
                        moveTo(p.x(0.0), p.y(profile.heightAt(0.0)))
                        for (i in 1..n + 1) { val a = along(i); lineTo(p.x(a), p.y(profile.heightAt(a))) }
                    }
                    val ground = Path().apply { addPath(line); lineTo(p.right, p.bottom); lineTo(p.left, p.bottom); close() }
                    fun piece(from: Double, to: Double) = Path().apply {
                        moveTo(p.x(from), p.y(profile.heightAt(from)))
                        var a = from + step
                        while (a < to) { lineTo(p.x(a), p.y(profile.heightAt(a))); a += step }
                        lineTo(p.x(to), p.y(profile.heightAt(to)))
                    }
                    val downs = descents.map { piece(it.fromM, it.toM) }
                    val downLabels = descents.map { d ->
                        tm.measure(w.str(R.string.elev_descent, "%.1f km".format(d.lengthM / 1000), (d.grade * 100).roundToInt()), Type.caption.copy(color = t.ink))
                    }
                    val bands = briefing.items.filter { it.anchor.type == AnchorType.SEGMENT && it.different }.map {
                        (if (it.state.deviation == Deviation.HIGH) t.high else t.some) to (it.anchor.alongM.toDouble() to it.anchor.endAlongM.toDouble())
                    }
                    val tunnels = profile.file.structures.filter { it.kind == "tunnel" }.map { piece(it.fromM.toDouble(), it.toM.toDouble()) }
                    val coldM = ref?.let { profile.heightForTemp(Metric.ICE_TEMP_C, it.alongM, it.tempC) }
                    val coldY = coldM?.takeIf { it < hi }?.let { p.y(maxOf(it, lo)) }
                    // Short words on the strip; where the temperature came from goes in the note under it.
                    val coldLabel = if (coldM != null && coldY != null) tm.measure(
                        w.str(R.string.elev_cold, "%.0f".format(Metric.ICE_TEMP_C), "%,d".format(coldM.roundToInt().coerceAtLeast(0))),
                        Type.caption.copy(color = t.ink2),
                    ) else null
                    val summitL = tm.measure("%,d m".format(profile.highestM.roundToInt()), Type.captionNum.copy(color = t.ink))
                    val yLabels = listOf(lo, hi).map { it to tm.measure("%,d m".format(it.roundToInt()), Type.captionNum.copy(color = t.muted)) }
                    val ticks = (0..profile.lengthM.toInt() / 1000 step 10).map { km -> km to tm.measure("$km", Type.captionNum.copy(color = t.muted)) }

                    onDrawBehind {
                        for ((color, r) in bands) drawRect(color.copy(alpha = StretchAlpha), Offset(p.x(r.first), p.top), Size(p.x(r.second) - p.x(r.first), p.bottom - p.top))
                        drawPath(ground, t.grid)
                        if (coldY != null) {
                            clipRect(top = 0f, bottom = coldY) { drawPath(ground, t.cold.copy(alpha = 0.35f)) }
                            drawLine(t.cold, Offset(p.left, coldY), Offset(p.right, coldY), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
                        }
                        for (tp in tunnels) drawPath(tp, t.tunnel, style = Stroke(TunnelBand.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
                        drawPath(line, t.ink2, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                        for (d in downs) drawPath(d, t.ink, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawLine(t.axis, Offset(p.left, p.bottom), Offset(p.right, p.bottom), 1.dp.toPx())

                        for ((h, l) in yLabels) drawText(l, topLeft = Offset(p.left - 16.dp.toPx() - l.size.width, p.y(h) - l.size.height / 2f))
                        for ((km, l) in ticks) {
                            val x = p.x(km * 1000.0)
                            drawLine(t.axis, Offset(x, p.bottom), Offset(x, p.bottom + 6.dp.toPx()), 1.dp.toPx())
                            drawText(l, topLeft = Offset(x - l.size.width / 2f, p.bottom + 8.dp.toPx()))
                        }
                        // The summit: a dot and its height just above it.
                        val sx = p.x(profile.highestAtM); val sy = p.y(profile.highestM)
                        drawCircle(t.ink, 5.dp.toPx(), Offset(sx, sy))
                        drawText(summitL, topLeft = Offset(sx - summitL.size.width / 2f, sy - 10.dp.toPx() - summitL.size.height))
                        // A descent's words start above its middle and run to the right, where the road is lower.
                        for ((d, l) in descents.zip(downLabels)) {
                            val mid = (d.fromM + d.toM) / 2
                            val x = p.x(mid).coerceAtMost(p.right - l.size.width)
                            drawText(l, topLeft = Offset(x + 12.dp.toPx(), p.y(profile.heightAt(mid)) - 8.dp.toPx() - l.size.height))
                        }
                        if (coldLabel != null && coldY != null) {
                            // Just above the dashed line, from the left: the road there is still below it, so the words
                            // sit in open sky and never cross the road.
                            val y = (coldY - 6.dp.toPx() - coldLabel.size.height).coerceAtLeast(p.top - 48.dp.toPx())
                            drawLine(t.cold, Offset(p.left + 4.dp.toPx(), y + coldLabel.size.height / 2f), Offset(p.left + 28.dp.toPx(), y + coldLabel.size.height / 2f), 3.dp.toPx())
                            drawText(coldLabel, topLeft = Offset(p.left + 36.dp.toPx(), y))
                        }
                    }
                },
            )
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                // The replay has tied an ice ribbon: the cold line swells once, so the eye finds the same place below.
                val f = weave?.value
                if (f != null && f.plan.metric == Metric.ICE_RISK_RATE && coldAt != null && coldAt < hi) {
                    val since = (f.t - f.plan.revealS) / 1.6
                    if (since in 0.0..1.0) {
                        val y = p.y(maxOf(coldAt, lo))
                        drawLine(t.cold.copy(alpha = sin(PI * since).toFloat()), Offset(p.left, y), Offset(p.right, y), 7.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                val a = preview.value ?: return@Canvas
                drawRect(t.surface.copy(alpha = 0.72f), Offset(p.left, p.top - 48.dp.toPx()), Size(p.x(a) - p.left, p.bottom - p.top + 48.dp.toPx()))
                val c = Offset(p.x(a), p.y(profile.heightAt(a)))
                drawCircle(t.positionRing, 12.dp.toPx(), c)
                drawCircle(t.position, 9.dp.toPx(), c)
            }
        }
        // How the cold line is estimated is in the README; its label already says the result. The source of the heights
        // is in the screen's one line of sources.
        Text(
            w.str(R.string.elev_note, p.exaggeration),
            style = Type.legal, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(NoteHeight).padding(top = 4.dp),
        )
        }
    }
}

private val NoteHeight = 28.dp

/** Distance along the route to x, height to y. */
private class Plot(val left: Float, val right: Float, val top: Float, val bottom: Float, val lengthM: Double, val lo: Double, val hi: Double) {
    fun x(alongM: Double): Float = left + ((alongM / lengthM).coerceIn(0.0, 1.0) * (right - left)).toFloat()
    fun y(heightM: Double): Float = bottom - (((heightM - lo) / (hi - lo)) * (bottom - top)).toFloat()
    /** How much taller than true the road is drawn: metres per pixel across over metres per pixel up. */
    val exaggeration: Int get() = ((lengthM / (right - left)) / ((hi - lo) / (bottom - top))).roundToInt().coerceAtLeast(1)
}
