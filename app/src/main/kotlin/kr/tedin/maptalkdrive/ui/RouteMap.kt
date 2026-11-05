package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
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
 * Turning keeps the shape and its proportions; positions along the road are read from the road's own metres.
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

/**
 * Parked: the road from bottom-left to top-right. Tunnels are thicker stretches, usual facilities quiet ticks across
 * the road, different ones status marks with a label set beside them, never over the road, another label or `reserved`.
 */
@Composable
fun RouteMap(
    briefing: Briefing,
    route: RouteIndex,
    startName: String,
    endName: String,
    t: Tokens,
    reserved: List<Rect>,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val inset = with(density) { 56.dp.toPx() }
        val geo = remember(route, wPx, hPx) { RouteGeometry(route, Rect(inset, inset, wPx - inset, hPx - inset)) }
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val ring = 2.dp.toPx()
                val labels = placeLabels(briefing, geo, tm, t, reserved, size.width, size.height)
                val tickStyle = Type.captionNum.copy(color = t.muted)
                val lengthKm = route.lengthM / 1000
                val ticks = (10 until lengthKm.toInt() step 10).map { km -> km to tm.measure("$km", tickStyle) }
                val startL = tm.measure(startName, Type.caption.copy(color = t.ink2))
                val endL = tm.measure(endName, Type.caption.copy(color = t.ink2))
                val road = geo.path()
                val tunnels = briefing.items.filter { it.anchor.type == AnchorType.TUNNEL }
                    .map { geo.path(it.anchor.alongM.toDouble() + 30, it.anchor.endAlongM.toDouble() - 30) }

                onDrawBehind {
                    drawPath(road, t.trackAhead, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    for (p in tunnels) drawPath(p, t.trackAhead, style = Stroke(12.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
                    endTick(geo.start, geo.at(0.0).second, t)
                    endTick(geo.end, geo.at(route.lengthM).second, t)

                    // Kilometre ticks across the road, numbers on the lower-right side.
                    for ((km, layout) in ticks) {
                        val (p, dir) = geo.at(km * 1000.0)
                        val n = Offset(-sin(dir), cos(dir)) // right-hand normal (down-right for an up-right road)
                        drawLine(t.axis, p + n * 10.dp.toPx(), p + n * 18.dp.toPx(), 1.dp.toPx())
                        val c = p + n * 34.dp.toPx()
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
                    for (l in labels) {
                        drawLine(t.muted, l.leaderFrom, l.leaderTo, 1.dp.toPx())
                        drawText(l.layout, topLeft = l.rect.topLeft)
                    }
                }
            },
        )
    }
}

/** Driving: the same road with no text. Behind me dimmed, my stretch to the next difference highlighted, me as a chevron. */
@Composable
fun DrivingMap(briefing: Briefing, route: RouteIndex, myAlongM: Double, t: Tokens, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val inset = with(density) { 40.dp.toPx() }
        val geo = remember(route, wPx, hPx) { RouteGeometry(route, Rect(inset, inset, wPx - inset, hPx - inset)) }
        val road = remember(geo) { geo.path() }
        Canvas(Modifier.fillMaxSize()) {
            val w = 6.dp.toPx()
            val next = briefing.nextDifferentAhead(myAlongM)
            drawPath(road, t.trackAhead, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(geo.path(0.0, myAlongM), t.trackPassed, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (next != null) drawPath(geo.path(myAlongM, next.first.pointM.toDouble()), t.trackToNext, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            endTick(geo.end, geo.at(route.lengthM).second, t)
            for (item in briefing.items.filter { it.different && it.pointM > myAlongM }) {
                val isNext = item.anchor.id == next?.first?.anchor?.id
                val d = item.state.deviation
                val s = when {
                    d == Deviation.HIGH && isNext -> 32
                    d == Deviation.HIGH -> 24
                    isNext -> 26
                    else -> 18
                }.dp.toPx()
                statusMark(geo.at(item.pointM.toDouble()).first, d, s, t, 2.dp.toPx(), t.surface)
            }
            val (me, dir) = geo.at(myAlongM)
            rotate((dir * 180 / PI).toFloat(), me) { chevron(me, 28.dp.toPx(), t, 2.dp.toPx()) }
        }
    }
}

private fun DrawScope.endTick(p: Offset, dir: Float, t: Tokens) {
    val n = Offset(-sin(dir), cos(dir))
    drawLine(t.ink2, p - n * 12.dp.toPx(), p + n * 12.dp.toPx(), 2.dp.toPx())
}

private fun DrawScope.crossTick(p: Offset, dir: Float, t: Tokens) {
    val n = Offset(-sin(dir), cos(dir))
    drawLine(t.muted, p - n * 6.dp.toPx(), p + n * 6.dp.toPx(), 2.dp.toPx())
}

private data class MapLabel(val item: BriefingItem, val layout: TextLayoutResult, val rect: Rect, val leaderFrom: Offset, val leaderTo: Offset, val cost: Int)

/**
 * Each label may sit in four directions from its mark at three distances. All combinations for the (at most three)
 * labels are tried; a combination is valid when no label overlaps another label, the road, another mark, a reserved
 * area or the edge. The cheapest valid one wins (perpendicular to the road and close first).
 */
private fun androidx.compose.ui.unit.Density.placeLabels(
    briefing: Briefing, geo: RouteGeometry, tm: androidx.compose.ui.text.TextMeasurer, t: Tokens,
    reserved: List<Rect>, w: Float, h: Float,
): List<MapLabel> {
    val roadPts = geo.samples(6.dp.toPx())
    val marks = briefing.items.filter { it.different }.map { geo.at(it.pointM.toDouble()).first }
    val pad = 8.dp.toPx()
    val options = briefing.labeled.sortedBy { it.pointM }.map { item ->
        val layout = tm.measure("${Words.place(item)} · ${Words.issueShort(Words.top(item)?.metric)}", Type.label.copy(color = t.ink))
        val lw = layout.size.width.toFloat(); val lh = layout.size.height.toFloat()
        val (c, _) = geo.at(item.pointM.toDouble())
        val r = (if (item.state.deviation == Deviation.HIGH) 18 else 14).dp.toPx()
        // (dx, dy) unit directions; up-left and down-right lie across an up-right road.
        val dirs = listOf(Triple(-1f, -1f, 0), Triple(1f, 1f, 0), Triple(1f, -1f, 1), Triple(-1f, 1f, 1))
        listOf(20, 56, 104).flatMapIndexed { di, dist ->
            dirs.map { (sx, sy, dirCost) ->
                val g = dist.dp.toPx()
                val left = if (sx > 0) c.x + g * 0.6f else c.x - g * 0.6f - lw
                val top = if (sy > 0) c.y + g * 0.6f else c.y - g * 0.6f - lh
                val rect = Rect(left, top, left + lw, top + lh)
                val corner = Offset(if (sx > 0) rect.left else rect.right, if (sy > 0) rect.top else rect.bottom)
                val from = c + (corner - c) * (r / maxOf(1f, (corner - c).getDistance()))
                MapLabel(item, layout, rect, from, corner, dirCost + di * 3)
            }
        }.filter { o ->
            val box = o.rect.inflate(pad)
            box.left >= 0 && box.top >= 0 && box.right <= w && box.bottom <= h &&
                reserved.none { it.overlaps(box) } &&
                roadPts.none { box.contains(it) } &&
                marks.none { m -> box.inflate(14.dp.toPx()).contains(m) }
        }
    }
    var best: List<MapLabel>? = null
    fun search(i: Int, chosen: List<MapLabel>) {
        if (best != null && chosen.sumOf { it.cost } >= best!!.sumOf { it.cost }) return
        if (i == options.size) { best = chosen; return }
        if (options[i].isEmpty()) { search(i + 1, chosen); return } // no room: this label is left to the table
        for (o in options[i]) if (chosen.none { it.rect.inflate(16.dp.toPx()).overlaps(o.rect) }) search(i + 1, chosen + o)
    }
    search(0, emptyList())
    return best.orEmpty()
}

/** The swatch for one legend entry, drawn exactly like the map draws that state. */
@Composable
fun LegendSwatch(kind: LegendKind, t: Tokens) = when (kind) {
    LegendKind.HIGH -> StatusGlyph(Deviation.HIGH, 24.dp, t, t.surface)
    LegendKind.SOME -> StatusGlyph(Deviation.SOME, 24.dp, t, t.surface)
    LegendKind.USUAL -> StatusGlyph(Deviation.NONE, 24.dp, t, t.surface)
    LegendKind.NOT_ENOUGH -> StatusGlyph(Deviation.NONE, 24.dp, t, t.surface, notEnough = true)
}

enum class LegendKind { HIGH, SOME, USUAL, NOT_ENOUGH }
