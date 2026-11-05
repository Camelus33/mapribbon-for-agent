package kr.tedin.maptalkdrive.channel

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.adapter.PleosLocationAdapter
import kr.tedin.maptalkdrive.adapter.PleosNaviAdapter
import kr.tedin.maptalkdrive.adapter.PleosVehicleAdapter
import kr.tedin.maptalkdrive.demo.DemoData
import kr.tedin.maptalkdrive.demo.RouteData
import kr.tedin.maptalkdrive.port.NaviEvent
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.detect.Sample
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.pseudo.Pseudonym
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import java.security.SecureRandom
import java.time.LocalDate

data class ChannelState(
    /** The route on screen and the one this car records on. */
    val routeId: String,
    val briefing: Briefing,
    val knotCount: Int = 0,
    val log: List<String> = emptyList(),
    val scenarioId: String? = null,
    val replaying: Boolean = false,
    val replayDone: Int = 0,
    val replayTotal: Int = 0,
    /** This car's agent is tying knots from the car's own signals. */
    val recording: Boolean = false,
    /** Knots this car tied since recording started. */
    val myKnots: Int = 0,
    /** The last knot any agent wrote, as it went into the channel. */
    val lastKnot: Knot? = null,
    val naviNotice: String? = null,
)

/**
 * The ribbon channel of this device, one per process: the activity, the recording service and the content provider
 * other agents read through all see the same knots. Knots from simulated cars and from this car go into one list; the
 * shared ribbon-core engine turns them into ribbons exactly as a server would. Nothing leaves the device in this build.
 */
class RibbonChannel private constructor(context: Context) {
    private val ctx = context.applicationContext
    val data = DemoData.load(ctx)
    val vehicle = PleosVehicleAdapter(ctx)
    val location = PleosLocationAdapter(ctx)
    val navi = PleosNaviAdapter(ctx)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engines = data.routes.associate { it.id to RibbonEngine(it.anchorSet.anchors, data.baseline) }
    private val knots = mutableListOf<Knot>()
    private val secret = deviceSecret(ctx)
    private var replayJob: Job? = null
    @Volatile private var recorder: PassDetector? = null
    private var portsStarted = false

    private val _state = MutableStateFlow(data.routes.first().id.let { ChannelState(routeId = it, briefing = briefingFor(it)) })
    val state: StateFlow<ChannelState> = _state

    /** The route on screen. */
    val current: RouteData get() = data.route(_state.value.routeId)

    init {
        scope.launch {
            navi.events.collect { e ->
                val notice = when (e) {
                    is NaviEvent.RouteStarted -> ctx.getString(R.string.navi_route, e.name ?: ctx.getString(R.string.navi_destination), e.distanceM / 1000.0)
                    NaviEvent.Arrived -> ctx.getString(R.string.navi_arrived)
                    NaviEvent.Cancelled -> ctx.getString(R.string.navi_cancelled)
                }
                _state.update { it.copy(naviNotice = notice) }
                log(notice)
            }
        }
        scope.launch {
            location.fixes.collect { fix ->
                val d = recorder ?: return@collect
                val sig = vehicle.signals.value
                val sample = Sample(fix.tMs, LngLat(fix.lon, fix.lat), sig.speedKmh, sig.brake, sig.turnSignal, sig.wheelAngleDeg, sig.wiper, sig.fogLight)
                val tied = d.accept(sample)
                if (tied.isNotEmpty()) _state.update { it.copy(myKnots = it.myKnots + tied.size) }
                addKnots(tied, ctx.getString(R.string.log_this_car))
            }
        }
    }

    fun startPorts() {
        if (portsStarted) return
        portsStarted = true
        vehicle.start(); location.start(); navi.start()
    }

