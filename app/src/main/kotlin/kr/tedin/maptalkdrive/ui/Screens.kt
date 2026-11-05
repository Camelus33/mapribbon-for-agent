package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.tedin.maptalkdrive.demo.RibbonViewModel
import kr.tedin.maptalkdrive.demo.UiState
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.port.VehicleSignals
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.spec.Deviation

/**
 * `restricted` comes from CarUxRestrictions: while the car moves, only the one-line driving view is shown and there is
 * nothing to press. The demo drive shows the same view without the restriction so it can be stopped.
 */
@Composable
fun AppScreen(vm: RibbonViewModel, restricted: Boolean) {
    val ui by vm.ui.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        if (restricted || ui.drive != null) {
            DrivingScreen(vm, ui, restricted)
        } else {
            Row(Modifier.fillMaxSize()) {
                BriefingScreen(vm, ui, Modifier.weight(0.72f).fillMaxHeight())
                DemoConsole(vm, ui, Modifier.weight(0.28f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun BriefingScreen(vm: RibbonViewModel, ui: UiState, modifier: Modifier) {
    val set = vm.data.anchorSet
    val b = ui.briefing
    Column(modifier.padding(horizontal = 48.dp, vertical = 40.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(set.routeName, color = Palette.ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("${km(set.lengthM)} · 경로 위 시설물 ${set.anchors.size}곳 · 리본 ${b.items.size - b.notEnoughCount}곳 공개", color = Palette.dim, fontSize = 22.sp)
            }
            if (vm.data.baseline.simulated || ui.scenarioId != null) SimBadge()
        }
        ui.naviNotice?.let { notice ->
            Spacer(Modifier.height(16.dp))
            Text(notice, color = Palette.teal, fontSize = 20.sp)
        }
        Spacer(Modifier.height(28.dp))
        Headline(b.headline, b.differentCount, b.notEnoughCount)
        Spacer(Modifier.height(24.dp))
        RouteStrip(b, set.lengthM.toDouble(), myAlongM = null)
        Spacer(Modifier.height(8.dp))
        Text(
            "터 터널(출구 기준) · 분 분기점 · 카 단속카메라(가상) · 회색 평소대로 · 노랑 평소와 다름 · 빨강 크게 다름 · 점선 기록 부족",
            color = Palette.dim, fontSize = 16.sp,
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(set.attribution, color = Palette.faint, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Button(onClick = { vm.startDemoDrive() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.teal, contentColor = Palette.bg)) {
                Text("안내 시작 (시뮬레이션 주행)", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Headline(top: BriefingItem?, different: Int, notEnough: Int) {
    val (fg, bgc, chip, text) = when {
        top == null -> listOf(Palette.dim, Palette.panel, "평소와 다른 곳 없음", if (notEnough > 0) "기록이 부족한 시설물 ${notEnough}곳은 판단하지 않았습니다" else "경로 위 시설물 모두 평소대로")
        top.state.deviation == Deviation.HIGH -> listOf(Palette.red, Palette.redBg, "평소와 다름 $different", headlineOf(top))
        else -> listOf(Palette.yellow, Palette.yellowBg, "평소와 다름 $different", headlineOf(top))
    }.let { Quad(it[0] as Color, it[1] as Color, it[2] as String, it[3] as String) }
    Row(
        Modifier.background(bgc, RoundedCornerShape(10.dp)).border(1.5.dp, fg, RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chip, color = Palette.bg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(fg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 4.dp))
        Spacer(Modifier.width(18.dp))
        Text(text, color = Palette.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}

private data class Quad(val a: Color, val b: Color, val c: String, val d: String)

@Composable
private fun SimBadge() {
    Text(
        "시뮬레이션 데이터", color = Palette.sim, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.5.dp, Palette.sim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun DrivingScreen(vm: RibbonViewModel, ui: UiState, restricted: Boolean) {
    val along = ui.drive?.alongM ?: 0.0
    val next = ui.briefing.nextDifferentAhead(along)
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (next != null) {
                val (item, dist) = next
                val c = colorOf(item)
                Box(Modifier.size(110.dp).background(if (item.state.deviation == Deviation.HIGH) Palette.redBg else Palette.yellowBg, RoundedCornerShape(16.dp)).border(4.dp, c, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text(glyph(item.anchor.type), color = c, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(32.dp))
                Text(km(dist), color = Palette.ink, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(32.dp))
                Text("앞 · ${titleOf(item)}", color = Palette.ink, fontSize = 44.sp)
            } else {
                Text("남은 경로에 평소와 다른 곳 없음", color = Palette.dim, fontSize = 44.sp)
            }
        }
        Spacer(Modifier.height(40.dp))
        RouteStrip(ui.briefing, vm.data.anchorSet.lengthM.toDouble(), myAlongM = along, labels = false)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ui.drive?.simulated == true) "시뮬레이션 주행 · ${km(along)} 지점" else "주행 중 · ${km(along)} 지점",
                color = if (ui.drive?.simulated == true) Palette.sim else Palette.dim, fontSize = 20.sp, modifier = Modifier.weight(1f),
            )
            if (!restricted) OutlinedButton(onClick = { vm.stopDrive() }) { Text("주행 끝", fontSize = 20.sp, color = Palette.ink) }
        }
    }
}

@Composable
private fun DemoConsole(vm: RibbonViewModel, ui: UiState, modifier: Modifier) {
    val signals by vm.vehicle.signals.collectAsState()
    val vStatus by vm.vehicle.status.collectAsState()
    val lStatus by vm.location.status.collectAsState()
    val nStatus by vm.navi.status.collectAsState()
    Column(modifier.background(Palette.panel).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시연 콘솔", color = Palette.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("가상의 차들을 이 구간에 지나가게 해 리본을 채웁니다.", color = Palette.dim, fontSize = 16.sp)
        for (s in vm.data.scenarios) {
            val active = ui.scenarioId == s.id
            Button(
                onClick = { vm.runScenario(s.id) }, enabled = !ui.replaying,
                colors = ButtonDefaults.buttonColors(containerColor = if (active) Palette.sim else Palette.track, contentColor = if (active) Palette.bg else Palette.ink),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("${s.vehicles.size}대 · ${s.title}", fontSize = 17.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.clear() }) { Text("리본 비우기", color = Palette.ink, fontSize = 16.sp) }
            OutlinedButton(onClick = { vm.startLiveDrive() }) { Text("실차 신호로 기록", color = Palette.ink, fontSize = 16.sp) }
        }
        Text("매듭 ${ui.knotCount}개${if (ui.replaying) " · 차량 통과 중…" else ""}", color = Palette.ink, fontSize = 18.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ui.log) { line -> Text(line, color = Palette.dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        Text("차량 연결", color = Palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        PortRow(vStatus); PortRow(lStatus); PortRow(nStatus)
        SignalGrid(signals)
    }
}

@Composable
private fun PortRow(s: PortStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (s.live) Color(0xFF4FBF7A) else Palette.faint, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(s.source + (s.note?.let { " · $it" } ?: ""), color = Palette.dim, fontSize = 15.sp)
    }
}

@Composable
private fun SignalGrid(s: VehicleSignals) {
    val turn = when (s.turnSignal) { TurnSignal.LEFT -> "왼쪽"; TurnSignal.RIGHT -> "오른쪽"; TurnSignal.OFF -> "꺼짐" }
    val lines = listOf(
        "기어 ${s.gear}  ·  속도 ${s.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"}  ·  브레이크 ${if (s.brake) "밟음" else "—"}",
        "방향지시등 $turn  ·  앞바퀴 각도 ${s.wheelAngleDeg?.let { "%.1f°".format(it) } ?: "—"}",
        "와이퍼 ${if (s.wiper) "작동" else "—"}  ·  안개등 ${if (s.fogLight) "켜짐" else "—"}",
    )
    Column { lines.forEach { Text(it, color = Palette.ink, fontSize = 15.sp) } }
}
