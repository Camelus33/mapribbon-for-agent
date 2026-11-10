package kr.tedin.maptalkdrive.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import kr.tedin.maptalkdrive.ribbon.brief.Cautions
import kr.tedin.maptalkdrive.ribbon.brief.Caution
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.channel.ChannelState
import kr.tedin.maptalkdrive.channel.LivePhase
import kr.tedin.maptalkdrive.demo.RibbonViewModel
import kr.tedin.maptalkdrive.demo.WeaveFrame
import kr.tedin.maptalkdrive.ribbon.brief.Weave
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.geo.CurveClass
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.MatchVerdict
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kr.tedin.maptalkdrive.ribbon.spec.RouteSource

private val Gutter = 48.dp
/** The left part of the briefing: the words. The map takes the rest. */
private val SidePanel = 600.dp
private val Touch = 76.dp

/**
 * Before departure the app shows the route and what the ribbons on it say, and can replay how those ribbons tied. While
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
    val weave = vm.weave.collectAsState()
    var sheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(restricted) { if (restricted) { vm.stopPreview(); vm.stopWeave(); sheet = false } }
    Box(Modifier.fillMaxSize().background(t.surface)) {
        if (restricted || s.recording) {
            RecordingScreen(vm, s, restricted, t, w)
        } else {
            BriefingScreen(vm, s, preview, weave, t, w, onOpenDemo = { sheet = true })
            if (sheet) DemoPanel(vm, s, t, w, onClose = { sheet = false }, Modifier.align(Alignment.CenterEnd))
        }
    }
}

// ---------------------------------------------------------------- before departure

private val ElevationHeight = 260.dp

@Composable
private fun BriefingScreen(
    vm: RibbonViewModel, s: ChannelState, preview: State<Double?>, weave: State<WeaveFrame?>, t: Tokens, w: Words, onOpenDemo: () -> Unit,
) {
    val running by remember { derivedStateOf { preview.value != null } }
    val weaving by remember { derivedStateOf { weave.value != null } }
    // Until the replay reaches its verdict the map and the height strip show the route untied.
    val untied by remember { derivedStateOf { weave.value.untied() } }
    val r = vm.channel.routeData(s.routeId)
    val set = r.anchorSet
    val b = s.briefing
    val (start, end) = w.ends(set)
    // The navigation's route on screen: where it came from, whether it is the navigation's line, how fresh its ribbons are.
    val live = s.live
    val onLive = live != null && live.routeId == s.routeId
    // Where to take care, ranked; the map numbers them the same way.
    val cautions = remember(b, r) { Cautions.build(b, r.curves, r.hairpins, System.currentTimeMillis()) }
    val chips = remember(cautions, w) {
        cautions.map { c -> ChipSpec(c.rank, c.metric, w.count(b.items.first { it.anchor.id == c.leadId }), c.pointM, c.deviation) }
    }
    // How the ribbons on screen tied, replayed from their counts. A replay of other ribbons is dropped.
    val plan = remember(b, r, cautions) { Weave.plan(b, cautions, r.route.lengthM, r.elevation?.highestAtM) }
    LaunchedEffect(plan) { vm.dropWeaveUnless(plan) }
    // The replay being drawn; this changes when a replay starts or ends, not on every frame.
    val weavePlan by remember { derivedStateOf { weave.value?.plan } }
    Column(Modifier.fillMaxSize().padding(start = Gutter, end = Gutter, top = 32.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$start → $end · ${Words.at(set.lengthM)}", style = Type.context, color = t.ink2)
                val ageMin = live?.ribbonsAtMs?.let { ((System.currentTimeMillis() - it) / 60_000).toInt() }
                val status = when {
                    s.replaying -> w.str(R.string.sim_passing, s.replayDone, s.replayTotal)
                    live?.phase == LivePhase.LOADING -> w.str(R.string.live_loading)
                    live?.phase == LivePhase.FAILED -> w.liveError(live.error)
                    onLive && ageMin != null && (live!!.stale || ageMin >= 2) -> w.str(R.string.ribbons_stale, ageMin)
                    s.naviNotice != null -> s.naviNotice
                    else -> null
                }
                if (status != null) Text(status, style = Type.caption, color = t.ink2)
            }
            if (live?.phase == LivePhase.FAILED) {
                OutlinedButton(onClick = { vm.channel.retryLive() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                    Text(w.str(R.string.live_retry), style = Type.button, color = t.ink)
                }
                Spacer(Modifier.width(16.dp))
            }
            if (onLive) {
                PillChip(w.str(if (live!!.source == RouteSource.TEST) R.string.live_source_test else R.string.live_source_navi), null, t)
                Spacer(Modifier.width(12.dp))
                live.match?.let { m ->
                    PillChip(w.match(m), if (m.verdict == MatchVerdict.DIFFERENT) "≠" else if (m.verdict == MatchVerdict.SAME) "=" else null, t)
                    Spacer(Modifier.width(12.dp))
                }
            }
            // Ribbons on a bundled route always come from simulated cars; on the navigation's route only when the server says so.
            if (!onLive || live!!.simulated) {
                SimChip(t, w)
                Spacer(Modifier.width(16.dp))
            }
            OutlinedButton(onClick = onOpenDemo, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                Text(w.str(R.string.demo), style = Type.button, color = t.ink)
            }
        }
        // Two parts that never overlap: the words on the left (where to take care, the legend, every place), the map on
        // the right, where the road runs from bottom-left (start) to top-right (destination). Text over the road makes
        // both unreadable, so the map has its own area and nothing is drawn over it but its own chips.
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val different = b.items.filter { it.different }.sortedWith(compareByDescending<BriefingItem> { it.state.deviation.ordinal }.thenBy { it.pointM })
            val bare = remember(b) { b.untied() }
            val shown = if (untied) bare else b
            val weaveLabels = remember(w) { WeaveLabels(w.str(R.string.weave_threshold_mark)) { k, n -> w.str(R.string.count_cars, k, n) } }
            Column(Modifier.width(SidePanel).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 20.dp, end = 32.dp, bottom = 12.dp)) {
                if (running) {
                    PreviewCard(b, preview, w, t)
                } else if (weaving) {
                    // The cold line on the height strip is the same place seen from below, when there is one.
                    val coldHint = plan.metric == Metric.ICE_RISK_RATE && r.elevation != null && tempRef(b) != null
                    WeaveCard(weavePlan ?: plan, weave, b, coldHint, w, t)
                } else if (cautions.isNotEmpty()) {
                    CautionList(cautions, b, w, t)
                    if (b.notEnoughCount > 0) {
                        Spacer(Modifier.height(16.dp))
                        NotJudged(b.notEnoughCount, w, t)
                    }
                } else {
                    Verdict(b.items.count { it.state.status == RibbonStatus.PUBLISHED }, b.notEnoughCount, rest = false, w, t)
                }
                Spacer(Modifier.height(24.dp))
                Legend(b, r.curves.map { it.curve }.toSet(), r.hairpins.isNotEmpty(), w, t)
                if (!running && !weaving && different.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    AllPlaces(different, w, t)
                }
            }
            // The road's height sits under the map on the same kilometres, when the route has heights.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                RouteMap(
                    shown, r.route, r.curves, r.hairpins, start, end, t,
                    reserved = emptyList(), preview = preview, chipSpecs = if (untied) emptyList() else chips,
                    weave = weave, weaveLabels = weaveLabels, modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                r.elevation?.let { ElevationStrip(it, shown, t, w, preview, Modifier.fillMaxWidth().height(ElevationHeight)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val note = when {
                running -> w.str(R.string.preview_desc)
                weaving -> w.str(R.string.weave_desc)
                else -> null
            }
            Text(
                note ?: set.attribution,
                style = if (note != null) Type.caption else Type.legal, color = if (note != null) t.ink2 else t.muted,
                modifier = Modifier.weight(1f),
            )
            when {
                running -> OutlinedButton(onClick = { vm.stopPreview() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                    IconBox(24.dp) { c, sz -> stopIcon(c, sz, t.ink) }
                    Spacer(Modifier.width(12.dp))
                    Text(w.str(R.string.preview_stop), style = Type.button, color = t.ink)
                }
                weaving -> OutlinedButton(onClick = { vm.stopWeave() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                    Text(w.str(R.string.weave_close), style = Type.button, color = t.ink, modifier = Modifier.padding(horizontal = 8.dp))
                }
                else -> {
                    // A replay needs cars: on a route nobody drove yet there is nothing to tie.
                    Button(
                        onClick = { vm.startWeave(plan) },
                        enabled = plan.cars > 0,
                        modifier = Modifier.height(Touch),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = t.actionBg, contentColor = t.actionInk),
                    ) {
                        IconBox(24.dp) { c, sz -> playIcon(c, sz, if (plan.cars > 0) t.actionInk else t.muted) }
                        Spacer(Modifier.width(12.dp))
                        Text(w.str(R.string.weave_run), style = Type.button, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
    }
}

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
    Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = w.describe(item) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(item.state.deviation, 48.dp, t, t.surface)
            FacilityIcon(item.anchor.type, 48.dp, t.ink)
            IssueIcon(metric, 48.dp, t.ink)
        }
        Spacer(Modifier.height(8.dp))
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
                BulletBar(item, 160.dp, t)
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

/**
 * Where to take care on this route, at most three, most costly first. The first is said in full: its number (in its
 * status colour, as on the map), what it may be and where, why it weighs (a tunnel exit, hairpins or sharp curves), the
 * evidence (cars, last car) and what a careful driver does there. The second and third are one line each, number, icon,
 * what and where, and open in full when touched (one at a time): three full cautions are more than a glance can take.
 */
