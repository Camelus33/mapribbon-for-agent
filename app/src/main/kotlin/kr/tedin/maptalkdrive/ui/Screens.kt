package kr.tedin.maptalkdrive.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.channel.ChannelState
import kr.tedin.maptalkdrive.demo.RibbonViewModel
import kr.tedin.maptalkdrive.port.Gear
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.port.VehicleSignals
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.geo.CurveClass
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus

private val Gutter = 48.dp
private val Touch = 76.dp

/**
 * Before departure the app shows the route and what the ribbons on it say, and can run the route as a preview. While
 * the car moves (CarUxRestrictions) it shows only that this car's agent is recording: no map, no position, no
 * controls. The navigation stays the driver's guide.
 */
@Composable
fun AppScreen(vm: RibbonViewModel, restricted: Boolean) {
    val t = LocalTokens.current
    val w = rememberWords()
    val s by vm.channel.state.collectAsState()
    // The preview position changes every frame; it is passed as State and read only where it is drawn.
    val preview = vm.preview.collectAsState()
    var sheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(restricted) { if (restricted) { vm.stopPreview(); sheet = false } }
    Box(Modifier.fillMaxSize().background(t.surface)) {
        if (restricted || s.recording) {
            RecordingScreen(vm, s, restricted, t, w)
        } else {
            BriefingScreen(vm, s, preview, t, w, onOpenDemo = { sheet = true })
            if (sheet) DemoSheet(vm, s, t, w, onClose = { sheet = false }, Modifier.align(Alignment.CenterEnd))
        }
    }
}

// ---------------------------------------------------------------- before departure

@Composable
private fun BriefingScreen(vm: RibbonViewModel, s: ChannelState, preview: State<Double?>, t: Tokens, w: Words, onOpenDemo: () -> Unit) {
    val running by remember { derivedStateOf { preview.value != null } }
    val r = vm.data.route(s.routeId)
    val set = r.anchorSet
    val b = s.briefing
    val (start, end) = w.ends(set)
    Column(Modifier.fillMaxSize().padding(start = Gutter, end = Gutter, top = 32.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$start → $end · ${Words.at(set.lengthM)}", style = Type.context, color = t.ink2)
                val status = when {
                    s.replaying -> w.str(R.string.sim_passing, s.replayDone, s.replayTotal)
                    s.naviNotice != null -> s.naviNotice
                    else -> null
                }
                if (status != null) Text(status, style = Type.caption, color = t.ink2)
            }
            SimChip(t, w)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = onOpenDemo, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                Text(w.str(R.string.demo), style = Type.button, color = t.ink)
            }
        }
        // The road runs from bottom-left (start) to top-right (destination). The two empty corners it leaves hold the
        // card (top-left) and the list (bottom-right); the map keeps its chips out of both.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The card grows while the preview runs; its reserved area only ever grows within one mode, so the map does
            // not re-place its chips each time a distance changes width.
            var cardRect by remember(running) { mutableStateOf(Rect.Zero) }
            var listRect by remember { mutableStateOf(Rect.Zero) }
            RouteMap(
                b, r.route, r.curves, r.hairpins, start, end, t,
                reserved = listOf(cardRect, listRect).filter { it != Rect.Zero },
                preview = preview, modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.align(Alignment.TopStart).padding(top = 24.dp).onGloballyPositioned { cardRect = grow(cardRect, it.boundsInParent()) }) {
                if (running) {
                    PreviewCard(b, preview, w, t)
                } else {
                    val top = b.headline
                    if (top != null) {
                        PlaceCard(top, w, t, ahead = null)
                        Spacer(Modifier.height(16.dp))
                        Counts(b, w, t)
                    } else {
                        Verdict(b.items.count { it.state.status == RibbonStatus.PUBLISHED }, b.notEnoughCount, rest = false, w, t)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Legend(b, r.curves.map { it.curve }.toSet(), r.hairpins.isNotEmpty(), w, t)
            }
            Box(Modifier.align(Alignment.BottomEnd).widthIn(max = 820.dp).onGloballyPositioned { listRect = it.boundsInParent() }) {
                PlaceList(b.items.filter { it.different }.sortedWith(compareByDescending<BriefingItem> { it.state.deviation.ordinal }.thenBy { it.pointM }), w, t)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (running) w.str(R.string.preview_desc) else set.attribution,
                style = if (running) Type.caption else Type.legal, color = if (running) t.ink2 else t.muted,
                modifier = Modifier.weight(1f),
            )
            if (!running) {
                Button(
                    onClick = { vm.startPreview() },
                    modifier = Modifier.height(Touch),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = t.actionBg, contentColor = t.actionInk),
                ) {
                    IconBox(24.dp) { c, sz -> playIcon(c, sz, t.actionInk) }
                    Spacer(Modifier.width(12.dp))
                    Text(w.str(R.string.preview_run), style = Type.button, modifier = Modifier.padding(end = 8.dp))
                }
            } else {
                OutlinedButton(onClick = { vm.stopPreview() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                    IconBox(24.dp) { c, sz -> stopIcon(c, sz, t.ink) }
                    Spacer(Modifier.width(12.dp))
                    Text(w.str(R.string.preview_stop), style = Type.button, color = t.ink)
                }
            }
        }
    }
}