    /** Lets the simulated cars of one scenario drive the route one after another, so the ribbons fill up on screen. */
    fun runScenario(id: String) {
        val scenario = data.scenarios.firstOrNull { it.id == id } ?: return
        val r = data.route(scenario.routeId)
        replayJob?.cancel()
        stopRecording()
        synchronized(knots) { knots.clear() }
        _state.update { it.copy(routeId = r.id, scenarioId = id, replaying = true, replayDone = 0, replayTotal = scenario.vehicles.size, knotCount = 0, lastKnot = null, log = emptyList(), briefing = briefingFor(r.id)) }
        log(ctx.getString(R.string.log_scenario, scenarioTitle(scenario.id)))
        replayJob = scope.launch {
            val now = System.currentTimeMillis()
            for (v in scenario.vehicles.sortedByDescending { it.startMinutesAgo }) {
                val produced = withContext(Dispatchers.Default) {
                    val detector = PassDetector(r.route, r.anchorSet.anchors, pseudonym = v.id, widthClass = v.widthClass)
                    ScenarioDrive.samples(v, r.route, r.anchorSet.anchors, startMs = now - v.startMinutesAgo * 60_000L)
                        .flatMap { detector.accept(it) }.toList()
                }
                addKnots(produced, ctx.getString(R.string.log_sim_car, _state.value.replayDone + 1, v.startMinutesAgo))
                _state.update { it.copy(replayDone = it.replayDone + 1) }
                delay(700)
            }
            _state.update { it.copy(replaying = false) }
        }
    }

    /** Shows another route. Its ribbons are whatever the channel holds for its anchors (nothing after a fresh start). */
    fun selectRoute(id: String) {
        val r = data.route(id)
        if (r.id == _state.value.routeId) return
        stopRecording()
        _state.update { it.copy(routeId = r.id, scenarioId = null, briefing = briefingFor(r.id)) }
    }

    fun clear() {
        replayJob?.cancel()
        synchronized(knots) { knots.clear() }
        _state.update { it.copy(scenarioId = null, replaying = false, replayDone = 0, replayTotal = 0, knotCount = 0, lastKnot = null, log = emptyList(), briefing = briefingNow()) }
    }

    /** This car's agent starts tying knots from the car's own position and signals. */
    fun startRecording() {
        if (recorder != null) return
        recorder = PassDetector(current.route, current.anchorSet.anchors, pseudonym = Pseudonym.forDay(secret, LocalDate.now(Baseline.SEOUL)))
        _state.update { it.copy(recording = true, myKnots = 0) }
        log(ctx.getString(R.string.log_live_start, location.status.value.source))
        RecordService.start(ctx)
    }

    fun stopRecording() {
        if (recorder == null) return
        recorder = null
        _state.update { it.copy(recording = false) }
        log(ctx.getString(R.string.log_live_stop))
        RecordService.stop(ctx)
    }

    fun dismissNotice() = _state.update { it.copy(naviNotice = null) }

    /** The ribbons of one route as of now, for the screen and for agents that read the channel. */
    fun ribbons(routeId: String = _state.value.routeId, nowMs: Long = System.currentTimeMillis()): List<RibbonState> {
        val snapshot = synchronized(knots) { knots.toList() }
        return engines.getValue(data.route(routeId).id).states(snapshot, nowMs)
    }

    fun scenarioTitle(id: String): String {
        val s = data.scenarios.firstOrNull { it.id == id } ?: return id
        return if (ctx.resources.getBoolean(R.bool.english_names)) s.titleEn ?: s.title else s.title
    }

    private fun addKnots(new: List<Knot>, who: String) {
        if (new.isEmpty()) return
        val count = synchronized(knots) { knots += new; knots.size }
        _state.update { it.copy(knotCount = count, lastKnot = new.last(), briefing = briefingNow()) }
        log(ctx.getString(R.string.log_knots, who, new.size))
    }

    private fun briefingNow(): Briefing = briefingFor(_state.value.routeId)

    private fun briefingFor(routeId: String): Briefing = Briefing.build(data.route(routeId).anchorSet.anchors, ribbons(routeId))

    private fun log(line: String) = _state.update { it.copy(log = (listOf(line) + it.log).take(40)) }

    companion object {
        @Volatile private var instance: RibbonChannel? = null

        fun get(context: Context): RibbonChannel =
            instance ?: synchronized(this) { instance ?: RibbonChannel(context).also { instance = it } }

        /** A random per-install secret. It never leaves the device; only the daily HMAC of it does. */
        private fun deviceSecret(ctx: Context): ByteArray {
            val prefs = ctx.getSharedPreferences("ribbon", Context.MODE_PRIVATE)
            prefs.getString("secret", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val s = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("secret", Base64.encodeToString(s, Base64.NO_WRAP)).apply()
            return s
        }
    }
}