@Composable
private fun CautionList(cautions: List<Caution>, b: Briefing, w: Words, t: Tokens) {
    val now = rememberNow()
    var open by remember(cautions) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(w.str(R.string.caution_title), style = Type.caption, color = t.ink2)
        for (c in cautions) {
            val lead = b.items.first { it.anchor.id == c.leadId }
            when {
                c.rank == 1 -> CautionRow(c, lead, w, t, now, onToggle = null)
                open == c.rank -> CautionRow(c, lead, w, t, now, onToggle = { open = null })
                else -> CautionLine(c, lead, w, t, onOpen = { open = c.rank })
            }
        }
    }
}

/** A single facility is named; a stretch (or a facility with the segments after it) is its kilometres. */
private fun whereOf(c: Caution, lead: BriefingItem, w: Words): String =
    if (c.anchorIds.size == 1 && lead.anchor.type != AnchorType.SEGMENT) "${w.place(lead)} · ${Words.at(c.pointM)}"
    else if (c.fromM == c.toM) Words.at(c.fromM) else "%.1f–%.1f km".format(c.fromM / 1000.0, c.toM / 1000.0)

/** A caution folded to one line: number, icon, what and where, and a chevron that says it opens. */
@Composable
private fun CautionLine(c: Caution, lead: BriefingItem, w: Words, t: Tokens, onOpen: () -> Unit) {
    val where = whereOf(c, lead, w)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${c.rank}. ${w.reading(c.reading)}, $where" }
            .padding(end = 12.dp),
    ) {
        RankBadge(c.rank, c.deviation, t)
        Spacer(Modifier.width(14.dp))
        IssueIcon(c.metric, 30.dp, t.ink)
        Spacer(Modifier.width(10.dp))
        Text(w.reading(c.reading), style = Type.title, color = t.ink, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        // The place gives way (ellipsis) before the chevron does: the chevron is what says the line opens.
        Text(where, style = Type.caption.copy(fontFeatureSettings = "tnum"), color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        IconBox(24.dp) { o, sz -> chevronIcon(o, sz, t.ink2, up = false) }
    }
}

@Composable
private fun CautionRow(c: Caution, lead: BriefingItem, w: Words, t: Tokens, now: Long, onToggle: (() -> Unit)?) {
    val where = whereOf(c, lead, w)
    val actions = c.actions.joinToString(" · ") { w.action(it) }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "${c.rank}. ${w.reading(c.reading)}, $where, ${w.describe(lead)}. $actions"
            }
            .padding(end = 12.dp, bottom = 4.dp),
    ) {
        RankBadge(c.rank, c.deviation, t)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IssueIcon(c.metric, 36.dp, t.ink)
                Spacer(Modifier.width(10.dp))
                Text(w.reading(c.reading), style = CautionTitle, color = t.ink, maxLines = 1)
                Spacer(Modifier.width(14.dp))
                Text(where, style = Type.context.copy(fontFeatureSettings = "tnum"), color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (onToggle != null) {
                    Spacer(Modifier.width(10.dp))
                    IconBox(24.dp) { o, sz -> chevronIcon(o, sz, t.ink2, up = true) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Why it weighs: met at a tunnel exit, on hairpins or sharp curves.
                if (c.tunnelExit) { FacilityIcon(AnchorType.TUNNEL, 26.dp, t.ink2); Spacer(Modifier.width(6.dp)) }
                if (c.hairpins > 0) { IconBox(26.dp) { o, sz -> hairpinIcon(o, sz, t.ink2) }; Spacer(Modifier.width(6.dp)) }
                else if (c.onSharpCurves) { IconBox(26.dp) { o, sz -> sharpCurveIcon(o, sz, t.ink2) }; Spacer(Modifier.width(6.dp)) }
                if (c.tunnelExit || c.hairpins > 0 || c.onSharpCurves) Spacer(Modifier.width(8.dp))
                if (Words.isCars(lead)) {
                    IconBox(24.dp) { o, sz -> carIcon(o, sz, t.ink2) }
                    Spacer(Modifier.width(4.dp))
                }
                Text(w.count(lead), style = Type.caption.copy(fontFeatureSettings = "tnum"), color = t.ink2)
                c.lastMs?.let { last ->
                    Spacer(Modifier.width(14.dp))
                    IconBox(20.dp) { o, sz -> clockIcon(o, sz, t.ink2) }
                    Spacer(Modifier.width(4.dp))
                    Text(w.lastCar(last, now), style = Type.caption.copy(fontFeatureSettings = "tnum"), color = t.ink2)
                }
            }
            Text("→ $actions", style = Type.caption, color = t.ink)
        }
    }
}

