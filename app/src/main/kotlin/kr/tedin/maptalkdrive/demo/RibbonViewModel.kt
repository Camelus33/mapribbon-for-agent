package kr.tedin.maptalkdrive.demo

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.tedin.maptalkdrive.adapter.PleosLocationAdapter
import kr.tedin.maptalkdrive.adapter.PleosNaviAdapter
import kr.tedin.maptalkdrive.adapter.PleosVehicleAdapter
import kr.tedin.maptalkdrive.port.NaviEvent
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.demo.SimVehicle
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.detect.Sample
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.pseudo.Pseudonym
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import java.security.SecureRandom
import java.time.LocalDate

/** My car on the route: where it is, and whether the position comes from the simulation or the car. */
data class DriveState(val alongM: Double, val simulated: Boolean)

data class UiState(
    val briefing: Briefing,
    val knotCount: Int = 0,
    val log: List<String> = emptyList(),
    val scenarioId: String? = null,
    val replaying: Boolean = false,
    val drive: DriveState? = null,
    val naviNotice: String? = null,
)

/**
 * The local ribbon channel. Knots from simulated cars and from this car go into one list; the shared ribbon-core
 * engine turns them into ribbons, exactly as a server would. Nothing leaves the device in this build.
 */
class RibbonViewModel(app: Application) : AndroidViewModel(app) {
    val data = DemoData.load(app)
    val vehicle = PleosVehicleAdapter(app)
    val location = PleosLocationAdapter(app)
    val navi = PleosNaviAdapter(app)

    private val anchors = data.anchorSet.anchors
    private val engine = RibbonEngine(anchors, data.baseline)
    private val knots = mutableListOf<Knot>()
    private val secret = deviceSecret(app)
    private var replayJob: Job? = null
    private var driveJob: Job? = null
    private var liveDetector: PassDetector? = null
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()

    private val _ui = MutableStateFlow(UiState(briefing = briefingNow()))
    val ui: StateFlow<UiState> = _ui

    init {
        viewModelScope.launch {
            navi.events.collect { e ->
                val notice = when (e) {
                    is NaviEvent.RouteStarted -> "내비 경로 시작: ${e.name ?: "목적지"} · ${"%.1f".format(e.distanceM / 1000.0)} km → 시연 구간 리본을 보여 줍니다"
                    NaviEvent.Arrived -> "목적지 도착"
                    NaviEvent.Cancelled -> "경로 취소"
                }
                _ui.update { it.copy(naviNotice = notice) }
                log(notice)
            }
        }
        viewModelScope.launch {
            location.fixes.collect { fix ->
                val d = liveDetector ?: return@collect
                val sig = vehicle.signals.value
                val sample = Sample(fix.tMs, LngLat(fix.lon, fix.lat), sig.speedKmh, sig.brake, sig.turnSignal, sig.wheelAngleDeg, sig.wiper, sig.fogLight)
                addKnots(d.accept(sample), "이 차")
                val snap = data.route.snap(LngLat(fix.lon, fix.lat))
                _ui.update { it.copy(drive = if (snap.distanceM < 60) DriveState(snap.alongM, simulated = false) else it.drive) }
            }
        }
    }

    fun startPorts() {
        vehicle.start(); location.start(); navi.start()
    }

    fun stopPorts() {
        vehicle.stop(); location.stop(); navi.stop()
    }

    /** Lets the simulated cars of one scenario drive the route one after another, so the ribbons fill up on screen. */
    fun runScenario(id: String) {
        val scenario = data.scenarios.firstOrNull { it.id == id } ?: return
        replayJob?.cancel()
        synchronized(knots) { knots.clear() }
        _ui.update { it.copy(scenarioId = id, replaying = true, knotCount = 0, log = emptyList(), briefing = briefingNow()) }
        log("시나리오: ${scenario.title}")
        replayJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            for (v in scenario.vehicles.sortedByDescending { it.startMinutesAgo }) {
                val produced = withContext(Dispatchers.Default) {
                    val detector = PassDetector(data.route, anchors, pseudonym = v.id, widthClass = v.widthClass)
                    ScenarioDrive.samples(v, data.route, anchors, startMs = now - v.startMinutesAgo * 60_000L)
                        .flatMap { detector.accept(it) }.toList()
                }
                addKnots(produced, "${v.id} (${v.startMinutesAgo}분 전 출발)")
                delay(700)
            }
            _ui.update { it.copy(replaying = false) }
        }
    }

    fun clear() {
        replayJob?.cancel()
        synchronized(knots) { knots.clear() }
        _ui.update { it.copy(scenarioId = null, replaying = false, knotCount = 0, log = emptyList(), briefing = briefingNow()) }
    }

    /** Drives this car down the demo route faster than real time. Its own knots join the channel like anyone's. */
    fun startDemoDrive(speedup: Int = 20) {
        stopDrive()
        val me = SimVehicle(id = "me", startMinutesAgo = 0, kmh = 95.0)
        val detector = PassDetector(data.route, anchors, pseudonym = pseudonymToday())
        driveJob = viewModelScope.launch {
            var alerted: String? = null
            for (s in ScenarioDrive.samples(me, data.route, anchors, startMs = System.currentTimeMillis())) {
                val along = s.fix?.let { data.route.snap(it).alongM } ?: _ui.value.drive?.alongM ?: 0.0
                val advanced = if (s.fix == null) along + 95 / 3.6 else along
                _ui.update { it.copy(drive = DriveState(advanced, simulated = true)) }
                addKnots(detector.accept(s), "이 차")
                _ui.value.briefing.nextDifferentAhead(advanced)?.let { (item, dist) ->
                    if (item.state.deviation == Deviation.HIGH && dist < 300 && alerted != item.anchor.id) {
                        alerted = item.anchor.id
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                    }
                }
                delay(1000L / speedup)
            }
            _ui.update { it.copy(drive = null) }
        }
    }

    /** Uses the car's own position and signals instead of the simulation. */
    fun startLiveDrive() {
        stopDrive()
        liveDetector = PassDetector(data.route, anchors, pseudonym = pseudonymToday())
        log("실차 신호로 주행 기록 시작 (위치: ${location.status.value.source})")
    }

    fun stopDrive() {
        driveJob?.cancel(); driveJob = null
        liveDetector = null
        _ui.update { it.copy(drive = null) }
    }

    fun dismissNotice() = _ui.update { it.copy(naviNotice = null) }

    private fun addKnots(new: List<Knot>, who: String) {
        if (new.isEmpty()) return
        val count = synchronized(knots) { knots += new; knots.size }
        _ui.update { it.copy(knotCount = count, briefing = briefingNow()) }
        log("$who · 매듭 ${new.size}개")
    }

    private fun briefingNow(): Briefing {
        val snapshot = synchronized(knots) { knots.toList() }
        return Briefing.build(anchors, engine.states(snapshot, System.currentTimeMillis()))
    }

    private fun log(line: String) = _ui.update { it.copy(log = (listOf(line) + it.log).take(40)) }

    private fun pseudonymToday() = Pseudonym.forDay(secret, LocalDate.now(Baseline.SEOUL))

    override fun onCleared() {
        stopPorts()
        tone?.release()
    }

    private companion object {
        /** A random per-install secret. It never leaves the device; only the daily HMAC of it does. */
        fun deviceSecret(ctx: Context): ByteArray {
            val prefs = ctx.getSharedPreferences("ribbon", Context.MODE_PRIVATE)
            prefs.getString("secret", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val s = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("secret", Base64.encodeToString(s, Base64.NO_WRAP)).apply()
            return s
        }
    }
}
