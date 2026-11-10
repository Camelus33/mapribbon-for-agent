package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.channel.ChannelState
import kr.tedin.maptalkdrive.demo.RibbonViewModel
import kr.tedin.maptalkdrive.demo.RouteData
import kr.tedin.maptalkdrive.port.Gear
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.port.VehicleSignals
import kr.tedin.maptalkdrive.channel.LivePhase
import kr.tedin.maptalkdrive.demo.NaviPresets
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.demo.StoryBeat
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kr.tedin.maptalkdrive.ribbon.spec.WiperLevel
import kotlin.math.cos
import kotlin.math.sin

private val PanelWidth = 600.dp
private val PanelTouch = 76.dp
private val Card = RoundedCornerShape(12.dp)
private val PrettyJson = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }

/**
 * The demo panel, said in pictures. Each scenario is a small map of where its cars do what, with the icons and counts
 * of the story it tells; the replay is a row of cars filling in; the car's own signals are telltales that light up;
 * the agent channel is a flow from the cars to the agents that read it. Words are left for names and accessibility.
 */
@Composable
fun DemoPanel(vm: RibbonViewModel, s: ChannelState, t: Tokens, w: Words, onClose: () -> Unit, modifier: Modifier) {
    val ch = vm.channel
    val signals by ch.vehicle.signals.collectAsState()
    val vStatus by ch.vehicle.status.collectAsState()
    val lStatus by ch.location.status.collectAsState()
    val nStatus by ch.navi.status.collectAsState()
    var details by remember { mutableStateOf(false) }
    var showSameBundled by remember { mutableStateOf(false) }
    Row(modifier.fillMaxHeight().width(PanelWidth)) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(t.hairline))
        Column(
            Modifier.fillMaxSize().background(t.page).verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.str(R.string.demo), style = Type.title, color = t.ink, modifier = Modifier.weight(1f))
                IconTap(w.str(R.string.close), onClose) { c, sz -> closeIcon(c, sz, t.ink) }
            }
            // Navigation routes typed in as tests (the emulator's navigation does not answer), the Jeju trips.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(w.str(R.string.demo_navi_presets), style = Type.caption, color = t.muted)
                for (p in NaviPresets.all) {
                    PanelButton(w.str(p.label), t, enabled = !s.replaying, onClick = { ch.navi.inject(p.route); onClose() }) { c, sz -> playIcon(c, sz, t.ink) }
                }
            }
            // The navigation's route, when one is set: its demo scenarios, or back to it from a bundled route.
            val preset = ch.livePreset()
            val presetOnScreen = preset?.takeIf { s.live?.routeId == s.routeId && s.live?.phase == LivePhase.READY }
            s.live?.routeId?.let { id ->
                val lr = ch.routeData(id)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (from, to) = w.ends(lr.anchorSet)
                    Text(w.str(R.string.demo_live_route) + " · $from → $to · ${Words.at(lr.anchorSet.lengthM)}", style = Type.context, color = t.ink)
                    if (s.routeId != id) PanelButton(w.str(R.string.demo_live_show), t, onClick = { ch.selectRoute(id); onClose() }) { c, sz -> playIcon(c, sz, t.ink) }
                    if (presetOnScreen != null) {
                        Text(w.str(R.string.demo_live_scenarios), style = Type.caption, color = t.ink2)
                        val bundled = vm.data.route(presetOnScreen.bundledRouteId)
                        for (sc in vm.data.shownScenarios.filter { it.routeId == presetOnScreen.bundledRouteId }) {
                            ScenarioCard(
                                ch.scenarioTitle(sc.id), sc, bundled, selected = s.scenarioId == sc.id, enabled = !s.replaying, t, w,
                                action = w.str(R.string.demo_send_cars),
                            ) { ch.runScenarioOnLive(sc.id); onClose() }
                        }
                    }
                }
            }
            for (route in vm.data.routes) {
                val scenarios = vm.data.shownScenarios.filter { it.routeId == route.id }
                if (scenarios.isEmpty()) continue
                // While the navigation's route from a preset is on screen, the same trip's bundled cards would show the
                // same titles twice; they fold away behind one line, so a card is never tapped for the wrong route.
                val same = presetOnScreen?.bundledRouteId == route.id
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (from, to) = w.ends(route.anchorSet)
                    Text(w.str(R.string.demo_bundled) + " · $from → $to · ${Words.at(route.anchorSet.lengthM)}", style = Type.caption, color = t.muted)
                    if (same && !showSameBundled) {
                        PanelButton(w.str(R.string.demo_show_bundled), t, onClick = { showSameBundled = true }) { c, sz -> chevronIcon(c, sz, t.ink, up = false) }
                    } else {
                        for (sc in scenarios) {
                            ScenarioCard(ch.scenarioTitle(sc.id), sc, route, selected = s.routeId == route.id && s.scenarioId == sc.id, enabled = !s.replaying, t, w) {
                                ch.runScenario(sc.id); onClose()
                            }
                        }
                    }
                }
            }
            if (s.replaying || s.knotCount > 0) ReplayStrip(s, t, w)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelButton(w.str(R.string.clear), t, enabled = !s.replaying, onClick = { ch.clear() }) { c, sz -> resetIcon(c, sz, t.ink) }
                PanelButton(w.str(R.string.record_live), t, onClick = { ch.startRecording(); onClose() }) { c, sz -> recordIcon(c, sz, t.ink) }
            }
            Connection(vStatus, lStatus, nStatus, t, w)
            Telltales(signals, t, w)
            AgentFlow(s, details, t, w) { details = !details }
            if (details) AgentDetails(s, t, w)
        }
    }
}

