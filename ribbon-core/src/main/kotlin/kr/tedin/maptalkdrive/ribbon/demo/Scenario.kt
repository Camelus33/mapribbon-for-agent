package kr.tedin.maptalkdrive.ribbon.demo

import kotlinx.serialization.Serializable
import kr.tedin.maptalkdrive.ribbon.detect.Sample
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.WidthClass
import kr.tedin.maptalkdrive.ribbon.spec.WiperLevel

/**
 * A simulated drive for the offline demo. Every vehicle is described by what it does over stretches of the route,
 * not by raw samples, so the file stays small and readable. Position fixes are dropped inside tunnels, as in a real car.
 */
@Serializable
data class Scenario(
    val spec: String = "maptalk.scenario/1",
    val id: String,
    val title: String,
    val description: String,
    val titleEn: String? = null,
    /** The route this scenario drives; scenarios written before there were two routes drive the expressway. */
    val routeId: String = "SY60E",
    val vehicles: List<SimVehicle>,
)

@Serializable
data class SimVehicle(
    val id: String,
    /** The vehicle starts this many minutes before "now", so its knots land inside the recent windows. */
    val startMinutesAgo: Int,
    val kmh: Double = 95.0,
    val widthClass: WidthClass? = null,
    /** Outside temperature the car reports on the whole drive; null for a car that reports none. */
    val tempC: Double? = null,
    val segments: List<Segment> = emptyList(),
)

/** Overrides for one stretch of the route. A null field leaves the default (off, or the vehicle's cruising speed). */
@Serializable
data class Segment(
    val fromM: Double,
    val toM: Double,
    val kmh: Double? = null,
    val wiper: Boolean? = null,
    val fog: Boolean? = null,
    val brake: Boolean? = null,
    val turn: TurnSignal? = null,
    val wheelDeg: Double? = null,
    /** The wiper switch; setting it also switches the wipers on (unless `wiper` says otherwise). */
    val wiperLevel: WiperLevel? = null,
    val lowBeam: Boolean? = null,
    val rearFog: Boolean? = null,
    val tempC: Double? = null,
)

object ScenarioDrive {
    /**
     * One sample per `stepMs` from the route start to its end. `speedKmh` is reported like a car's speed sensor;
     * set `reportSpeed = false` to make the detector derive it from fixes.
     */
    fun samples(
        v: SimVehicle,
        route: RouteIndex,
        anchors: List<Anchor>,
        startMs: Long,
        stepMs: Long = 1_000,
        fromM: Double = 0.0,
        toM: Double = route.lengthM - 1,
        reportSpeed: Boolean = true,
    ): Sequence<Sample> = sequence {
        val tunnels = anchors.filter { it.type == AnchorType.TUNNEL }
        var along = fromM
        var t = startMs
        while (along <= toM) {
            val seg = v.segments.filter { along >= it.fromM && along < it.toM }
            fun <T> pick(f: (Segment) -> T?): T? = seg.lastOrNull { f(it) != null }?.let(f)
            val kmh = pick { it.kmh } ?: v.kmh
            val level = pick { it.wiperLevel }
            val wiperOn = pick { it.wiper } ?: (level != null && level != WiperLevel.OFF)
            val inTunnel = tunnels.any { along > it.alongM + 20 && along < it.endAlongM - 20 }
            yield(
                Sample(
                    tMs = t,
                    fix = if (inTunnel) null else route.pointAt(along),
                    speedKmh = if (reportSpeed) kmh else null,
                    brake = pick { it.brake } ?: false,
                    turnSignal = pick { it.turn } ?: TurnSignal.OFF,
                    wheelAngleDeg = pick { it.wheelDeg } ?: 0.0,
                    wiper = wiperOn,
                    fogLight = pick { it.fog } ?: false,
                    wiperLevel = level ?: if (wiperOn) WiperLevel.LOW else WiperLevel.OFF,
                    lowBeam = pick { it.lowBeam } ?: false,
                    rearFog = pick { it.rearFog } ?: false,
                    outsideTempC = pick { it.tempC } ?: v.tempC,
                ),
            )
            along += kmh / 3.6 * stepMs / 1000.0
            t += stepMs
        }
    }
}