/** Grows `old` to hold `new`; an empty rect takes `new` as is. */
private fun grow(old: Rect, new: Rect): Rect =
    if (old == Rect.Zero) new else Rect(minOf(old.left, new.left), minOf(old.top, new.top), maxOf(old.right, new.right), maxOf(old.bottom, new.bottom))

/** What the preview card shows: the next different place ahead and its distance, or how the rest of the route stands. */
private data class AheadView(val item: BriefingItem?, val distance: Pair<String, String>?, val judged: Int, val notJudged: Int) {
    /** The card changes (and cross-fades) only when this does, not when the distance ticks down. */
    val key: String get() = item?.anchor?.id ?: if (judged == 0 && notJudged > 0) "none" else "clear"
}

/** The preview card: the next different place ahead of the preview dot, with its distance. Only this recomposes per frame. */
@Composable
private fun PreviewCard(b: Briefing, preview: State<Double?>, w: Words, t: Tokens) {
    // Recomposes only when the place ahead, its rounded distance or the rest of the route changes, not on every frame.
    val view by remember(b, w) {
        derivedStateOf {
            val along = preview.value ?: 0.0
            val next = b.nextDifferentAhead(along)
            if (next != null) {
                AheadView(next.first, w.ahead(next.second), 0, 0)
            } else {
                val rest = b.items.filter { it.pointM > along }
                AheadView(null, null, rest.count { it.state.status == RibbonStatus.PUBLISHED }, rest.count { it.state.status == RibbonStatus.NOT_ENOUGH })
            }
        }
    }
    Crossfade(targetState = view.key, animationSpec = tween(150), label = "next") { key ->
        // The outgoing card of a cross-fade only needs to look like itself for 150 ms.
        val v = view.takeIf { it.key == key }
            ?: b.items.firstOrNull { it.anchor.id == key }?.let { AheadView(it, w.ahead(0.0), 0, 0) }
            ?: AheadView(null, null, if (key == "none") 0 else 1, if (key == "none") 1 else 0)
        if (v.item != null) PlaceCard(v.item, w, t, ahead = v.distance ?: w.ahead(0.0)) else Verdict(v.judged, v.notJudged, rest = true, w, t)
    }
}

/** Now, to the half minute: enough for "last car 10 min ago" to stay true while the screen sits. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    return now
}

/**
 * One place: status shape, facility and what cars did, as icons; what that may mean, as the headline; then the evidence:
 * how many cars against the usual on the same scale, and how recent the last car is. In the preview the distance leads.
 */