// ---------------------------------------------------------------- scenarios

/** One scenario: where its cars do what (a small map), what they do and in how many cars (icons and counts). */
@Composable
private fun ScenarioCard(
    title: String, scenario: Scenario, route: RouteData, selected: Boolean, enabled: Boolean, t: Tokens, w: Words,
    action: String? = null, onClick: () -> Unit,
) {
    val beats = scenario.story.sortedBy { it.fromM }
    val cars = scenario.vehicles.size
    Row(
        Modifier.fillMaxWidth().clip(Card).background(if (selected) t.surface else t.page)
            .border(if (selected) 2.dp else 1.dp, if (selected) t.ink else t.grid, Card)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, ${w.str(R.string.sim_cars, cars)}, " + beats.joinToString { "${w.issue(it.metric)} ${it.cars}" }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniRoute(route, beats, t, Modifier.size(width = 136.dp, height = 72.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Type.caption, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    for (beat in beats) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IssueIcon(beat.metric, 30.dp, if (beat.expectDifferent) t.ink else t.ink2)
                            Spacer(Modifier.width(4.dp))
                            Text("${beat.cars}", style = Type.captionNum, color = if (beat.expectDifferent) t.ink else t.ink2)
                            // A control: the cars do it everywhere, so nothing may stand out.
                            if (!beat.expectDifferent) {
                                Spacer(Modifier.width(8.dp))
                                IconBox(22.dp) { c, sz -> equalsIcon(c, sz, t.ink2) }
                            }
                        }
                    }
                }
                IconBox(24.dp) { c, sz -> carIcon(c, sz, t.muted) }
                Spacer(Modifier.width(4.dp))
                Text("$cars", style = Type.captionNum, color = t.muted)
            }
            if (action != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(t.actionBg).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBox(18.dp) { c, sz -> playIcon(c, sz, t.actionInk) }
                    Spacer(Modifier.width(6.dp))
                    Text(action, style = Type.caption, color = t.actionInk)
                }
            }
        }
    }
}

