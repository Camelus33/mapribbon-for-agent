package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.geo.CurveClass
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The real road shape, turned and scaled so the start sits bottom-left and the destination top-right of `box`.
 * Turning and one scale for both axes keep the shape: a bend on screen is the bend on the road.
 */
class RouteGeometry(route: RouteIndex, box: Rect) {
    private val pts: List<Offset>
    private val cum = route.cum

    init {
        val lat0 = route.path.map { it.lat }.average()
        val lon0 = route.path.map { it.lon }.average()
        val k = cos(lat0 * PI / 180)
        // Local metres, screen orientation (y grows downward).
        val local = route.path.map { Offset(((it.lon - lon0) * k * 111_320).toFloat(), (-(it.lat - lat0) * 110_540).toFloat()) }
        val v = local.last() - local.first()
        val turn = atan2(-box.height, box.width) - atan2(v.y, v.x)
        val c = cos(turn).toFloat(); val s = sin(turn).toFloat()
        val rotated = local.map { p -> val q = p - local.first(); Offset(q.x * c - q.y * s, q.x * s + q.y * c) }
        val minX = rotated.minOf { it.x }; val maxX = rotated.maxOf { it.x }
        val minY = rotated.minOf { it.y }; val maxY = rotated.maxOf { it.y }
        val scale = min(box.width / (maxX - minX), box.height / (maxY - minY))
        val ox = box.left + (box.width - (maxX - minX) * scale) / 2 - minX * scale
        val oy = box.top + (box.height - (maxY - minY) * scale) / 2 - minY * scale
        pts = rotated.map { Offset(it.x * scale + ox, it.y * scale + oy) }
    }

    val start: Offset get() = pts.first()
    val end: Offset get() = pts.last()

    /** Screen point and travel direction (radians) at `alongM` metres from the start. */
    fun at(alongM: Double): Pair<Offset, Float> {
        val a = alongM.coerceIn(0.0, cum.last())
        var i = cum.indexOfFirst { it >= a }.coerceAtLeast(1)
        if (i >= pts.size) i = pts.size - 1
        val f = ((a - cum[i - 1]) / maxOf(1e-9, cum[i] - cum[i - 1])).toFloat()
        val p = pts[i - 1] + (pts[i] - pts[i - 1]) * f
        val d = pts[i] - pts[i - 1]
        return p to atan2(d.y, d.x)
    }

    /** The road between two positions, as a path. */
    fun path(fromM: Double = 0.0, toM: Double = cum.last()): Path = Path().apply {
        val (a, _) = at(fromM)
        moveTo(a.x, a.y)
        for (i in pts.indices) if (cum[i] > fromM && cum[i] < toM) lineTo(pts[i].x, pts[i].y)
        val (b, _) = at(toM)
        lineTo(b.x, b.y)
    }

    /** Points every `stepPx` along the drawn road, for collision tests. */
    fun samples(stepPx: Float): List<Offset> = buildList {
        for (i in 1 until pts.size) {
            val d = pts[i] - pts[i - 1]
            val n = maxOf(1, (hypot(d.x, d.y) / stepPx).toInt())
            for (j in 0 until n) add(pts[i - 1] + d * (j / n.toFloat()))
        }
        add(pts.last())
    }
}

/** How each curve class is drawn: lighter and thinner for straight, darker and thicker the tighter the road bends. */
fun curveStroke(c: CurveClass, t: Tokens): Pair<Color, Dp> = when (c) {
    CurveClass.STRAIGHT -> t.muted to 3.dp
    CurveClass.CURVE -> t.ink2 to 7.dp
    CurveClass.SHARP -> t.ink to 11.dp
}

val TunnelBand = 22.dp

/**
 * Parked, before departure: the road from bottom-left to top-right, drawn by how it bends. Tunnels are a quiet band
 * under the road, usual facilities short ticks across it, different ones status marks with an icon chip beside them
 * (what was different and how many cars), never over the road, another chip or a `reserved` area.
 *
 * `preview` puts the preview-run dot on the road and dims what it has passed. It is read only while drawing the
 * overlay, so a moving dot redraws the overlay and nothing else.
 */
