package kr.tedin.maptalkdrive.adapter

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RoutePointType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.port.NaviEvent
import kr.tedin.maptalkdrive.port.NaviPort
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.ribbon.live.NaviRoute
import kr.tedin.maptalkdrive.ribbon.live.NaviRouteDraft
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption
import kotlin.math.abs
import ai.pleos.playground.navi.constants.RouteOption as PleosRouteOption

/**
 * Where the car's navigation is going, through Pleos NaviHelper. The navigation gives points (destination, waypoints,
 * where the car is), its own route length and route option, never the line: the ribbon server draws the line.
 *
 * Any route event (routing done, guidance started, re-routed, destination or waypoints or option changed, or route
 * guidance found running) starts one round of questions after a short pause, since several events come together. The
 * answers arrive through separate callbacks in no fixed order; [NaviRouteDraft] collects them, and the route goes out
 * when all are in, or after a timeout as soon as the destination is known. Every callback is logged under "PleosNavi":
 * which events a real car's navigation sends, and when, is still to be seen.
 */
class PleosNaviAdapter(context: Context) : NaviPort {
    private val ctx = context.applicationContext
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<NaviEvent> = _events
    private val _status = MutableStateFlow(PortStatus("Pleos NaviHelper", live = false, note = ctx.getString(R.string.port_not_started)))
    override val status: StateFlow<PortStatus> = _status
    private var helper: NaviHelper? = null
    private val main = Handler(Looper.getMainLooper())

    // All of the draft state below is touched on the main thread only (callbacks come on binder threads and post here).
    private var draft: NaviRouteDraft? = null
    private var tripAsked = false
    private var lastStartDistanceM: Int? = null
    private var last: NaviRoute? = null
    private var askedOnce = false

    // The SDK binds to the navigation service asynchronously and drops requests sent before it is bound,
    // so the "are you there" probe is repeated a few times after start.
    private val probe = Runnable { helper?.let { h -> runCatching { h.getRouteStateInfo() } } }
    private val ask = Runnable { askAll() }
    private val giveUp = Runnable { finish(timedOut = true) }

    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() = event("onRoutingCompleted")
        override fun onReRouteEnded() = event("onReRouteEnded")
        override fun onDestinationChanged() = event("onDestinationChanged")
        override fun onWaypointChanged(info: WaypointChangedInfo) = event("onWaypointChanged")
        override fun onRouteOptionChanged(option: PleosRouteOption) = event("onRouteOptionChanged $option")
        override fun onRouteStarted(info: RouteStartInfo) {
            log("onRouteStarted ${info.distance} m")
            _events.tryEmit(NaviEvent.RouteStarted(info.name, info.distance))
            main.post {
                lastStartDistanceM = info.distance.takeIf { it > 0 }
                draft?.startDistanceM = lastStartDistanceM
            }
            schedule()
        }
        override fun onNavigationStatus(status: NavigationStatus) {
            log("onNavigationStatus $status")
            if (status == NavigationStatus.ROUTE_DRIVE) schedule()
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            log("onDestinationArrived")
            _events.tryEmit(NaviEvent.Arrived)
        }
        override fun onRouteCancelled() {
            log("onRouteCancelled")
            main.post { last = null; lastStartDistanceM = null; draft = null }
            _events.tryEmit(NaviEvent.Cancelled)
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
        }

