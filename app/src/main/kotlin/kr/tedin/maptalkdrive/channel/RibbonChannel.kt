package kr.tedin.maptalkdrive.channel

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.tedin.maptalkdrive.BuildConfig
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.adapter.PleosLocationAdapter
import kr.tedin.maptalkdrive.adapter.PleosNaviAdapter
import kr.tedin.maptalkdrive.adapter.PleosVehicleAdapter
import kr.tedin.maptalkdrive.demo.DemoData
import kr.tedin.maptalkdrive.demo.NaviPreset
import kr.tedin.maptalkdrive.demo.NaviPresets
import kr.tedin.maptalkdrive.demo.RouteData
import kr.tedin.maptalkdrive.net.KnotUploader
import kr.tedin.maptalkdrive.net.RibbonClient
import kr.tedin.maptalkdrive.port.NaviEvent
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.detect.Sample
import kr.tedin.maptalkdrive.ribbon.geo.ElevationProfile
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.live.NaviRoute
import kr.tedin.maptalkdrive.ribbon.pseudo.Pseudonym
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RouteMatch
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import kr.tedin.maptalkdrive.ribbon.spec.RouteSource
import java.security.SecureRandom
import java.time.LocalDate

/** Where the navigation's route stands: being drawn by the server, on screen, or not to be had. */
enum class LivePhase { LOADING, READY, FAILED }

/** Why the navigation's route could not be shown. */
enum class LiveError { UNREACHABLE, SERVER_LOADING, NO_ROUTE, NO_POSITION, OTHER }

/** The navigation's route as the screen needs to know it. */
data class LiveView(
    val phase: LivePhase,
    val source: RouteSource,
    /** The origin is where the car is (from the navigation or the car), not a typed test point. */
    val originIsCurrent: Boolean,
    val destinationName: String?,
    val routeId: String? = null,
    val match: RouteMatch? = null,
    /** Some knots behind the ribbons came from the verification simulator. */
    val simulated: Boolean = false,
    val ribbonsAtMs: Long? = null,
    val error: LiveError? = null,
    /** The ribbons could not be refreshed last time (the route itself is still on screen). */
    val stale: Boolean = false,
    /** The road's height has arrived and is drawn under the map. */
    val elevation: Boolean = false,
)

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
    /** The navigation's route, drawn by the ribbon server, when one is set. */
    val live: LiveView? = null,
)

/**
 * The ribbon channel of this device, one per process: the activity, the recording service and the content provider
 * other agents read through all see the same state.
 *
 * Two kinds of route are on offer. The bundled demo routes keep their ribbons here, from simulated cars run through the
 * shared ribbon-core engine. The navigation's route (the live route) is drawn by the ribbon server from the points the
 * navigation gives; its ribbons come from the server, and this car's knots on it go to the server.
 */
class RibbonChannel private constructor(context: Context) {
    private val ctx = context.applicationContext
    val data = DemoData.load(ctx)
    val vehicle = PleosVehicleAdapter(ctx)
    val location = PleosLocationAdapter(ctx)
    val navi = PleosNaviAdapter(ctx)
    private val client = RibbonClient(BuildConfig.RIBBON_SERVER_URL)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engines = data.routes.associate { it.id to RibbonEngine(it.anchorSet.anchors, data.baseline) }
    private val knots = mutableListOf<Knot>()
    private val secret = deviceSecret(ctx)
    private var replayJob: Job? = null
    @Volatile private var recorder: PassDetector? = null
    @Volatile private var recordingOn: String? = null
    private var portsStarted = false
    @Volatile private var lastFix: LatLon? = null

    /** The navigation's route as the server drew it, and the newest ribbons on it. */
    private class LiveRoute(
        val route: NaviRoute,
        val request: RouteRequest,
        val response: RouteResponse,
        /** Replaced once, when the road's height arrives. */
        @Volatile var data: RouteData,
        @Volatile var ribbons: List<RibbonState>? = null,
    )
    @Volatile private var live: LiveRoute? = null
    private var liveJob: Job? = null
    private var lastRouteAsked: NaviRoute? = null
    private val uploader = KnotUploader(client, scope, onSent = { n ->
        log(ctx.getString(R.string.log_upload, n))
        refreshLiveNow()
    })

    private val _state = MutableStateFlow(data.routes.first().id.let { ChannelState(routeId = it, briefing = briefingFor(it)) })
    val state: StateFlow<ChannelState> = _state

    /** The route on screen. */
    val current: RouteData get() = routeData(_state.value.routeId)

