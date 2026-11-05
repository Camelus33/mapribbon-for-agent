package kr.tedin.maptalkdrive.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.tedin.maptalkdrive.demo.RibbonViewModel
import kr.tedin.maptalkdrive.demo.UiState
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.port.VehicleSignals
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus

private val Gutter = 48.dp
private val Touch = 76.dp

/**
 * `restricted` comes from CarUxRestrictions: while the car moves only the driving view is shown and nothing can be
 * pressed. The demo drive shows the same view without the restriction so it can be stopped.
 */
@Composable
fun AppScreen(vm: RibbonViewModel, restricted: Boolean) {
    val t = LocalTokens.current
    val ui by vm.ui.collectAsState()
    var sheet by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(t.surface)) {
        if (restricted || ui.drive != null) {
            DrivingScreen(vm, ui, restricted, t)
        } else {
            BriefingScreen(vm, ui, t, onOpenDemo = { sheet = true })
            if (sheet) DemoSheet(vm, ui, t, onClose = { sheet = false }, Modifier.align(Alignment.CenterEnd))
        }
    }
}

// ---------------------------------------------------------------- parked briefing

@Composable
private fun BriefingScreen(vm: RibbonViewModel, ui: UiState, t: Tokens, onOpenDemo: () -> Unit) {
    val set = vm.data.anchorSet
    val b = ui.briefing
    val (start, end) = set.routeName.substringAfter(' ').split(" → ").let { it.first() to it.last() }
    Column(Modifier.fillMaxSize().padding(start = Gutter, end = Gutter, top = 32.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${set.routeName.substringBefore(' ')} · $start → $end · ${Words.at(set.lengthM)} · 시설물 ${set.anchors.size}곳", style = Type.context, color = t.ink2)
                val status = when {
                    ui.replaying -> "가상 차량 통과 중 · ${ui.replayDone}/${ui.replayTotal}"
                    ui.naviNotice != null -> ui.naviNotice
                    else -> null
                }
                if (status != null) Text(status, style = Type.caption, color = t.ink2)
            }
            SimChip(t)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = onOpenDemo, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                Text("시연", style = Type.button, color = t.ink)
            }
        }
        // The road runs from bottom-left (start) to top-right (destination). The two empty triangles it leaves hold
        // the answer (top-left) and the table (bottom-right); the map keeps its labels out of both.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            var headRect by remember { mutableStateOf(Rect.Zero) }
            var tableRect by remember { mutableStateOf(Rect.Zero) }
            RouteMap(b, vm.data.route, start, end, t, reserved = listOf(headRect, tableRect).filter { it != Rect.Zero }, modifier = Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).padding(top = 24.dp).onGloballyPositioned { headRect = it.boundsInParent() }) {
                Headline(b, t)
                Spacer(Modifier.height(16.dp))
                Legend(b, t)
            }
            Box(Modifier.align(Alignment.BottomEnd).width(780.dp).onGloballyPositioned { tableRect = it.boundsInParent() }) {
                ResultTable(b.items.filter { it.different }.sortedWith(compareByDescending<BriefingItem> { it.state.deviation.ordinal }.thenBy { it.pointM }), t)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(set.attribution, style = Type.legal, color = t.muted, modifier = Modifier.weight(1f))
            Button(
                onClick = { vm.startDemoDrive() },
                modifier = Modifier.height(Touch),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.actionBg, contentColor = t.actionInk),
            ) { Text("시뮬레이션 주행 시작", style = Type.button, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun Headline(b: Briefing, t: Tokens) {
    val top = b.headline
    Row(verticalAlignment = Alignment.Top) {
        if (top != null) {
            StatusGlyph(top.state.deviation, 40.dp, t, t.surface)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("${Words.place(top)} · ${Words.issue(Words.top(top)?.metric)}", style = Type.headline, color = t.ink)
                Text(
                    "${Words.thisTime(top)} (평소 ${Words.usual(top)}) · ${Words.at(top.pointM)} 지점 · 평소와 다른 곳 ${b.differentCount}곳",
                    style = Type.context, color = t.ink2,
                )
            }
        } else {
            Column {
                Text("평소와 다른 곳 없음", style = Type.headline, color = t.ink)
                Text(
                    if (b.notEnoughCount > 0) "기록이 부족한 시설물 ${b.notEnoughCount}곳은 판단하지 않았습니다" else "경로 위 시설물 ${b.items.size}곳 모두 평소대로",
                    style = Type.context, color = t.ink2,
                )
            }
        }
    }
}

/** Only the states on screen, each with the mark the strip uses. */
@Composable
private fun Legend(b: Briefing, t: Tokens) {
    val kinds = buildList {
        if (b.items.any { it.different && it.state.deviation == Deviation.HIGH }) add(LegendKind.HIGH to "평소와 크게 다름")
        if (b.items.any { it.different && it.state.deviation == Deviation.SOME }) add(LegendKind.SOME to "평소와 조금 다름")
        if (b.items.any { it.state.status == RibbonStatus.PUBLISHED && !it.different }) add(LegendKind.USUAL to "평소대로")
        if (b.notEnoughCount > 0) add(LegendKind.NOT_ENOUGH to "기록 부족")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((k, label) in kinds) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(k, t)
                Spacer(Modifier.width(8.dp))
                Text(label, style = Type.caption, color = t.ink2)
            }
        }
        Text("굵은 구간 터널", style = Type.caption, color = t.muted)
    }
}

