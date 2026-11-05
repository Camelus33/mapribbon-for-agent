package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.detect.Sample
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.WiperLevel

/** A straight 20 km road heading east along latitude 38.0. */
object Road {
    private const val LAT = 38.0
    private const val LON0 = 128.0
    // Same spherical radius as the haversine in geo, so "along" distances match exactly.
    private val metersPerDegLon = 6_371_008.8 * Math.PI / 180 * kotlin.math.cos(Math.toRadians(LAT))
    val route = RouteIndex((0..20).map { LngLat(LON0 + it * 1000 / metersPerDegLon, LAT) })
    fun at(alongM: Double) = LngLat(LON0 + alongM / metersPerDegLon, LAT)

    fun anchor(id: String, type: AnchorType, alongM: Int, endAlongM: Int = alongM, before: Int = 300, after: Int = 300) = Anchor(
        id = id, type = type, name = id, dir = "E", alongM = alongM, endAlongM = endAlongM,
        zoneBeforeM = before, zoneAfterM = after, lat = LAT, lon = at(alongM.toDouble()).lon, source = "test",
    )
}

/** What the car does at a given distance along the road. Defaults: steady speed, nothing switched on. */
data class Drive(
    val fromM: Double = 0.0,
    val toM: Double = 19_900.0,
    val kmh: Double = 90.0,
    val startMs: Long = 1_000_000_000L,
    val noFix: (Double) -> Boolean = { false },
    val brake: (Double) -> Boolean = { false },
    val speedAt: (Double) -> Double? = { null },
    val turn: (Double) -> TurnSignal = { TurnSignal.OFF },
    val wheel: (Double) -> Double? = { null },
    val wiper: (Double) -> Boolean = { false },
    val fog: (Double) -> Boolean = { false },
    val wiperLevel: (Double) -> WiperLevel? = { null },
    val lowBeam: (Double) -> Boolean = { false },
    val rearFog: (Double) -> Boolean = { false },
    val tempC: (Double) -> Double? = { null },
)

/** One sample per second. Speed is reported only when `speedAt` gives one; otherwise the detector derives it. */
fun Drive.samples(): List<Sample> {
    val out = mutableListOf<Sample>()
    var along = fromM
    var t = startMs
    while (along <= toM) {
        out += Sample(
            tMs = t,
            fix = if (noFix(along)) null else Road.at(along),
            speedKmh = speedAt(along),
            brake = brake(along),
            turnSignal = turn(along),
            wheelAngleDeg = wheel(along),
            wiper = wiper(along),
            fogLight = fog(along),
            wiperLevel = wiperLevel(along),
            lowBeam = lowBeam(along),
            rearFog = rearFog(along),
            outsideTempC = tempC(along),
        )
        val v = speedAt(along) ?: kmh
        along += v / 3.6
        t += 1000
    }
    return out
}

fun knot(anchorId: String, pseudonym: String, exitedAtMs: Long, f: Knot.() -> Knot = { this }) =
    Knot(anchorId = anchorId, dir = "E", pseudonym = pseudonym, enteredAtMs = exitedAtMs - 30_000, exitedAtMs = exitedAtMs).f()