    init {
        scope.launch {
            navi.events.collect { e ->
                when (e) {
                    is NaviEvent.RouteSet -> onRouteSet(e.route)
                    is NaviEvent.RouteStarted -> note(ctx.getString(R.string.navi_route, e.name ?: ctx.getString(R.string.navi_destination), e.distanceM / 1000.0))
                    NaviEvent.Arrived -> note(ctx.getString(R.string.navi_arrived))
                    NaviEvent.Cancelled -> { note(ctx.getString(R.string.navi_cancelled)); clearLive() }
                }
            }
        }
        scope.launch {
            location.fixes.collect { fix ->
                lastFix = LatLon(fix.lat, fix.lon)
                val d = recorder ?: return@collect
                val sig = vehicle.signals.value
                val sample = Sample(
                    fix.tMs, LngLat(fix.lon, fix.lat), sig.speedKmh, sig.brake, sig.turnSignal, sig.wheelAngleDeg, sig.wiper, sig.fogLight,
                    wiperLevel = sig.wiperLevel, lowBeam = sig.lowBeam, rearFog = sig.rearFog, outsideTempC = sig.outsideTempC,
                )
                val tied = d.accept(sample)
                if (tied.isEmpty()) return@collect
                _state.update { it.copy(myKnots = it.myKnots + tied.size) }
                // Knots on the navigation's route belong on the server, where other cars' knots on the same road are.
                if (recordingOn != null && recordingOn == live?.response?.routeId) uploader.add(tied)
                addKnots(tied, ctx.getString(R.string.log_this_car))
            }
        }
    }

    fun startPorts() {
        if (portsStarted) return
        portsStarted = true
        vehicle.start(); location.start(); navi.start()
    }

    /** The bundled route or the navigation's route with this id; an unknown id falls back to the first bundled route. */
    fun routeData(id: String): RouteData = live?.takeIf { it.data.id == id }?.data ?: data.route(id)

    /** Whether a route id names a route the channel can answer for. */
    fun knows(id: String): Boolean = live?.data?.id == id || data.routes.any { it.id == id }

    // ---------------------------------------------------------------- the navigation's route

    /** The navigation set a route (or a test route was typed in): have the server draw it, then show its ribbons. */
    fun onRouteSet(route: NaviRoute) {
        lastRouteAsked = route
        val l = live
        if (l != null && l.route.samePlaces(route) && l.route.naviDistanceM == route.naviDistanceM && _state.value.live?.phase == LivePhase.READY) {
            selectRoute(l.data.id)
            refreshLiveNow()
            return
        }
        val req = route.request(lastFix)
        if (req == null) {
            _state.update { it.copy(live = LiveView(LivePhase.FAILED, route.source, route.originIsCurrent, route.destinationName, error = LiveError.NO_POSITION)) }
            return
        }
        liveJob?.cancel()
        _state.update { it.copy(live = LiveView(LivePhase.LOADING, route.source, route.originIsCurrent, route.destinationName)) }
        log(ctx.getString(R.string.log_live_route, route.destinationName ?: ctx.getString(R.string.navi_destination)))
        liveJob = scope.launch {
            val res = try {
                client.route(req)
            } catch (e: RibbonClient.Failure) {
                Log.w(TAG, "route request failed: ${e.message}")
                _state.update { it.copy(live = LiveView(LivePhase.FAILED, route.source, route.originIsCurrent, route.destinationName, error = errorOf(e))) }
                return@launch
            }
            val rd = withContext(Dispatchers.Default) { routeDataOf(route, res) }
            live = LiveRoute(route, req, res, rd)
            val wasRecording = recorder != null
            _state.update {
                it.copy(
                    routeId = rd.id, scenarioId = null, briefing = briefingFor(rd.id),
                    live = LiveView(LivePhase.READY, route.source, route.originIsCurrent, route.destinationName, rd.id, res.match),
                )
            }
            if (wasRecording) restartRecorder()
            launch { fetchElevation(live!!) }
            while (isActive) {
                fetchRibbons()
                delay(REFRESH_MS)
            }
        }
    }

    /** The demo preset the navigation's route came from, if it did. */
    fun livePreset(): NaviPreset? = NaviPresets.of(live?.route)