/** The table view of the different facilities: every number on the strip is readable here without colour. */
@Composable
private fun ResultTable(rows: List<BriefingItem>, t: Tokens) {
    if (rows.isEmpty()) return
    val cols = listOf(0.34f, 0.26f, 0.16f, 0.1f, 0.14f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, bottom = 8.dp)) {
            listOf("시설물", "무엇이", "이번", "평소", "위치").forEachIndexed { i, h ->
                Text(h, style = Type.caption, color = t.muted, modifier = Modifier.weight(cols[i]), textAlign = if (i == 4) TextAlign.End else TextAlign.Start)
            }
        }
        for (item in rows) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) { StatusGlyph(item.state.deviation, 28.dp, t, t.surface) }
                Text(Words.place(item), style = Type.body, color = t.ink, modifier = Modifier.weight(cols[0]), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Words.issue(Words.top(item)?.metric), style = Type.body, color = t.ink2, modifier = Modifier.weight(cols[1]), maxLines = 1)
                Text(Words.thisTime(item), style = Type.bodyNum, color = t.ink, modifier = Modifier.weight(cols[2]))
                Text(Words.usual(item), style = Type.bodyNum, color = t.ink2, modifier = Modifier.weight(cols[3]))
                Text(Words.at(item.pointM), style = Type.bodyNum, color = t.ink2, modifier = Modifier.weight(cols[4]), textAlign = TextAlign.End)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
    }
}

@Composable
private fun SimChip(t: Tokens) {
    Row(
        Modifier.border(1.dp, t.ink2.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            drawCircle(t.ink2, size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(t.ink2, size.minDimension / 6)
        }
        Spacer(Modifier.width(8.dp))
        Text("시뮬레이션 데이터", style = Type.caption, color = t.ink2)
    }
}

// ---------------------------------------------------------------- driving

@Composable
private fun DrivingScreen(vm: RibbonViewModel, ui: UiState, restricted: Boolean, t: Tokens) {
    val along = ui.drive?.alongM ?: 0.0
    val next = ui.briefing.nextDifferentAhead(along)
    Column(Modifier.fillMaxSize().padding(start = Gutter, end = Gutter, top = 48.dp, bottom = 24.dp)) {
        // Same diagonal as the briefing: the hero sits in the empty top-left, the road crosses to the top-right.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            DrivingMap(ui.briefing, vm.data.route, along, t, Modifier.fillMaxSize())
            // The outgoing side of the crossfade still composes after `next` has moved on (or become null), so each
            // side computes its own distance from the item it shows.
            Crossfade(targetState = next?.first, animationSpec = tween(150), label = "next", modifier = Modifier.align(Alignment.TopStart)) { item ->
                if (item != null) DriveHero(item, (item.pointM - along).coerceAtLeast(0.0), t) else DriveClear(t)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (ui.drive?.simulated == true) "시뮬레이션 주행" else "주행 중"} · ${Words.at(along)} 지점",
                style = Type.caption, color = t.ink2, modifier = Modifier.weight(1f),
            )
            if (ui.drive?.simulated == true) SimChip(t)
            if (!restricted) {
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = { vm.stopDrive() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) {
                    Text("주행 끝", style = Type.button, color = t.ink)
                }
            }
        }
    }
}

@Composable
private fun DriveHero(item: BriefingItem, distanceM: Double, t: Tokens) {
    val (num, unit) = Words.ahead(distanceM)
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeroMark(item, 136.dp, t)
        Spacer(Modifier.width(40.dp))
        Column {
            Row {
                // Tabular digits keep the width steady while the number counts down, so "km 앞" does not jitter.
                Text(num, style = Type.driveDistance.copy(fontFeatureSettings = "tnum"), color = t.ink, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(16.dp))
                Text(unit, style = Type.driveUnit, color = t.ink2, modifier = Modifier.alignByBaseline())
            }
            Text(Words.issue(Words.top(item)?.metric), style = Type.driveIssue, color = t.ink)
            Text("${Words.place(item)} · ${Words.level(item.state.deviation)}", style = Type.driveMeta, color = t.ink2)
        }
    }
}

/** The status shape at hero size with the facility's pictogram inside. */
@Composable
private fun HeroMark(item: BriefingItem, size: Dp, t: Tokens) {
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val s = this.size.minDimension
        if (item.state.deviation == Deviation.HIGH) {
            val tc = Offset(c.x, c.y + s * 0.06f)
            // At hero size the pictogram says what the facility is; the triangle itself already says "clearly different".
            statusMark(tc, Deviation.HIGH, s, t, 0f, t.surface, exclaim = false)
            pictogram(item.anchor.type, Offset(tc.x, tc.y + s * 0.1f), s * 0.44f, t.onStatus)
        } else {
            drawCircle(t.some, s * 0.44f, c)
            pictogram(item.anchor.type, c, s * 0.46f, t.onStatus)
        }
    }
}