/** The route's own shape, start bottom-left, with the scenario's places as dots and its stretches as thick lines. */
@Composable
private fun MiniRoute(route: RouteData, beats: List<StoryBeat>, t: Tokens, modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val pad = 6.dp.toPx()
            val geo = RouteGeometry(route.route, Rect(pad, pad, size.width - pad, size.height - pad))
            val road = geo.path()
            val stretches = beats.filter { it.toM > it.fromM }.map { (if (it.expectDifferent) t.ink else t.ink2) to geo.path(it.fromM, it.toM) }
            val points = beats.filter { it.toM <= it.fromM }.map { geo.at(it.fromM).first }
            onDrawBehind {
                drawPath(road, t.muted, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(t.ink2, 3.dp.toPx(), geo.start)
                for ((color, p) in stretches) drawPath(p, color, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                for (p in points) {
                    drawCircle(t.surface, 7.dp.toPx(), p)
                    drawCircle(t.ink, 5.dp.toPx(), p)
                }
            }
        },
    )
}

/** The replay as cars filling in one by one, and the knots they have tied so far. */
@Composable
private fun ReplayStrip(s: ChannelState, t: Tokens, w: Words) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = (if (s.replaying) w.str(R.string.sim_passing, s.replayDone, s.replayTotal) + ", " else "") + w.str(R.string.knots, s.knotCount)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            repeat(s.replayTotal) { i -> IconBox(30.dp) { c, sz -> carIcon(c, sz, if (i < s.replayDone) t.ink else t.grid) } }
        }
        IconBox(30.dp) { c, sz -> knotIcon(c, sz, t.ink) }
        Spacer(Modifier.width(6.dp))
        Text("${s.knotCount}", style = Type.title.copy(fontFeatureSettings = "tnum"), color = t.ink)
    }
}

@Composable
private fun PanelButton(label: String, t: Tokens, enabled: Boolean = true, onClick: () -> Unit, icon: DrawScope.(Offset, Float) -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.height(PanelTouch), shape = RoundedCornerShape(12.dp)) {
        IconBox(26.dp, icon)
        Spacer(Modifier.width(10.dp))
        Text(label, style = Type.button, color = if (enabled) t.ink else t.muted)
    }
}

@Composable
private fun IconTap(label: String, onClick: () -> Unit, icon: DrawScope.(Offset, Float) -> Unit) {
    Box(
        Modifier.size(PanelTouch).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { IconBox(28.dp, icon) }
}

// ---------------------------------------------------------------- the car

/** Vehicle, position and navigation as three tiles; a filled dot when data is arriving, a hollow one while waiting. */
@Composable
private fun Connection(v: PortStatus, l: PortStatus, n: PortStatus, t: Tokens, w: Words) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PortTile(w.str(R.string.port_vehicle), v, t, Modifier.weight(1f)) { c, sz -> carIcon(c, sz, t.ink) }
        PortTile(w.str(R.string.port_location), l, t, Modifier.weight(1f)) { c, sz -> pinIcon(c, sz, t.ink) }
        PortTile(w.str(R.string.port_navi), n, t, Modifier.weight(1f)) { c, sz -> naviIcon(c, sz, t.ink) }
    }
}