private val CautionTitle = Type.headline.copy(fontSize = 30.sp, lineHeight = 38.sp)

/** The caution's number in a disc of its status colour: clearly different (orange) or somewhat (yellow). */
@Composable
private fun RankBadge(rank: Int, d: Deviation, t: Tokens) {
    Box(
        Modifier.padding(top = 2.dp).size(40.dp).clip(CircleShape).background(if (d == Deviation.HIGH) t.high else t.some),
        contentAlignment = Alignment.Center,
    ) { Text("$rank", style = Type.title.copy(fontFeatureSettings = "tnum"), color = t.onStatus) }
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
 * The legend: how the road is drawn and what the marks mean. Only kinds on screen are listed. The icons of what was
 * different need no entry: everywhere they appear, their words stand next to them.
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendRow(t) { for ((k, label) in road) LegendEntry(label, t) { LegendSwatch(k, t) } }
        if (marks.isNotEmpty()) LegendRow(t) { for ((k, label) in marks) LegendEntry(label, t) { LegendSwatch(k, t) } }
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
                Modifier.fillMaxWidth().height(68.dp).semantics(mergeDescendants = true) { contentDescription = w.describe(item) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusGlyph(item.state.deviation, 26.dp, t, t.surface)
                Spacer(Modifier.width(10.dp))
                IssueIcon(metric, 28.dp, t.ink)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.meaning(item), style = Type.body, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(w.place(item), style = Type.caption, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                if (Words.isCars(item)) {
                    IconBox(24.dp) { c, sz -> carIcon(c, sz, t.ink2) }
                    Spacer(Modifier.width(4.dp))
                }
                Text(w.count(item), style = Type.bodyNum, color = t.ink, maxLines = 1)
                Text(Words.at(item.pointM), style = Type.bodyNum, color = t.ink2, modifier = Modifier.width(100.dp), textAlign = TextAlign.End)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
    }
}

/** Every different place, folded behind one line: the cautions above already say the ones that matter most. */
@Composable
private fun AllPlaces(rows: List<BriefingItem>, w: Words, t: Tokens) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(w.str(R.string.all_places, rows.size), style = Type.caption, color = t.ink2, modifier = Modifier.weight(1f))
            IconBox(24.dp) { c, sz -> chevronIcon(c, sz, t.ink2, up = open) }
        }
        if (open) PlaceList(rows, w, t)
    }
}

/** A label in a pill, the shape [SimChip] uses, with an optional leading glyph ("=" same, "≠" may differ). */
@Composable
private fun PillChip(text: String, glyph: String?, t: Tokens) {
    Row(
        Modifier.border(1.dp, t.ink2.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Text(glyph, style = Type.caption, color = t.ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.caption, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