@Composable
private fun PlaceCard(item: BriefingItem, w: Words, t: Tokens, ahead: Pair<String, String>?) {
    val metric = Words.top(item)?.metric
    val now = rememberNow()
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = w.describe(item) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            StatusGlyph(item.state.deviation, 52.dp, t, t.surface)
            FacilityIcon(item.anchor.type, 52.dp, t.ink)
            IssueIcon(metric, 52.dp, t.ink)
        }
        Spacer(Modifier.width(24.dp))
        Column {
            if (ahead != null) {
                val (num, unit) = ahead
                Row {
                    Text(num, style = Type.driveDistance.copy(fontSize = 88.sp, lineHeight = 92.sp, fontFeatureSettings = "tnum"), color = t.ink, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(12.dp))
                    Text(unit, style = Type.driveUnit, color = t.ink2, modifier = Modifier.alignByBaseline())
                }
            }
            Text(w.meaning(item), style = Type.headline, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                // A segment's name already is its kilometres.
                if (ahead == null && item.anchor.type != AnchorType.SEGMENT) "${w.place(item)} · ${Words.at(item.pointM)}" else w.place(item),
                style = Type.context.copy(fontFeatureSettings = "tnum"), color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (Words.isCars(item)) {
                    IconBox(36.dp) { c, sz -> carIcon(c, sz, t.ink) }
                    Spacer(Modifier.width(8.dp))
                }
                Text(w.count(item), style = Type.headline.copy(fontFeatureSettings = "tnum"), color = t.ink)
                Spacer(Modifier.width(16.dp))
                BulletBar(item, 200.dp, t)
                w.usual(item)?.let { usual ->
                    Spacer(Modifier.width(12.dp))
                    UsualTick(t)
                    Spacer(Modifier.width(6.dp))
                    Text(usual, style = Type.context.copy(fontFeatureSettings = "tnum"), color = t.ink2)
                }
            }
            item.state.window?.lastMs?.let { last ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(24.dp) { c, sz -> clockIcon(c, sz, t.ink2) }
                    Spacer(Modifier.width(8.dp))
                    Text(w.lastCar(last, now), style = Type.context.copy(fontFeatureSettings = "tnum"), color = t.ink2)
                }
            }
        }
    }
}

/**
 * What to say when nothing is different. A place with too few cars is never folded into "as usual": with no place
 * judged the card says so with the hollow mark, and otherwise it counts the places it could not judge.
 */
@Composable
private fun Verdict(judged: Int, notJudged: Int, rest: Boolean, w: Words, t: Tokens) {
    Column {
        if (judged == 0 && (notJudged > 0 || !rest)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusGlyph(Deviation.NONE, 52.dp, t, t.surface, notEnough = true)
                Spacer(Modifier.width(20.dp))
                Text(w.str(if (rest) R.string.none_judged_ahead else R.string.none_judged), style = Type.headline, color = t.ink2)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(52.dp)) {
                    val sw = 6.dp.toPx()
                    drawLine(t.ink2, Offset(size.width * 0.15f, size.height * 0.55f), Offset(size.width * 0.42f, size.height * 0.8f), sw)
                    drawLine(t.ink2, Offset(size.width * 0.42f, size.height * 0.8f), Offset(size.width * 0.88f, size.height * 0.22f), sw)
                }
                Spacer(Modifier.width(20.dp))
                Text(if (rest) w.str(R.string.nothing_ahead) else w.plural(R.plurals.all_usual, judged), style = Type.headline, color = t.ink2)
            }
            if (notJudged > 0) {
                Spacer(Modifier.height(16.dp))
                NotJudged(notJudged, w, t)
            }
        }
    }
}

@Composable
private fun NotJudged(n: Int, w: Words, t: Tokens) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusGlyph(Deviation.NONE, 32.dp, t, t.surface, notEnough = true)
        Spacer(Modifier.width(8.dp))
        Text(w.str(R.string.not_judged, n), style = Type.caption, color = t.ink2)
    }
}

/** How many places are different, by level, as marks with numbers; then the places not judged, with their own mark. */
@Composable
private fun Counts(b: Briefing, w: Words, t: Tokens) {
    val high = b.items.count { it.different && it.state.deviation == Deviation.HIGH }
    val some = b.items.count { it.different && it.state.deviation == Deviation.SOME }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (high > 0) CountMark(high, t) { StatusGlyph(Deviation.HIGH, 32.dp, t, t.surface) }
        if (some > 0) CountMark(some, t) { StatusGlyph(Deviation.SOME, 32.dp, t, t.surface) }
        if (b.notEnoughCount > 0) NotJudged(b.notEnoughCount, w, t)
    }
}

@Composable
private fun CountMark(n: Int, t: Tokens, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(8.dp))
        Text("$n", style = Type.title.copy(fontFeatureSettings = "tnum"), color = t.ink)
    }
}