@Composable
private fun PortTile(name: String, s: PortStatus, t: Tokens, modifier: Modifier, icon: DrawScope.(Offset, Float) -> Unit) {
    Column(
        modifier.clip(Card).background(t.surface).padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$name, ${s.note ?: s.source}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            IconBox(40.dp, icon)
            Canvas(Modifier.size(14.dp).align(Alignment.TopEnd)) {
                val r = size.minDimension / 2
                drawCircle(t.surface, r, center)
                if (s.live) drawCircle(t.good, r * 0.75f, center) else drawCircle(t.muted, r * 0.6f, center, style = Stroke(2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = Type.caption, color = t.ink2, maxLines = 1)
    }
}

/**
 * The car's signals as a cluster of telltales: the gear, the speed, and lamps that light up (ink) or stay dark (grid)
 * the way a dashboard's do. The wipers show their speed as bars, the wheel its angle, the outside air its temperature.
 */
@Composable
private fun Telltales(s: VehicleSignals, t: Tokens, w: Words) {
    Column(
        Modifier.fillMaxWidth().clip(Card).background(t.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((g, label) in listOf(Gear.PARK to "P", Gear.REVERSE to "R", Gear.NEUTRAL to "N", Gear.DRIVE to "D")) {
                val on = s.gear == g
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) t.ink else Color.Transparent)
                        .semantics { contentDescription = "${w.str(R.string.sig_gear)} $label" + if (on) ", ${w.str(R.string.on)}" else "" },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = Type.title, color = if (on) t.surface else t.muted) }
            }
            Spacer(Modifier.weight(1f))
            Text(s.speedKmh?.let { "%.0f".format(it) } ?: "—", style = Type.headline.copy(fontFeatureSettings = "tnum"), color = t.ink)
            Spacer(Modifier.width(6.dp))
            Text("km/h", style = Type.caption, color = t.ink2)
        }
        val level = s.wiperLevel ?: if (s.wiper) WiperLevel.INTERMITTENT else WiperLevel.OFF
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Lamp(s.brake, w.str(R.string.sig_brake), t, w) { c, sz, ink -> issueIcon(Metric.HARD_BRAKE_RATE, c, sz, ink) }
            Lamp(s.turnSignal == TurnSignal.LEFT, "${w.str(R.string.sig_turn)} ${w.str(R.string.left)}", t, w) { c, sz, ink -> turnIcon(c, sz, ink, left = true) }
            Lamp(s.turnSignal == TurnSignal.RIGHT, "${w.str(R.string.sig_turn)} ${w.str(R.string.right)}", t, w) { c, sz, ink -> turnIcon(c, sz, ink, left = false) }
            Lamp(
                on = true, name = "${w.str(R.string.sig_wheel)} ${Words.degrees(s.wheelAngleDeg)}", t = t, w = w, state = false,
                below = { Text(Words.degrees(s.wheelAngleDeg), style = Type.legal.copy(fontFeatureSettings = "tnum"), color = t.ink2) },
            ) { c, sz, ink -> steeringIcon(c, sz, ink, (s.wheelAngleDeg ?: 0.0) * STEERING_RATIO) }
            Lamp(
                level != WiperLevel.OFF, w.str(R.string.sig_wiper), t, w,
                below = { LevelBars(level.ordinal, 3, t) },
            ) { c, sz, ink -> issueIcon(Metric.WIPER_ON_RATE, c, sz, ink) }
            Lamp(s.fogLight, w.str(R.string.sig_fog), t, w) { c, sz, ink -> issueIcon(Metric.FOG_ON_RATE, c, sz, ink) }
            Lamp(s.rearFog, w.str(R.string.sig_rear_fog), t, w) { c, sz, ink -> issueIcon(Metric.REAR_FOG_RATE, c, sz, ink) }
            Lamp(s.lowBeam, w.str(R.string.sig_low_beam), t, w) { c, sz, ink -> issueIcon(Metric.DAY_LOW_BEAM_RATE, c, sz, ink) }
            Lamp(
                on = s.outsideTempC != null, name = "${w.str(R.string.sig_temp)} ${s.outsideTempC?.let { "%.1f °C".format(it) } ?: "—"}", t = t, w = w, state = false,
                below = { Text(s.outsideTempC?.let { "%.0f°".format(it) } ?: "—", style = Type.legal.copy(fontFeatureSettings = "tnum"), color = t.ink2) },
            ) { c, sz, ink -> thermometerIcon(c, sz, ink) }
        }
    }
}

/** Front wheel to steering wheel, roughly: the wheel turns this many times the road wheels. */
private const val STEERING_RATIO = 15.0

/** One telltale: lit in ink when on, dark in the grid colour when off, with an optional reading below it. */
@Composable
private fun Lamp(
    on: Boolean, name: String, t: Tokens, w: Words, state: Boolean = true,
    below: (@Composable () -> Unit)? = null, draw: DrawScope.(Offset, Float, Color) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(48.dp).semantics(mergeDescendants = true) {
            contentDescription = if (state) "$name, ${w.str(if (on) R.string.on else R.string.off)}" else name
        },
    ) {
        IconBox(38.dp) { c, sz -> draw(c, sz, if (on) t.ink else t.grid) }
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) { below?.invoke() }
    }
}

