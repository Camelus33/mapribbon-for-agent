package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.demo.WeaveFrame
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.brief.WeaveOutcome
import kr.tedin.maptalkdrive.ribbon.brief.WeavePlan
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The briefing before the replay has tied anything: every place as usual and no temperature yet, so the map and the
 * height strip start bare and the ribbons (and the cold line) appear when the replay reaches them.
 */
fun Briefing.untied(): Briefing = Briefing(
    items.map { it.copy(state = it.state.copy(deviation = Deviation.NONE, reasons = emptyList(), metrics = it.state.metrics - Metric.OUTSIDE_TEMP_C)) },
    headline = null,
    labeled = emptyList(),
)

/** Whether the map should still show the untied briefing at this moment of the replay. */
fun WeaveFrame?.untied(): Boolean = this != null && t < plan.revealS

/** Distance between two knots in a place's grid. */
private val KnotPitch = 15.dp

private fun ease(x: Double): Float = (1 - Math.pow(1 - x.coerceIn(0.0, 1.0), 3.0)).toFloat()

/** Words the map needs for the replay. */
data class WeaveLabels(val threshold: String, val count: (Int, Int) -> String)

internal data class KnotSlot(val car: Int, val slot: Int, val colored: Boolean)

/** Where the replay draws on one map: each place's point and direction, and its knots in arrival order. */
class WeaveLayout(val plan: WeavePlan, private val geo: RouteGeometry) {
    internal val points: List<Pair<Offset, Float>> = plan.places.map { geo.at(it.pointM.toDouble()) }
    internal val knots: List<List<KnotSlot>> = plan.places.indices.map { i ->
        var slot = 0
        (0 until plan.cars).filter { plan.passes(i, it) }.map { c -> KnotSlot(c, slot++, plan.colored(i, c)) }
    }
    internal val cols = ceil(sqrt(maxOf(1, plan.cars).toDouble())).toInt()

    /** Where a coloured knot flashes as it lands: where ribbons tie and the focus; with colour everywhere, every third. */
    internal val flashes: Set<Int> = run {
        val withHits = plan.places.indices.filter { plan.places[it].hits > 0 }
        val focus = setOfNotNull(plan.focusIndex.takeIf { it >= 0 })
        if (plan.tiedIds.isNotEmpty()) withHits.filter { plan.places[it].anchorId in plan.tiedIds }.toSet() + focus
        else withHits.filterIndexed { k, _ -> k % 3 == 0 }.toSet() + focus
    }
}

/** One layout per plan on a map; the plan only changes when a replay starts. */
class WeaveLayoutCache(private val geo: RouteGeometry) {
    private var last: WeaveLayout? = null
    fun of(plan: WeavePlan): WeaveLayout = last?.takeIf { it.plan === plan } ?: WeaveLayout(plan, geo).also { last = it }
}

/**
 * One moment of "knots become a ribbon" on the map, over the untied map:
 * - the cars run the road one after another; each drops a knot at the middle of every place it passed, grey as usual
 *   or coloured where it did the story's thing, and a coloured one flashes the story's icon where it matters;
 * - at the focus a counter shows coloured knots over knots so far, and once [WeavePlan.minCars] distinct cars passed,
 *   the threshold mark appears: the place can now be judged and published;
 * - after the verdict, each place whose ribbon ties draws its band along its stretch, and the knots fade into the map,
 *   which from then on shows the ribbons as they are.
 */