/** This time as a filled bar (in the status colour), the usual share as a tick across it. */
@Composable
private fun BulletBar(item: BriefingItem, width: Dp, t: Tokens) {
    val (now, usual) = Words.shares(item) ?: return
    val fill = if (item.state.deviation == Deviation.HIGH) t.high else if (item.state.deviation == Deviation.SOME) t.some else t.muted
    Canvas(Modifier.size(width = width, height = 24.dp)) {
        val track = 12.dp.toPx()
        val y = (size.height - track) / 2
        drawRoundRect(t.grid, Offset(0f, y), Size(size.width, track), androidx.compose.ui.geometry.CornerRadius(track / 2))
        if (now > 0f) drawRoundRect(fill, Offset(0f, y), Size(maxOf(track, size.width * now), track), androidx.compose.ui.geometry.CornerRadius(track / 2))
        if (usual != null) {
            val x = (size.width * usual).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
            drawLine(t.surface, Offset(x, 0f), Offset(x, size.height), 5.dp.toPx())
            drawLine(t.ink, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}

@Composable
private fun UsualTick(t: Tokens) = Canvas(Modifier.size(width = 6.dp, height = 24.dp)) {
    drawLine(t.ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
}

/**
 * The legend: how the road is drawn and what the marks mean. Only kinds on screen are listed. This is the one place
 * that spells out the icons.
 */
@Composable
private fun Legend(b: Briefing, curves: Set<CurveClass>, hairpins: Boolean, w: Words, t: Tokens) {
    val road = buildList {
        add(LegendKind.STRAIGHT to w.str(R.string.legend_straight))
        if (CurveClass.CURVE in curves) add(LegendKind.CURVE to w.str(R.string.legend_curve))
        if (CurveClass.SHARP in curves) add(LegendKind.SHARP to w.str(R.string.legend_sharp))
        if (hairpins) add(LegendKind.HAIRPIN to w.str(R.string.legend_hairpin))
        add(LegendKind.TUNNEL to w.str(R.string.legend_tunnel))
    }
    val marks = buildList {
        if (b.items.any { it.different && it.state.deviation == Deviation.HIGH }) add(LegendKind.HIGH to w.str(R.string.legend_high))
        if (b.items.any { it.different && it.state.deviation == Deviation.SOME }) add(LegendKind.SOME to w.str(R.string.legend_some))
        // The usual tick is drawn for facilities only; a usual segment leaves the road as it is.
        if (b.items.any { it.state.status == RibbonStatus.PUBLISHED && !it.different && it.anchor.type != AnchorType.SEGMENT }) add(LegendKind.USUAL to w.str(R.string.legend_usual))
        if (b.notEnoughCount > 0) add(LegendKind.NOT_ENOUGH to w.str(R.string.legend_not_enough))
    }
    // One entry per icon: fog lights switched on (at a tunnel) and on (on a segment) share the icon and the words.
    val issues = b.items.filter { it.different }.mapNotNull { Words.top(it)?.metric }.distinctBy { w.issue(it) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendRow(t) { for ((k, label) in road) LegendEntry(label, t) { LegendSwatch(k, t) } }
        if (marks.isNotEmpty()) LegendRow(t) { for ((k, label) in marks) LegendEntry(label, t) { LegendSwatch(k, t) } }
        if (issues.isNotEmpty()) LegendRow(t) {
            for (m in issues) LegendEntry(w.issue(m), t) { IssueIcon(m, 28.dp, t.ink2) }
        }
    }
}

/** One legend row, wrapping instead of running out over the map. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendRow(t: Tokens, content: @Composable () -> Unit) =
    FlowRow(Modifier.widthIn(max = LegendWidth), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }

private val LegendWidth = 680.dp

@Composable
private fun LegendEntry(label: String, t: Tokens, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.caption, color = t.ink2)
    }
}

/**
 * Every different place as a row: icons, what it may mean over where it is, cars against the usual (the bar's tick, as on
 * the card) and the position. The table view of the map, readable without colour.
 */
@Composable
private fun PlaceList(rows: List<BriefingItem>, w: Words, t: Tokens) {
    if (rows.isEmpty()) return
    Column {
        for (item in rows) {
            val metric = Words.top(item)?.metric
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
            Row(
                Modifier.height(76.dp).semantics(mergeDescendants = true) { contentDescription = w.describe(item) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusGlyph(item.state.deviation, 28.dp, t, t.surface)
                Spacer(Modifier.width(12.dp))
                FacilityIcon(item.anchor.type, 30.dp, t.ink2)
                Spacer(Modifier.width(8.dp))
                IssueIcon(metric, 30.dp, t.ink)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.width(300.dp)) {
                    Text(w.meaning(item), style = Type.body, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(w.place(item), style = Type.caption, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (Words.isCars(item)) {
                        IconBox(26.dp) { c, sz -> carIcon(c, sz, t.ink2) }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(w.count(item), style = Type.bodyNum, color = t.ink, maxLines = 1)
                }
                Spacer(Modifier.width(16.dp))
                BulletBar(item, 120.dp, t)
                Text(Words.at(item.pointM), style = Type.bodyNum, color = t.ink2, modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
    }
}

@Composable
private fun SimChip(t: Tokens, w: Words) {
    Row(
        Modifier.border(1.dp, t.ink2.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            drawCircle(t.ink2, size.minDimension / 2, style = Stroke(2.dp.toPx()))
            drawCircle(t.ink2, size.minDimension / 6)
        }
        Spacer(Modifier.width(8.dp))
        Text(w.str(R.string.sim_data), style = Type.caption, color = t.ink2)
    }
}

// ---------------------------------------------------------------- while driving

/**
 * While the car moves the screen only says that this car's agent is leaving knots for the cars behind. No map and no
 * position: the navigation guides, this app records.
 */
@Composable
private fun RecordingScreen(vm: RibbonViewModel, s: ChannelState, restricted: Boolean, t: Tokens, w: Words) {
    Column(Modifier.fillMaxSize().padding(Gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(160.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            // Still on purpose: nothing on this screen moves while the car does.
            drawCircle(t.ink2, r * 0.62f, c, style = Stroke(3.dp.toPx()))
            drawCircle(t.ink, r * 0.3f, c)
        }
        Spacer(Modifier.height(32.dp))
        Text(w.str(R.string.recording), style = Type.headline, color = t.ink)
        Spacer(Modifier.height(8.dp))
        Text(w.str(R.string.recording_count, s.myKnots), style = Type.context.copy(fontFeatureSettings = "tnum"), color = t.ink2)
        Spacer(Modifier.height(24.dp))
        Text(w.str(R.string.recording_hint), style = Type.caption, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
        if (!restricted) {
            Spacer(Modifier.height(40.dp))
            OutlinedButton(onClick = { vm.channel.stopRecording() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                IconBox(24.dp) { c, sz -> stopIcon(c, sz, t.ink) }
                Spacer(Modifier.width(12.dp))
                Text(w.str(R.string.preview_stop), style = Type.button, color = t.ink)
            }
        }
    }
}

// ---------------------------------------------------------------- demo sheet

private val PrettyJson = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }

@Composable
private fun DemoSheet(vm: RibbonViewModel, s: ChannelState, t: Tokens, w: Words, onClose: () -> Unit, modifier: Modifier) {
    val ch = vm.channel
    val signals by ch.vehicle.signals.collectAsState()
    val vStatus by ch.vehicle.status.collectAsState()
    val lStatus by ch.location.status.collectAsState()
    val nStatus by ch.navi.status.collectAsState()
    var details by remember { mutableStateOf(false) }
    Row(modifier.fillMaxHeight().width(520.dp)) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(t.hairline))
        Column(Modifier.fillMaxSize().background(t.page).padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.str(R.string.demo), style = Type.title, color = t.ink, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) { Text(w.str(R.string.close), style = Type.button, color = t.ink) }
            }
            Text(w.str(R.string.demo_hint), style = Type.caption, color = t.ink2)
            Column {
                for (route in vm.data.routes) {
                    val (from, to) = w.ends(route.anchorSet)
                    // Scenarios are grouped under the route they drive; picking one also puts its route on screen.
                    Text("$from → $to · ${Words.at(route.anchorSet.lengthM)}", style = Type.caption, color = t.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    for (sc in vm.data.scenarios.filter { it.routeId == route.id }) {
                        val selected = s.scenarioId == sc.id
                        Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
                        Row(
                            Modifier.fillMaxWidth().height(Touch).clickable(enabled = !s.replaying) { ch.runScenario(sc.id); onClose() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 12.dp).background(if (selected) t.ink else t.page))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ch.scenarioTitle(sc.id), style = Type.caption, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(w.str(R.string.sim_cars, sc.vehicles.size), style = Type.caption, color = t.muted)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
            }
            if (s.replaying) {
                Text(w.str(R.string.sim_passing, s.replayDone, s.replayTotal), style = Type.caption, color = t.ink2)
                LinearProgressIndicator(progress = { s.replayDone / s.replayTotal.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = t.ink, trackColor = t.grid)
            } else {
                Text(if (s.knotCount > 0) w.str(R.string.knots, s.knotCount) else w.str(R.string.no_knots), style = Type.caption, color = t.ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { ch.clear() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) { Text(w.str(R.string.clear), style = Type.button, color = t.ink) }
                OutlinedButton(onClick = { ch.startRecording(); onClose() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) { Text(w.str(R.string.record_live), style = Type.button, color = t.ink) }
            }
            Text(if (details) w.str(R.string.details_hide) else w.str(R.string.details), style = Type.caption, color = t.ink2, modifier = Modifier.clickable { details = !details }.padding(vertical = 8.dp))
            if (details) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentIo(s.lastKnot, s.briefing.headline?.state, t, w)
                    Spacer(Modifier.height(8.dp))
                    for (line in s.log) Text(line, style = Type.caption, color = t.muted)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(w.str(R.string.connection), style = Type.title, color = t.ink)
            PortRow(w.str(R.string.port_vehicle), vStatus, t)
            PortRow(w.str(R.string.port_location), lStatus, t)
            PortRow(w.str(R.string.port_navi), nStatus, t)
            SignalGrid(signals, t, w)
        }
    }
}

/** The agent layer made visible for the demo: the last knot written and the ribbon an agent reads, as the JSON itself. */
@Composable
private fun AgentIo(knot: Knot?, ribbon: RibbonState?, t: Tokens, w: Words) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 20.sp)
    Text(w.str(R.string.agent_io), style = Type.title, color = t.ink)
    Text(w.str(R.string.agent_endpoint), style = Type.caption, color = t.ink2)
    Text(w.str(R.string.agent_written), style = Type.caption, color = t.ink2)
    Text(knot?.let { PrettyJson.encodeToString(Knot.serializer(), it) } ?: w.str(R.string.agent_none), style = mono, color = t.ink,
        modifier = Modifier.fillMaxWidth().background(t.surface).padding(12.dp))
    Text(w.str(R.string.agent_read), style = Type.caption, color = t.ink2)
    Text(ribbon?.let { PrettyJson.encodeToString(RibbonState.serializer(), it) } ?: w.str(R.string.agent_none), style = mono, color = t.ink,
        modifier = Modifier.fillMaxWidth().background(t.surface).padding(12.dp))
}

@Composable
private fun PortRow(name: String, s: PortStatus, t: Tokens) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.live) "✓" else "–", style = Type.caption, color = if (s.live) t.good else t.muted, modifier = Modifier.width(28.dp))
        Text(name, style = Type.caption, color = t.ink, modifier = Modifier.width(140.dp))
        Text(s.note ?: s.source, style = Type.caption, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignalGrid(s: VehicleSignals, t: Tokens, w: Words) {
    val on = w.str(R.string.on); val off = w.str(R.string.off)
    val turn = when (s.turnSignal) { TurnSignal.LEFT -> w.str(R.string.left); TurnSignal.RIGHT -> w.str(R.string.right); TurnSignal.OFF -> off }
    val gear = when (s.gear) { Gear.PARK -> "P"; Gear.DRIVE -> "D"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; else -> "—" }
    val rows = listOf(
        w.str(R.string.sig_gear) to gear, w.str(R.string.sig_speed) to (s.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"),
        w.str(R.string.sig_brake) to if (s.brake) w.str(R.string.pressed) else off, w.str(R.string.sig_turn) to turn,
        w.str(R.string.sig_wheel) to Words.degrees(s.wheelAngleDeg), w.str(R.string.sig_wiper) to if (s.wiper) on else off,
        w.str(R.string.sig_fog) to if (s.fogLight) on else off,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.chunked(2).forEach { pair ->
            Row {
                pair.forEach { (k, v) ->
                    Row(Modifier.weight(1f)) {
                        Text(k, style = Type.caption, color = t.muted, modifier = Modifier.width(130.dp))
                        Text(v, style = Type.captionNum, color = t.ink)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