@Composable
private fun LevelBars(lit: Int, of: Int, t: Tokens) = Canvas(Modifier.size(width = 30.dp, height = 10.dp)) {
    val gap = 3.dp.toPx()
    val bw = (size.width - gap * (of - 1)) / of
    for (i in 0 until of) {
        drawRoundRect(if (i < lit) t.ink else t.grid, Offset(i * (bw + gap), 0f), Size(bw, size.height), CornerRadius(2.dp.toPx()))
    }
}

// ---------------------------------------------------------------- the agent channel

/** Cars tie knots, knots become ribbons, agents read the ribbons: the channel as a flow with its counts. Tap for the JSON. */
@Composable
private fun AgentFlow(s: ChannelState, open: Boolean, t: Tokens, w: Words, onToggle: () -> Unit) {
    val ribbons = s.briefing.items.count { it.state.status == RibbonStatus.PUBLISHED }
    Column(
        Modifier.fillMaxWidth().clip(Card).background(t.surface).clickable(onClick = onToggle).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${w.str(R.string.agent_io)}, ${w.str(R.string.knots, s.knotCount)}" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.str(R.string.agent_io), style = Type.caption, color = t.ink2, modifier = Modifier.weight(1f))
            IconBox(24.dp) { c, sz -> chevronIcon(c, sz, t.ink2, up = open) }
        }
        Row(verticalAlignment = Alignment.Top) {
            FlowNode(w.str(R.string.flow_cars), null, t) { c, sz -> carIcon(c, sz, t.ink) }
            FlowEdge(s.knotCount, t, Modifier.weight(1f)) { c, sz -> knotIcon(c, sz, t.ink2) }
            FlowNode(w.str(R.string.flow_ribbons), ribbons, t) { c, sz -> ribbonIcon(c, sz, t.ink) }
            FlowEdge(null, t, Modifier.weight(1f)) { _, _ -> }
            FlowNode(w.str(R.string.flow_agents), null, t) { c, sz -> sparkIcon(c, sz, t.ink) }
        }
    }
}

/** A node of the flow: its icon in a disc, a one-word name under it, and its count beside it. */
@Composable
private fun FlowNode(name: String, count: Int?, t: Tokens, icon: DrawScope.(Offset, Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(t.page), contentAlignment = Alignment.Center) { IconBox(34.dp, icon) }
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text("$count", style = Type.title.copy(fontFeatureSettings = "tnum"), color = t.ink)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = Type.legal, color = t.ink2, maxLines = 1)
    }
}

/** An arrow between two nodes, with what travels along it (a knot icon and the count) above it. */
@Composable
private fun FlowEdge(count: Int?, t: Tokens, modifier: Modifier, icon: DrawScope.(Offset, Float) -> Unit) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
            if (count != null) {
                IconBox(20.dp, icon)
                Spacer(Modifier.width(4.dp))
                Text("$count", style = Type.captionNum, color = t.ink2)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2
            val sw = 2.dp.toPx()
            drawLine(t.ink2, Offset(0f, y), Offset(size.width, y), sw, StrokeCap.Round)
            val h = 6.dp.toPx()
            drawLine(t.ink2, Offset(size.width, y), Offset(size.width - h, y - h * 0.7f), sw, StrokeCap.Round)
            drawLine(t.ink2, Offset(size.width, y), Offset(size.width - h, y + h * 0.7f), sw, StrokeCap.Round)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The JSON itself, for whoever wants to see what goes over the channel: the last knot written, the ribbon read. */
@Composable
private fun AgentDetails(s: ChannelState, t: Tokens, w: Words) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 20.sp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(w.str(R.string.agent_endpoint), style = Type.caption, color = t.ink2)
        JsonBlock(w.str(R.string.agent_written), s.lastKnot?.let { PrettyJson.encodeToString(Knot.serializer(), it) }, mono, t, w)
        JsonBlock(w.str(R.string.agent_read), s.briefing.headline?.state?.let { PrettyJson.encodeToString(RibbonState.serializer(), it) }, mono, t, w)
        for (line in s.log) Text(line, style = Type.caption, color = t.muted)
    }
}