fun DrawScope.drawWeave(t: Double, lay: WeaveLayout, geo: RouteGeometry, tk: Tokens, tm: TextMeasurer, labels: WeaveLabels) {
    val p = lay.plan
    val fade = if (t <= p.revealS) 1f else 1f - ease((t - p.revealS) / 0.8)

    // The ribbons tie: a band grows along each stretch (a facility's mark grows in place).
    val drawFrom = p.evalS + p.barsS
    if (t >= drawFrom && t < p.revealS) {
        val r = ease((t - drawFrom) / p.drawS)
        for ((i, place) in p.places.withIndex()) {
            if (place.deviation == Deviation.NONE) continue
            val color = if (place.deviation == Deviation.HIGH) tk.high else tk.some
            if (place.type == AnchorType.SEGMENT) {
                val to = place.alongM + (place.endAlongM - place.alongM) * r.toDouble()
                drawPath(geo.path(place.alongM.toDouble(), to), color.copy(alpha = StretchAlpha), style = Stroke(TunnelBand.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
            } else {
                statusMark(lay.points[i].first, place.deviation, (if (place.deviation == Deviation.HIGH) 28 else 20).dp.toPx() * r, tk, 2.dp.toPx(), tk.surface)
            }
        }
    }

    // Knots, in a small grid on the road at each place's middle, dropping in as their car passes.
    if (fade > 0f) {
        val pitch = KnotPitch.toPx()
        for ((i, slots) in lay.knots.withIndex()) {
            val c = lay.points[i].first
            val rows = ceil(slots.size / lay.cols.toDouble()).toInt().coerceAtLeast(1)
            val colsHere = minOf(lay.cols, slots.size).coerceAtLeast(1)
            for (k in slots) {
                val a = ((t - p.dropS(i, k.car)) / 0.35).coerceIn(0.0, 1.0).toFloat()
                if (a <= 0f) continue
                val col = k.slot % lay.cols; val row = k.slot / lay.cols
                val at = c + Offset((col - (colsHere - 1) / 2f) * pitch, (row - (rows - 1) / 2f) * pitch - 8.dp.toPx() * (1 - a))
                val r = (if (k.colored) 6 else 5).dp.toPx()
                drawCircle(tk.surface.copy(alpha = a * fade), r + 1.5f.dp.toPx(), at)
                drawCircle((if (k.colored) tk.knot else tk.muted).copy(alpha = a * fade), r, at)
            }
        }
        // A coloured knot landing where it matters: a ring opens and the story's icon rises from it.
        for (i in lay.flashes) {
            val c = lay.points[i].first
            for (k in lay.knots[i]) {
                if (!k.colored) continue
                val d = (t - p.dropS(i, k.car)) / 0.6
                if (d < 0 || d >= 1) continue
                val a = (1 - d).toFloat()
                drawCircle(tk.knot.copy(alpha = a), 10.dp.toPx() + 18.dp.toPx() * d.toFloat(), c, style = Stroke(2.dp.toPx()))
                issueIcon(p.metric, c - Offset(0f, 30.dp.toPx() + 14.dp.toPx() * d.toFloat()), 26.dp.toPx(), tk.knot.copy(alpha = a))
            }
        }
    }

    // The cars on their way: the preview run's chevron, light with a dark rim, since the road under it is dark.
    val carInk = tk.copy(position = tk.surface, positionRing = tk.ink)
    for (car in 0 until p.cars) {
        val along = p.carAlong(car, t) ?: continue
        val (at, dir) = geo.at(along)
        rotate((dir * 180 / PI).toFloat(), at) { chevron(at, 22.dp.toPx(), carInk, 2.dp.toPx()) }
    }

    // The counter at the focus, and the threshold once enough distinct cars passed.
    val fi = p.focusIndex
    if (fi >= 0 && p.metric != null && t < p.revealS + 0.4) {
        val passed = p.passedBy(fi, t)
        if (passed > 0) {
            val alpha = if (t <= p.revealS) 1f else (1 - (t - p.revealS) / 0.4).toFloat().coerceIn(0f, 1f)
            val hits = p.hitsBy(fi, t)
            val met = passed >= p.minCars
            val (c, dir) = lay.points[fi]
            val left = Offset(sin(dir), -cos(dir)) // left of the direction of travel: up-left on an up-right road
            val text = tm.measure(labels.count(hits, passed), Type.label.copy(color = tk.ink, fontFeatureSettings = "tnum"))
            val icon = 30.dp.toPx(); val pad = 12.dp.toPx(); val gap = 8.dp.toPx()
            val w = pad + icon + gap + text.size.width + pad
            val h = 48.dp.toPx()
            val margin = 12.dp.toPx()
            val center = c + left * 96.dp.toPx()
            val box = Rect(
                (center.x - w / 2).coerceIn(margin, size.width - w - margin),
                (center.y - h / 2).coerceIn(margin + 36.dp.toPx(), size.height - h - margin),
                0f, 0f,
            ).let { Rect(it.left, it.top, it.left + w, it.top + h) }
            val cluster = (lay.cols * KnotPitch.toPx()) * 0.75f + 6.dp.toPx()
            val toBox = box.center - c
            val reach = toBox.getDistance().coerceAtLeast(1f)
            drawLine(tk.muted.copy(alpha = alpha), c + toBox * (cluster / reach), box.center - toBox * ((h / 2) / reach), 1.dp.toPx())
            if (met) {
                drawCircle(tk.high.copy(alpha = alpha), cluster + 6.dp.toPx(), c, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
            }
            drawRoundRect(tk.surface.copy(alpha = alpha), box.topLeft, box.size, CornerRadius(h / 2))
            drawRoundRect(
                (if (met) tk.high else tk.axis).copy(alpha = alpha), box.topLeft, box.size, CornerRadius(h / 2),
                style = Stroke((if (met) 2.5f else 1f).dp.toPx()),
            )
            issueIcon(p.metric, Offset(box.left + pad + icon / 2, box.center.y), icon, tk.ink.copy(alpha = alpha))
            drawText(text, topLeft = Offset(box.left + pad + icon + gap, box.center.y - text.size.height / 2f), alpha = alpha)
            if (met) {
                val l = tm.measure(labels.threshold, Type.caption.copy(color = tk.ink2))
                drawText(l, topLeft = Offset(box.center.x - l.size.width / 2f, box.top - 6.dp.toPx() - l.size.height), alpha = alpha)
            }
        }
    }
}

/** A knot as the map draws it, for the key in the card. */
@Composable
private fun KnotDot(color: Color, t: Tokens) = Canvas(Modifier.size(20.dp)) {
    drawCircle(t.surface, 7.dp.toPx())
    drawCircle(color, 5.5f.dp.toPx())
}

/**
 * The words beside the replay. They add up rather than replace one another, so each can be read at its own pace and
 * the end holds all of it: what the knots are, that a place needs three distinct cars, the verdict against the
 * neighbours and the usual value, and whether a ribbon tied.
 */
@Composable
fun WeaveCard(plan: WeavePlan, frame: State<WeaveFrame?>, b: Briefing, coldHint: Boolean, w: Words, t: Tokens) {
    val focusItem = remember(plan, b) { plan.focus?.let { f -> b.items.firstOrNull { it.anchor.id == f.anchorId } } }
    val threshold by remember(plan) { derivedStateOf { plan.focusIndex >= 0 && plan.passedBy(plan.focusIndex, frame.value?.t ?: 0.0) >= plan.minCars } }
    val judging by remember(plan) { derivedStateOf { (frame.value?.t ?: 0.0) >= plan.evalS } }
    val judged by remember(plan) { derivedStateOf { (frame.value?.t ?: 0.0) >= plan.evalS + plan.barsS } }
    val revealed by remember(plan) { derivedStateOf { (frame.value?.t ?: 0.0) >= plan.revealS } }
    Column {
        Text(w.str(R.string.weave_title), style = Type.headline, color = t.ink)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KnotDot(t.muted, t)
            Spacer(Modifier.width(8.dp))
            Text(w.str(R.string.weave_key_usual), style = Type.context, color = t.ink2)
            if (plan.metric != null) {
                Spacer(Modifier.width(24.dp))
                KnotDot(t.knot, t)
                Spacer(Modifier.width(8.dp))
                IssueIcon(plan.metric, 32.dp, t.ink)
                Spacer(Modifier.width(8.dp))
                Text(w.issue(plan.metric), style = Type.context, color = t.ink2)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            listOfNotNull(
                // Demo scenarios send the same cars everywhere; on a real road places differ in how many cars passed.
                if (plan.fewestCars == plan.cars) w.str(R.string.weave_cars, plan.cars) else w.str(R.string.weave_cars_varied, plan.fewestCars, plan.cars),
                w.str(R.string.weave_knots),
                plan.metric?.let { w.str(R.string.weave_colours, w.issue(it)) },
            ).joinToString(" "),
            style = Type.body, color = t.ink,
        )
        if (threshold && focusItem != null) {
            Spacer(Modifier.height(12.dp))
            Text(w.str(R.string.weave_threshold, w.place(focusItem), plan.minCars), style = Type.body, color = t.ink)
        }
        if (judging && focusItem != null && plan.outcome != WeaveOutcome.NOTHING) {
            Spacer(Modifier.height(20.dp))
            Judgement(plan, frame, focusItem, judged, w, t)
        }
        if (revealed) {
            Spacer(Modifier.height(20.dp))
            Text(result(plan, focusItem, w), style = Type.label, color = t.ink)
            if (coldHint && plan.outcome == WeaveOutcome.TIED) {
                Spacer(Modifier.height(8.dp))
                Text(w.str(R.string.weave_tied_cold, "%.0f".format(Metric.ICE_TEMP_C)), style = Type.body, color = t.ink2)
            }
        }
    }
}

/** The focus against its neighbours and its usual value, as cars out of the same number, with bars that fill. */
@Composable
private fun Judgement(plan: WeavePlan, frame: State<WeaveFrame?>, focus: BriefingItem, judged: Boolean, w: Words, t: Tokens) {
    val n = plan.focus!!.n
    fun cars(rate: Double) = w.str(R.string.count_cars, (rate * n).roundToInt(), n)
    Column {
        Text(w.str(R.string.weave_judge_title, w.place(focus)), style = Type.label, color = t.ink)
        Text(
            w.str(if (plan.usualRate != null) R.string.weave_judge else R.string.weave_judge_neighbours_only),
            style = Type.caption, color = t.ink2,
        )
        Spacer(Modifier.height(12.dp))
        JudgeRow(w.str(R.string.weave_this), plan.focusRate, plan.focusRate?.let(::cars) ?: "—", t.knot, plan, frame, t)
        JudgeRow(w.str(R.string.weave_neighbours), plan.neighbourRate, plan.neighbourRate?.let(::cars) ?: "—", t.muted, plan, frame, t)
        val usual = plan.usualRate
        JudgeRow(
            w.str(R.string.weave_usual), usual,
            when {
                usual == null -> w.str(R.string.weave_usual_unknown)
                (usual * n).roundToInt() == 0 -> w.str(R.string.weave_usual_almost_none)
                else -> w.str(R.string.weave_usual_about, (usual * n).roundToInt(), n)
            },
            t.muted, plan, frame, t,
        )
        if (judged) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plan.outcome == WeaveOutcome.TIED) {
                    StatusGlyph(plan.focus!!.deviation, 32.dp, t, t.surface)
                    Spacer(Modifier.width(10.dp))
                }
                Text(verdict(plan, w), style = Type.label, color = t.ink)
            }
        }
    }
}