    /**
     * Sends the simulated cars of one bundled scenario down the navigation's route, for the demo: the server's earlier
     * simulated knots are cleared first, so one scenario's story is not mixed with another's (cars' knots stay), and
     * the new ones go up marked simulated, so the screen says so. The scenario's places are metres along its bundled
     * route, which is the same road from the same start.
     */
    fun runScenarioOnLive(id: String) {
        val l = live ?: return
        val scenario = data.scenarios.firstOrNull { it.id == id } ?: return
        replayJob?.cancel()
        selectRoute(l.data.id)
        _state.update { it.copy(scenarioId = id, replaying = true, replayDone = 0, replayTotal = scenario.vehicles.size) }
        log(ctx.getString(R.string.log_scenario, scenarioTitle(id)))
        replayJob = scope.launch {
            try {
                client.clearSimulated()
                val now = System.currentTimeMillis()
                val r = l.data
                val tied = mutableListOf<Knot>()
                for (v in scenario.vehicles.sortedByDescending { it.startMinutesAgo }) {
                    tied += withContext(Dispatchers.Default) {
                        val detector = PassDetector(r.route, r.anchorSet.anchors, pseudonym = "sim-${v.id}", widthClass = v.widthClass)
                        ScenarioDrive.samples(v, r.route, r.anchorSet.anchors, startMs = now - v.startMinutesAgo * 60_000L)
                            .flatMap { detector.accept(it) }.filter { it.exitedAtMs <= now }.toList()
                    }
                    _state.update { it.copy(replayDone = it.replayDone + 1) }
                }
                for (batch in tied.chunked(500)) client.upload(batch, simulated = true)
                tied.lastOrNull()?.let { k -> _state.update { it.copy(lastKnot = k) } }
                log(ctx.getString(R.string.log_live_sim, scenario.vehicles.size, tied.size))
                fetchRibbons()
            } catch (e: RibbonClient.Failure) {
                Log.w(TAG, "simulated cars not sent: ${e.message}")
                note(ctx.getString(R.string.live_sim_failed))
            } finally {
                _state.update { it.copy(replaying = false) }
            }
        }
    }

    /** Asks for the last route again after a failure. */
    fun retryLive() {
        val r = lastRouteAsked ?: return
        live = null
        onRouteSet(r)
    }

    private fun refreshLiveNow() {
        if (live == null) return
        scope.launch { fetchRibbons() }
    }

    private suspend fun fetchRibbons() {
        val l = live ?: return
        try {
            val r = try {
                client.ribbons(l.response.routeId)
            } catch (e: RibbonClient.Failure.NotFound) {
                // The server restarted and forgot the route: the same request gives the same route id back.
                client.route(l.request)
                client.ribbons(l.response.routeId)
            }
            if (live !== l) return
            l.ribbons = r.ribbons
            _state.update { s ->
                s.copy(
                    briefing = if (s.routeId == l.data.id) briefingFor(l.data.id) else s.briefing,
                    live = s.live?.copy(simulated = r.simulated, ribbonsAtMs = System.currentTimeMillis(), stale = false),
                )
            }
        } catch (e: RibbonClient.Failure) {
            Log.i(TAG, "ribbons not refreshed: ${e.message}")
            _state.update { s -> s.copy(live = s.live?.copy(stale = true)) }
        }
    }

    /**
     * The road's height, which the server builds after drawing the route (a long route takes some seconds): asked for
     * a few times with growing pauses, then drawn under the map like on the bundled routes.
     */
    private suspend fun fetchElevation(l: LiveRoute) {
        for (wait in ELEVATION_WAITS_MS) {
            delay(wait)
            if (live !== l) return
            val file = try {
                client.elevation(l.response.routeId)
            } catch (e: RibbonClient.Failure.NotFound) {
                // The server restarted and forgot the route: asking for it again rebuilds the same route and profile.
                runCatching { client.route(l.request) }
                null
            } catch (e: RibbonClient.Failure) {
                Log.i(TAG, "no elevation yet: ${e.message}")
                null
            } ?: continue
            val profile = runCatching { ElevationProfile(file) }.getOrNull() ?: return
            l.data = RouteData(l.data.anchorSet, l.data.route, profile)
            _state.update { s -> s.copy(live = s.live?.copy(elevation = true)) }
            return
        }
    }

    private fun clearLive() {
        liveJob?.cancel()
        val was = live ?: return run { _state.update { it.copy(live = null) } }
        val onScreen = _state.value.routeId == was.data.id
        if (onScreen) stopRecording()
        live = null
        val first = data.routes.first().id
        _state.update { s ->
            if (s.routeId == was.data.id) s.copy(live = null, routeId = first, briefing = briefingFor(first)) else s.copy(live = null)
        }
    }

    /** The server's anchors under a name the screen reads as "<origin> → <destination>". */
    private fun routeDataOf(route: NaviRoute, res: RouteResponse): RouteData {
        val dest = route.destinationName?.replace("→", " ")?.trim()?.takeIf { it.isNotEmpty() }
        // A preset names its start; any other test input has only a position, so it says it is one.
        val preset = NaviPresets.of(route)
        val (fromKo, fromEn) = when {
            route.originIsCurrent -> "현재 위치" to "Current location"
            preset != null -> preset.originName to preset.originNameEn
            else -> "시험 출발지" to "Test start"
        }
        val set = res.anchorSet.copy(
            routeName = "내비 경로: $fromKo → ${dest ?: "목적지"}",
            routeNameEn = "Navigation route: $fromEn → ${dest ?: "Destination"}",
        )
        return RouteData(set, RouteIndex(res.route.geometry.coordinates.map { LngLat(it[0], it[1]) }))
    }