@Composable
private fun JsonBlock(label: String, json: String?, mono: TextStyle, t: Tokens, w: Words) {
    Text(label, style = Type.caption, color = t.ink2)
    Text(json ?: w.str(R.string.agent_none), style = mono, color = t.ink, modifier = Modifier.fillMaxWidth().background(t.surface).padding(12.dp))
}

// ---------------------------------------------------------------- icons used only here

private fun DrawScope.stroke(s: Float) = Stroke(s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.closeIcon(c: Offset, s: Float, ink: Color) {
    val d = s * 0.3f
    drawLine(ink, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), s * 0.09f, StrokeCap.Round)
    drawLine(ink, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), s * 0.09f, StrokeCap.Round)
}

/** Start over: a circular arrow. */
private fun DrawScope.resetIcon(c: Offset, s: Float, ink: Color) {
    val r = s * 0.32f
    drawArc(ink, -60f, 300f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = stroke(s))
    val a = Math.toRadians(-60.0)
    val tip = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
    drawLine(ink, tip, Offset(tip.x - s * 0.2f, tip.y - s * 0.02f), s * 0.085f, StrokeCap.Round)
    drawLine(ink, tip, Offset(tip.x + s * 0.02f, tip.y + s * 0.2f), s * 0.085f, StrokeCap.Round)
}

/** Record: a dot in a ring. */
private fun DrawScope.recordIcon(c: Offset, s: Float, ink: Color) {
    drawCircle(ink, s * 0.36f, c, style = stroke(s))
    drawCircle(ink, s * 0.18f, c)
}

/** Knots: the cars' records, beads tied along a string. */
private fun DrawScope.knotIcon(c: Offset, s: Float, ink: Color) {
    drawLine(ink, Offset(c.x - s * 0.46f, c.y), Offset(c.x + s * 0.46f, c.y), s * 0.07f, StrokeCap.Round)
    for (x in listOf(-0.28f, 0f, 0.28f)) drawCircle(ink, s * 0.11f, Offset(c.x + s * x, c.y))
}

/** A ribbon: the bow that a place's knots add up to. */
private fun DrawScope.ribbonIcon(c: Offset, s: Float, ink: Color) {
    val left = Path().apply {
        moveTo(c.x, c.y); lineTo(c.x - s * 0.42f, c.y - s * 0.26f); lineTo(c.x - s * 0.42f, c.y + s * 0.26f); close()
    }
    val right = Path().apply {
        moveTo(c.x, c.y); lineTo(c.x + s * 0.42f, c.y - s * 0.26f); lineTo(c.x + s * 0.42f, c.y + s * 0.26f); close()
    }
    drawPath(left, ink, style = stroke(s))
    drawPath(right, ink, style = stroke(s))
    drawCircle(ink, s * 0.1f, c)
}

/** An agent: a four-pointed spark. */
private fun DrawScope.sparkIcon(c: Offset, s: Float, ink: Color) {
    val r = s * 0.42f; val k = s * 0.1f
    drawPath(Path().apply {
        moveTo(c.x, c.y - r); lineTo(c.x + k, c.y - k); lineTo(c.x + r, c.y); lineTo(c.x + k, c.y + k)
        lineTo(c.x, c.y + r); lineTo(c.x - k, c.y + k); lineTo(c.x - r, c.y); lineTo(c.x - k, c.y - k); close()
    }, ink)
}