@Composable
private fun JudgeRow(label: String, rate: Double?, value: String, color: Color, plan: WeavePlan, frame: State<WeaveFrame?>, t: Tokens) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
        Text(label, style = Type.context, color = t.ink2, modifier = Modifier.width(132.dp))
        Canvas(Modifier.size(width = 200.dp, height = 14.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(t.grid, Offset.Zero, size, r)
            if (rate != null) {
                // Only this canvas redraws while the bars fill.
                val fill = ease(((frame.value?.t ?: 0.0) - plan.evalS) / plan.barsS) * rate.toFloat().coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(color, Offset.Zero, Size(size.width * fill, size.height), r)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(value, style = Type.bodyNum, color = t.ink)
    }
}

private fun verdict(p: WeavePlan, w: Words): String = w.str(
    when (p.outcome) {
        WeaveOutcome.TIED -> if (p.usualRate != null) R.string.weave_verdict_tied else R.string.weave_verdict_tied_neighbours
        WeaveOutcome.TOO_FEW -> R.string.weave_verdict_few
        WeaveOutcome.LIKE_NEIGHBOURS -> R.string.weave_verdict_neighbours
        WeaveOutcome.LIKE_USUAL -> R.string.weave_verdict_usual
        WeaveOutcome.SMALL, WeaveOutcome.NOTHING -> R.string.weave_verdict_small
    },
)

private fun result(p: WeavePlan, focus: BriefingItem?, w: Words): String = when (p.outcome) {
    WeaveOutcome.TIED -> w.str(R.string.weave_tied, focus?.let(w::place) ?: "", w.issue(p.metric), p.focus!!.let { w.str(R.string.count_cars, it.hits, it.n) })
    WeaveOutcome.TOO_FEW -> w.str(R.string.weave_none_few, p.minCars)
    WeaveOutcome.LIKE_NEIGHBOURS -> w.str(R.string.weave_none_neighbours)
    WeaveOutcome.LIKE_USUAL -> w.str(R.string.weave_none_usual)
    WeaveOutcome.SMALL -> w.str(R.string.weave_none_small)
    WeaveOutcome.NOTHING -> w.str(R.string.weave_none_quiet)
}