    private fun errorOf(e: RibbonClient.Failure): LiveError = when (e) {
        is RibbonClient.Failure.Unreachable -> LiveError.UNREACHABLE
        is RibbonClient.Failure.Loading -> LiveError.SERVER_LOADING
        is RibbonClient.Failure.NoRoute -> LiveError.NO_ROUTE
        else -> LiveError.OTHER
    }

    // ---------------------------------------------------------------- the bundled demo routes

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

    /** Shows another route: a bundled one, or the navigation's route by its id. */
    fun selectRoute(id: String) {
        val r = routeData(id)
        if (r.id == _state.value.routeId) return
        stopRecording()
        _state.update { it.copy(routeId = r.id, scenarioId = null, briefing = briefingFor(r.id)) }
    }

    fun clear() {
        replayJob?.cancel()
        synchronized(knots) { knots.clear() }
        _state.update { it.copy(scenarioId = null, replaying = false, replayDone = 0, replayTotal = 0, knotCount = 0, lastKnot = null, log = emptyList(), briefing = briefingNow()) }
    }

    // ---------------------------------------------------------------- this car

    /** This car's agent starts tying knots from the car's own position and signals. */
    fun startRecording() {
        if (recorder != null) return
        restartRecorder()
        _state.update { it.copy(recording = true, myKnots = 0) }
        log(ctx.getString(R.string.log_live_start, location.status.value.source))
        RecordService.start(ctx)
    }

    /** A detector on the route on screen (again, when the navigation's route changed under a recording). */
    private fun restartRecorder() {
        val r = current
        recordingOn = r.id
        recorder = PassDetector(r.route, r.anchorSet.anchors, pseudonym = Pseudonym.forDay(secret, LocalDate.now(Baseline.SEOUL)))
    }

    fun stopRecording() {
        if (recorder == null) return
        recorder = null
        recordingOn = null
        _state.update { it.copy(recording = false) }
        log(ctx.getString(R.string.log_live_stop))
        RecordService.stop(ctx)
        scope.launch { uploader.flush() }
    }

    fun dismissNotice() = _state.update { it.copy(naviNotice = null) }

    /**
     * The ribbons of one route as of now, for the screen and for agents that read the channel. On the navigation's
     * route they are the server's; before the first answer every anchor reads "not enough cars yet".
     */
    fun ribbons(routeId: String = _state.value.routeId, nowMs: Long = System.currentTimeMillis()): List<RibbonState> {
        live?.takeIf { it.data.id == routeId }?.let { l ->
            return l.ribbons ?: RibbonEngine(l.data.anchorSet.anchors).states(emptyList(), nowMs)
        }
        val snapshot = synchronized(knots) { knots.toList() }
        return engines.getValue(data.route(routeId).id).states(snapshot, nowMs)
    }

    fun scenarioTitle(id: String): String {
        val s = data.scenarios.firstOrNull { it.id == id } ?: return id
        return if (ctx.resources.getBoolean(R.bool.english_names)) s.titleEn ?: s.title else s.title
    }

    /** The scenario's short name for its card, or its title when it has none. */
    fun scenarioLabel(id: String): String {
        val s = data.scenarios.firstOrNull { it.id == id } ?: return id
        val english = ctx.resources.getBoolean(R.bool.english_names)
        return (if (english) s.labelEn ?: s.label else s.label) ?: scenarioTitle(id)
    }

    private fun addKnots(new: List<Knot>, who: String) {
        if (new.isEmpty()) return
        val count = synchronized(knots) { knots += new; knots.size }
        _state.update { it.copy(knotCount = count, lastKnot = new.last(), briefing = briefingNow()) }
        log(ctx.getString(R.string.log_knots, who, new.size))
    }

    private fun briefingNow(): Briefing = briefingFor(_state.value.routeId)

    private fun briefingFor(routeId: String): Briefing = Briefing.build(routeData(routeId).anchorSet.anchors, ribbons(routeId))

    private fun note(text: String) {
        _state.update { it.copy(naviNotice = text) }
        log(text)
    }

    private fun log(line: String) = _state.update { it.copy(log = (listOf(line) + it.log).take(40)) }

    companion object {
        private const val TAG = "RibbonChannel"
        private const val REFRESH_MS = 60_000L
        // A long route is built at the API's pace (600 points a minute) the first time: keep asking for up to 10 minutes.
        private val ELEVATION_WAITS_MS = listOf(0L, 1_500L, 3_000L, 5_000L, 8_000L, 13_000L) + List(28) { 20_000L }

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
