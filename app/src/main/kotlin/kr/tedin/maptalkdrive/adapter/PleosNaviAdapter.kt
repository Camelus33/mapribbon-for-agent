package kr.tedin.maptalkdrive.adapter

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.tedin.maptalkdrive.port.NaviEvent
import kr.tedin.maptalkdrive.port.NaviPort
import kr.tedin.maptalkdrive.port.PortStatus

/**
 * Route events from the car's navigation through Pleos NaviHelper. NaviHelper gives the destination and the trip
 * length but not the route line, so the app matches the event to its own demo route.
 */
class PleosNaviAdapter(context: Context) : NaviPort {
    private val ctx = context.applicationContext
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<NaviEvent> = _events
    private val _status = MutableStateFlow(PortStatus("Pleos NaviHelper", live = false, note = "시작 전"))
    override val status: StateFlow<PortStatus> = _status
    private var helper: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            _events.tryEmit(NaviEvent.RouteStarted(info.name, info.distance))
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            _events.tryEmit(NaviEvent.Arrived)
        }
        override fun onRouteCancelled() {
            _events.tryEmit(NaviEvent.Cancelled)
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
        }
    }

    override fun start() {
        helper = try {
            NaviHelper(ctx).also { it.initialize(); it.addListener(listener) }
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper unavailable", e)
            null
        }
        _status.value = if (helper != null) PortStatus("Pleos NaviHelper", live = true, note = "경로 이벤트 대기")
        else PortStatus("Pleos NaviHelper", live = false, note = "내비 서비스 없음")
    }

    override fun stop() {
        helper?.let { h -> runCatching { h.removeListener(listener) }; runCatching { h.release() } }
        helper = null
    }

    private companion object { const val TAG = "PleosNavi" }
}