@Composable
private fun DriveClear(t: Tokens) {
    Row(Modifier.heightIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(56.dp)) {
            val w = 6.dp.toPx()
            drawLine(t.ink2, Offset(size.width * 0.15f, size.height * 0.55f), Offset(size.width * 0.42f, size.height * 0.8f), w)
            drawLine(t.ink2, Offset(size.width * 0.42f, size.height * 0.8f), Offset(size.width * 0.88f, size.height * 0.22f), w)
        }
        Spacer(Modifier.width(24.dp))
        Text("남은 경로에 평소와 다른 곳 없음", style = Type.headline, color = t.ink2)
    }
}

// ---------------------------------------------------------------- demo sheet

@Composable
private fun DemoSheet(vm: RibbonViewModel, ui: UiState, t: Tokens, onClose: () -> Unit, modifier: Modifier) {
    val signals by vm.vehicle.signals.collectAsState()
    val vStatus by vm.vehicle.status.collectAsState()
    val lStatus by vm.location.status.collectAsState()
    val nStatus by vm.navi.status.collectAsState()
    var details by remember { mutableStateOf(false) }
    Row(modifier.fillMaxHeight().width(480.dp)) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(t.hairline))
        Column(Modifier.fillMaxSize().background(t.page).padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시연", style = Type.title, color = t.ink, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) { Text("닫기", style = Type.button, color = t.ink) }
            }
            Text("가상 차량을 이 구간에 지나가게 합니다", style = Type.caption, color = t.ink2)
            Column {
                for (s in vm.data.scenarios) {
                    val selected = ui.scenarioId == s.id
                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
                    Row(
                        Modifier.fillMaxWidth().height(Touch).clickable(enabled = !ui.replaying) { vm.runScenario(s.id); onClose() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 12.dp).background(if (selected) t.ink else t.page))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = Type.caption, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("가상 차량 ${s.vehicles.size}대", style = Type.caption, color = t.muted)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.grid))
            }
            if (ui.replaying) {
                Text("가상 차량 통과 중 · ${ui.replayDone}/${ui.replayTotal}", style = Type.caption, color = t.ink2)
                LinearProgressIndicator(progress = { ui.replayDone / ui.replayTotal.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = t.ink, trackColor = t.grid)
            } else {
                Text(if (ui.knotCount > 0) "기록 ${ui.knotCount}건" else "기록 없음", style = Type.caption, color = t.ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { vm.clear() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) { Text("비우기", style = Type.button, color = t.ink) }
                OutlinedButton(onClick = { vm.startLiveDrive(); onClose() }, modifier = Modifier.height(Touch), shape = RoundedCornerShape(12.dp)) { Text("실차 신호로 기록", style = Type.button, color = t.ink) }
            }
            Text(if (details) "자세히 닫기" else "자세히", style = Type.caption, color = t.ink2, modifier = Modifier.clickable { details = !details }.padding(vertical = 8.dp))
            if (details) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) { items(ui.log) { Text(it, style = Type.caption, color = t.muted) } }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text("차량 연결", style = Type.title, color = t.ink)
            PortRow("차량 신호", vStatus, t)
            PortRow("위치", lStatus, t)
            PortRow("내비", nStatus, t)
            SignalGrid(signals, t)
        }
    }
}

@Composable
private fun PortRow(name: String, s: PortStatus, t: Tokens) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.live) "✓" else "–", style = Type.caption, color = if (s.live) t.good else t.muted, modifier = Modifier.width(28.dp))
        Text(name, style = Type.caption, color = t.ink, modifier = Modifier.width(96.dp))
        Text(s.note ?: s.source, style = Type.caption, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignalGrid(s: VehicleSignals, t: Tokens) {
    val turn = when (s.turnSignal) { TurnSignal.LEFT -> "왼쪽"; TurnSignal.RIGHT -> "오른쪽"; TurnSignal.OFF -> "꺼짐" }
    val gear = when (s.gear) {
        kr.tedin.maptalkdrive.port.Gear.PARK -> "P"; kr.tedin.maptalkdrive.port.Gear.DRIVE -> "D"
        kr.tedin.maptalkdrive.port.Gear.REVERSE -> "R"; kr.tedin.maptalkdrive.port.Gear.NEUTRAL -> "N"; else -> "—"
    }
    val rows = listOf(
        "기어" to gear, "속도" to (s.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"),
        "브레이크" to if (s.brake) "밟음" else "꺼짐", "방향지시등" to turn,
        "앞바퀴 각도" to Words.degrees(s.wheelAngleDeg), "와이퍼" to if (s.wiper) "작동" else "꺼짐",
        "안개등" to if (s.fogLight) "켜짐" else "꺼짐",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.chunked(2).forEach { pair ->
            Row {
                pair.forEach { (k, v) ->
                    Row(Modifier.weight(1f)) {
                        Text(k, style = Type.caption, color = t.muted, modifier = Modifier.width(112.dp))
                        Text(v, style = Type.captionNum, color = t.ink)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