@Composable
fun RouteMap(
    briefing: Briefing,
    route: RouteIndex,
    curves: List<CurveStretch>,
    startName: String,
    endName: String,
    t: Tokens,
    reserved: List<Rect>,
    preview: State<Double?>,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val inset = with(density) { 56.dp.toPx() }
        val geo = remember(route, wPx, hPx) { RouteGeometry(route, Rect(inset, inset, wPx - inset, hPx - inset)) }
        Box(Modifier.fillMaxSize()) {
            // The map is drawn into its own offscreen layer: the preview dot above it redraws without re-recording the map.
            Spacer(
                Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
                    val ring = 2.dp.toPx()
                    val chips = placeChips(briefing, geo, tm, t, reserved, size.width, size.height)
                    val tickStyle = Type.captionNum.copy(color = t.muted)
                    val lengthKm = route.lengthM / 1000
                    val ticks = (10 until lengthKm.toInt() step 10).map { km -> km to tm.measure("$km", tickStyle) }
                    val startL = tm.measure(startName, Type.caption.copy(color = t.ink2))
                    val endL = tm.measure(endName, Type.caption.copy(color = t.ink2))
                    val tunnels = briefing.items.filter { it.anchor.type == AnchorType.TUNNEL }
                        .map { geo.path(it.anchor.alongM.toDouble(), it.anchor.endAlongM.toDouble()) }
                    val roads = CurveClass.entries.map { c -> c to curves.filter { it.curve == c }.map { geo.path(it.fromM, it.toM) } }

                    onDrawBehind {
                        for (p in tunnels) drawPath(p, t.tunnel, style = Stroke(TunnelBand.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
                        for ((c, paths) in roads) {
                            val (color, width) = curveStroke(c, t)
                            for (p in paths) drawPath(p, color, style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        endTick(geo.start, geo.at(0.0).second, t)
                        endTick(geo.end, geo.at(route.lengthM).second, t)

                        // Kilometre ticks across the road, numbers on the lower-right side.
                        for ((km, layout) in ticks) {
                            val (p, dir) = geo.at(km * 1000.0)
                            val n = Offset(-sin(dir), cos(dir)) // right-hand normal (down-right for an up-right road)
                            drawLine(t.axis, p + n * 16.dp.toPx(), p + n * 24.dp.toPx(), 1.dp.toPx())
                            val c = p + n * 40.dp.toPx()
                            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
                        }
                        drawText(startL, topLeft = Offset(geo.start.x - startL.size.width / 2f, geo.start.y + 20.dp.toPx()))
                        drawText(endL, topLeft = Offset(geo.end.x - endL.size.width / 2f, geo.end.y - 20.dp.toPx() - endL.size.height))

                        for (item in briefing.items) {
                            val (c, dir) = geo.at(item.pointM.toDouble())
                            when {
                                item.state.status == RibbonStatus.NOT_ENOUGH -> notEnoughMark(c, 12.dp.toPx(), 2.dp.toPx(), t, t.surface)
                                !item.different && item.anchor.type != AnchorType.TUNNEL -> crossTick(c, dir, t)
                            }
                        }
                        for (item in briefing.items.filter { it.different }) {
                            val d = item.state.deviation
                            statusMark(geo.at(item.pointM.toDouble()).first, d, (if (d == Deviation.HIGH) 28 else 20).dp.toPx(), t, ring, t.surface)
                        }
                        for (chip in chips) drawChip(chip, t)
                    }
                },
            )
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                val previewAlongM = preview.value
                if (previewAlongM != null) {
                    // What the preview has passed fades back; the dot runs on the road.
                    drawPath(geo.path(0.0, previewAlongM), t.surface.copy(alpha = 0.72f), style = Stroke(TunnelBand.toPx() + 4.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
                    val (me, dir) = geo.at(previewAlongM)
                    rotate((dir * 180 / PI).toFloat(), me) { chevron(me, 30.dp.toPx(), t, 3.dp.toPx()) }
                }
            }
        }
    }
}

private fun DrawScope.endTick(p: Offset, dir: Float, t: Tokens) {
    val n = Offset(-sin(dir), cos(dir))
    drawLine(t.ink2, p - n * 12.dp.toPx(), p + n * 12.dp.toPx(), 2.dp.toPx())
}

private fun DrawScope.crossTick(p: Offset, dir: Float, t: Tokens) {
    val n = Offset(-sin(dir), cos(dir))
    drawLine(t.ink2, p - n * 14.dp.toPx(), p + n * 14.dp.toPx(), 2.dp.toPx())
}

/** An icon chip: what was different (icon) and how many cars ("6/9"), joined to its mark by a leader. */
private data class MapChip(val item: BriefingItem, val count: TextLayoutResult, val rect: Rect, val leaderFrom: Offset, val leaderTo: Offset, val cost: Int)

private fun DrawScope.drawChip(chip: MapChip, t: Tokens) {
    drawLine(t.muted, chip.leaderFrom, chip.leaderTo, 1.dp.toPx())
    val r = chip.rect
    drawRoundRect(t.surface, r.topLeft, r.size, CornerRadius(8.dp.toPx()))
    drawRoundRect(t.axis, r.topLeft, r.size, CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
    val icon = ChipIcon.toPx()
    val pad = ChipPad.toPx()
    issueIcon(Words.top(chip.item)?.metric, Offset(r.left + pad + icon / 2, r.center.y), icon, t.ink)
    drawText(chip.count, topLeft = Offset(r.left + pad + icon + ChipGap.toPx(), r.center.y - chip.count.size.height / 2f))
}

private val ChipIcon = 36.dp
private val ChipPad = 10.dp
private val ChipGap = 8.dp

/**
 * Each chip may sit in four directions from its mark at three distances. All combinations for the (at most three)
 * chips are tried; a combination is valid when no chip overlaps another chip, the road, another mark, a reserved
 * area or the edge. The cheapest valid one wins (across the road and close first).
 */
private fun Density.placeChips(
    briefing: Briefing, geo: RouteGeometry, tm: TextMeasurer, t: Tokens,
    reserved: List<Rect>, w: Float, h: Float,
): List<MapChip> {
    val roadPts = geo.samples(6.dp.toPx())
    val marks = briefing.items.filter { it.different }.map { geo.at(it.pointM.toDouble()).first }
    val pad = 8.dp.toPx()
    val roadClear = TunnelBand.toPx() / 2
    val options = briefing.labeled.sortedBy { it.pointM }.map { item ->
        val count = tm.measure(Words.count(item), Type.label.copy(color = t.ink, fontFeatureSettings = "tnum"))
        val cw = ChipPad.toPx() * 2 + ChipIcon.toPx() + ChipGap.toPx() + count.size.width
        val ch = ChipIcon.toPx() + 12.dp.toPx()
        val (c, _) = geo.at(item.pointM.toDouble())
        val r = (if (item.state.deviation == Deviation.HIGH) 18 else 14).dp.toPx()
        // (dx, dy) unit directions; up-left and down-right lie across an up-right road.
        val dirs = listOf(Triple(-1f, -1f, 0), Triple(1f, 1f, 0), Triple(1f, -1f, 1), Triple(-1f, 1f, 1))
        listOf(24, 60, 108).flatMapIndexed { di, dist ->
            dirs.map { (sx, sy, dirCost) ->
                val g = dist.dp.toPx()
                val left = if (sx > 0) c.x + g * 0.6f else c.x - g * 0.6f - cw
                val top = if (sy > 0) c.y + g * 0.6f else c.y - g * 0.6f - ch
                val rect = Rect(left, top, left + cw, top + ch)
                val corner = Offset(if (sx > 0) rect.left else rect.right, if (sy > 0) rect.top else rect.bottom)
                val from = c + (corner - c) * (r / maxOf(1f, (corner - c).getDistance()))
                MapChip(item, count, rect, from, corner, dirCost + di * 3)
            }
        }.filter { o ->
            val box = o.rect.inflate(pad)
            box.left >= 0 && box.top >= 0 && box.right <= w && box.bottom <= h &&
                reserved.none { it.overlaps(box) } &&
                roadPts.none { box.inflate(roadClear).contains(it) } &&
                marks.none { m -> box.inflate(14.dp.toPx()).contains(m) }
        }
    }
    var best: List<MapChip>? = null
    fun search(i: Int, chosen: List<MapChip>) {
        if (best != null && chosen.sumOf { it.cost } >= best!!.sumOf { it.cost }) return
        if (i == options.size) { best = chosen; return }
        if (options[i].isEmpty()) { search(i + 1, chosen); return } // no room: this one is left to the list
        for (o in options[i]) if (chosen.none { it.rect.inflate(16.dp.toPx()).overlaps(o.rect) }) search(i + 1, chosen + o)
    }
    search(0, emptyList())
    return best.orEmpty()
}

/** The swatch for one legend entry, drawn exactly like the map draws it. */
@Composable
fun LegendSwatch(kind: LegendKind, t: Tokens) = when (kind) {
    LegendKind.HIGH -> StatusGlyph(Deviation.HIGH, 24.dp, t, t.surface)
    LegendKind.SOME -> StatusGlyph(Deviation.SOME, 24.dp, t, t.surface)
    LegendKind.USUAL -> Canvas(Modifier.size(24.dp)) {
        drawLine(t.muted, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
        drawLine(t.ink2, Offset(size.width / 2, size.height / 2 - 14.dp.toPx() / 2), Offset(size.width / 2, size.height / 2 + 14.dp.toPx() / 2), 2.dp.toPx())
    }
    LegendKind.NOT_ENOUGH -> StatusGlyph(Deviation.NONE, 24.dp, t, t.surface, notEnough = true)
    LegendKind.STRAIGHT, LegendKind.CURVE, LegendKind.SHARP -> Canvas(Modifier.size(width = 40.dp, height = 24.dp)) {
        val c = when (kind) { LegendKind.STRAIGHT -> CurveClass.STRAIGHT; LegendKind.CURVE -> CurveClass.CURVE; else -> CurveClass.SHARP }
        val (color, width) = curveStroke(c, t)
        val p = Path().apply {
            moveTo(4.dp.toPx(), size.height * 0.7f)
            if (c == CurveClass.STRAIGHT) lineTo(size.width - 4.dp.toPx(), size.height * 0.3f)
            else quadraticBezierTo(size.width * 0.5f, if (c == CurveClass.SHARP) -size.height * 0.5f else size.height * 0.05f, size.width - 4.dp.toPx(), size.height * 0.7f)
        }
        drawPath(p, color, style = Stroke(width.toPx(), cap = StrokeCap.Round))
    }
    LegendKind.TUNNEL -> Canvas(Modifier.size(width = 40.dp, height = 24.dp)) {
        drawRect(t.tunnel, Offset(0f, (size.height - TunnelBand.toPx()) / 2), Size(size.width, TunnelBand.toPx()))
        drawLine(t.muted, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
    }
}

enum class LegendKind { HIGH, SOME, USUAL, NOT_ENOUGH, STRAIGHT, CURVE, SHARP, TUNNEL }