private fun DrawScope.pinIcon(c: Offset, s: Float, ink: Color) {
    val r = s * 0.24f
    val top = Offset(c.x, c.y - s * 0.12f)
    drawPath(Path().apply {
        moveTo(c.x, c.y + s * 0.44f)
        lineTo(c.x - r * 0.95f, top.y + r * 0.35f)
        arcTo(Rect(top.x - r, top.y - r, top.x + r, top.y + r), 160f, 220f, false)
        close()
    }, ink, style = stroke(s))
    drawCircle(ink, s * 0.08f, top)
}

/** Navigation: the arrow of a route guidance screen. */
private fun DrawScope.naviIcon(c: Offset, s: Float, ink: Color) {
    drawPath(Path().apply {
        moveTo(c.x, c.y - s * 0.42f); lineTo(c.x + s * 0.32f, c.y + s * 0.38f); lineTo(c.x, c.y + s * 0.2f); lineTo(c.x - s * 0.32f, c.y + s * 0.38f); close()
    }, ink, style = stroke(s))
}

private fun DrawScope.turnIcon(c: Offset, s: Float, ink: Color, left: Boolean) {
    val d = if (left) -1f else 1f
    drawPath(Path().apply {
        moveTo(c.x + d * s * 0.42f, c.y)
        lineTo(c.x + d * s * 0.02f, c.y - s * 0.34f)
        lineTo(c.x + d * s * 0.02f, c.y - s * 0.14f)
        lineTo(c.x - d * s * 0.38f, c.y - s * 0.14f)
        lineTo(c.x - d * s * 0.38f, c.y + s * 0.14f)
        lineTo(c.x + d * s * 0.02f, c.y + s * 0.14f)
        lineTo(c.x + d * s * 0.02f, c.y + s * 0.34f)
        close()
    }, ink)
}

private fun DrawScope.steeringIcon(c: Offset, s: Float, ink: Color, angleDeg: Double) {
    rotate(angleDeg.toFloat().coerceIn(-180f, 180f), c) {
        drawCircle(ink, s * 0.4f, c, style = stroke(s))
        drawCircle(ink, s * 0.1f, c)
        drawLine(ink, Offset(c.x - s * 0.4f, c.y), Offset(c.x + s * 0.4f, c.y), s * 0.085f, StrokeCap.Round)
        drawLine(ink, c, Offset(c.x, c.y + s * 0.4f), s * 0.085f, StrokeCap.Round)
    }
}

private fun DrawScope.thermometerIcon(c: Offset, s: Float, ink: Color) {
    drawRoundRect(ink, Offset(c.x - s * 0.08f, c.y - s * 0.42f), Size(s * 0.16f, s * 0.58f), CornerRadius(s * 0.08f), style = stroke(s))
    drawCircle(ink, s * 0.16f, Offset(c.x, c.y + s * 0.26f))
    for (y in listOf(-0.3f, -0.16f, -0.02f)) drawLine(ink, Offset(c.x + s * 0.14f, c.y + s * y), Offset(c.x + s * 0.24f, c.y + s * y), s * 0.06f, StrokeCap.Round)
}

fun DrawScope.chevronIcon(c: Offset, s: Float, ink: Color, up: Boolean) {
    val d = if (up) -1f else 1f
    drawLine(ink, Offset(c.x - s * 0.3f, c.y - d * s * 0.12f), Offset(c.x, c.y + d * s * 0.16f), s * 0.09f, StrokeCap.Round)
    drawLine(ink, Offset(c.x, c.y + d * s * 0.16f), Offset(c.x + s * 0.3f, c.y - d * s * 0.12f), s * 0.09f, StrokeCap.Round)
}

/** "Stays usual": two bars. */
private fun DrawScope.equalsIcon(c: Offset, s: Float, ink: Color) {
    drawLine(ink, Offset(c.x - s * 0.3f, c.y - s * 0.12f), Offset(c.x + s * 0.3f, c.y - s * 0.12f), s * 0.1f, StrokeCap.Round)
    drawLine(ink, Offset(c.x - s * 0.3f, c.y + s * 0.12f), Offset(c.x + s * 0.3f, c.y + s * 0.12f), s * 0.1f, StrokeCap.Round)
}
