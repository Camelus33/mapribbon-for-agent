package kr.tedin.maptalkdrive.port

import kr.tedin.maptalkdrive.ribbon.live.NaviRoute
import kr.tedin.maptalkdrive.ribbon.spec.WiperLevel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** The vehicle signals a knot is made of. Nulls mean "this car or platform does not report it". */
data class VehicleSignals(
    val brake: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val wheelAngleDeg: Double? = null,
    val wiper: Boolean = false,
    val fogLight: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double? = null,
    /** The front wiper switch, coarsened; null until the car has answered. */
    val wiperLevel: WiperLevel? = null,
    val lowBeam: Boolean = false,
    val rearFog: Boolean = false,
    /** Outside air temperature from AAOS (ENV_OUTSIDE_TEMPERATURE); null where the car has none. */
    val outsideTempC: Double? = null,
)

data class Fix(val tMs: Long, val lat: Double, val lon: Double)

/** Where a port's data comes from right now, shown on screen so a demo never passes simulation off as the car. */
data class PortStatus(val source: String, val live: Boolean, val note: String? = null)

interface VehiclePort {
    val signals: StateFlow<VehicleSignals>
    val status: StateFlow<PortStatus>
    fun start()
    fun stop()
}

interface LocationPort {
    val fixes: SharedFlow<Fix>
    val status: StateFlow<PortStatus>
    fun start()
    fun stop()
}

sealed interface NaviEvent {
    data class RouteStarted(val name: String?, val distanceM: Int) : NaviEvent
    /** Where the navigation is going: its points, length and route option (it never gives the line itself). */
    data class RouteSet(val route: NaviRoute) : NaviEvent
    data object Arrived : NaviEvent
    data object Cancelled : NaviEvent
}

interface NaviPort {
    val events: SharedFlow<NaviEvent>
    val status: StateFlow<PortStatus>
    fun start()
    fun stop()
    /** A route typed in for testing; it goes down the same path as one from the navigation, marked as a test. */
    fun inject(route: NaviRoute)
}