        // Answers.
        override fun onRouteStateInfo(info: RouteStateInfo) {
            log("onRouteStateInfo option=${info.routeOption}")
            _status.value = PortStatus("Pleos NaviHelper", live = true, note = ctx.getString(R.string.port_navi_connected))
            main.post {
                draft?.let { it.option = option(info.routeOption); check() }
                // The first answer proves the navigation is there: ask once whether a route is already running.
                if (!askedOnce) { askedOnce = true; schedule() }
            }
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            log("onDestinationInfo ${info.name} remain=${info.remainDistance}")
            main.post {
                val d = draft ?: return@post
                if (info.latitude != 0.0 || info.longitude != 0.0) {
                    d.destination = LatLon(info.latitude, info.longitude)
                    d.destinationName = info.name.takeIf { it.isNotBlank() }
                    d.remainDistanceM = info.remainDistance.takeIf { it > 0 }
                }
                check()
            }
        }
        override fun onWayPointInfo(info: List<WaypointInfo>) {
            log("onWayPointInfo ${info.size}")
            main.post { draft?.let { d -> d.waypoints = info.map { LatLon(it.latitude, it.longitude) }; check() } }
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            log("onCurrentLocationInfo heading=${info.heading}")
            main.post {
                draft?.let { d ->
                    if (info.latitude != 0.0 || info.longitude != 0.0) d.current = LatLon(info.latitude, info.longitude)
                    d.headingDeg = info.heading.takeIf { !it.isNaN() }
                    check()
                }
            }
        }
        override fun onTripInfo(info: TripInfo) {
            log("onTripInfo ${info.distance} m")
            main.post {
                val d = draft ?: return@post
                d.tripDistanceM = info.distance.takeIf { it > 0 }
                val points = info.summary?.routePoints.orEmpty()
                if (d.current == null) points.firstOrNull { it.pointType == RoutePointType.ORIGIN }?.let { d.current = LatLon(it.latitude, it.longitude) }
                if (d.waypoints == null) d.waypoints = points.filter { it.pointType == RoutePointType.WAYPOINT }.map { LatLon(it.latitude, it.longitude) }
                check()
            }
        }
    }

    override fun start() {
        helper = try {
            NaviHelper(ctx).also { it.initialize(); it.addListener(listener) }
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper unavailable", e)
            null
        }
        _status.value = if (helper != null) PortStatus("Pleos NaviHelper", live = false, note = ctx.getString(R.string.port_navi_registered))
        else PortStatus("Pleos NaviHelper", live = false, note = ctx.getString(R.string.port_navi_missing))
        if (helper != null) PROBE_AT_MS.forEach { main.postDelayed(probe, it) }
    }

    override fun stop() {
        main.removeCallbacks(probe); main.removeCallbacks(ask); main.removeCallbacks(giveUp)
        helper?.let { h -> runCatching { h.removeListener(listener) }; runCatching { h.release() } }
        helper = null
    }

    override fun inject(route: NaviRoute) {
        log("test route to ${route.destination}")
        main.post { last = null }
        _events.tryEmit(NaviEvent.RouteSet(route))
    }

    private fun event(name: String) { log(name); schedule() }

    /** Several events come together; ask once, a moment after the last of them. */
    private fun schedule() {
        main.removeCallbacks(ask)
        main.postDelayed(ask, ASK_AFTER_MS)
    }

    private fun askAll() {
        val h = helper ?: return
        draft = NaviRouteDraft().also { it.startDistanceM = lastStartDistanceM }
        tripAsked = false
        runCatching { h.getDestinationInfo() }
        runCatching { h.getWaypointInfo() }
        runCatching { h.getCurrentLocationInfo() }
        runCatching { h.getRouteStateInfo() }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, GIVE_UP_MS)
    }

    private fun check() {
        val d = draft ?: return
        if (d.complete()) { finish(timedOut = false); return }
        // Without a length the route cannot be checked against the navigation's: ask for a trip estimate, once.
        val dest = d.destination
        if (dest != null && d.distanceM == null && !tripAsked) {
            tripAsked = true
            runCatching { helper?.requestTrip(TripRequest(dest.lat, dest.lon, "", d.destinationName ?: "", "")) }
        }
    }

    private fun finish(timedOut: Boolean) {
        val d = draft ?: return
        val route = d.result()
        if (route == null) { if (timedOut) draft = null; return }
        draft = null
        main.removeCallbacks(giveUp)
        val prev = last
        // The same route asked again (a re-route that changed nothing) is not news, unless its length moved.
        if (prev != null && prev.samePlaces(route) && sameLength(prev.naviDistanceM, route.naviDistanceM)) return
        last = route
        log("route set → ${route.destinationName} ${route.naviDistanceM} m (${if (timedOut) "partial" else "complete"})")
        _events.tryEmit(NaviEvent.RouteSet(route))
    }

    private fun sameLength(a: Int?, b: Int?): Boolean = a == b || (a != null && b != null && abs(a - b) <= a / 100)

    private fun option(o: PleosRouteOption?): RouteOption = o?.let { runCatching { RouteOption.valueOf(it.name) }.getOrNull() } ?: RouteOption.RECOMMENDED

    private fun log(msg: String) = Log.i(TAG, msg)

    private companion object {
        const val TAG = "PleosNavi"
        val PROBE_AT_MS = listOf(1_000L, 3_000L, 6_000L)
        const val ASK_AFTER_MS = 1_500L
        const val GIVE_UP_MS = 5_000L
    }
}
