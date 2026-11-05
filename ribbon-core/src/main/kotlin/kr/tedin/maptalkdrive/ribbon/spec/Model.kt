package kr.tedin.maptalkdrive.ribbon.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a ribbon can hang on. Facilities (tunnel, junction, toll gate, camera) come from public data or OSM, never from
 * users. A SEGMENT is a stretch of open road between them, about 2 km, cut from the route itself, so what happens
 * between two facilities has a place too. Segments stop at tunnel mouths: inside a tunnel there is no position.
 */
@Serializable
enum class AnchorType { TUNNEL, JUNCTION, TOLL, CAMERA, SEGMENT }

/**
 * One anchor on one carriageway. Positions are meters along the route line. A tunnel and a segment span
 * [alongM, endAlongM]; every other type is a point (alongM == endAlongM). Vehicles tie a knot only inside the influence
 * zone; a segment's zone is the segment itself.
 */
@Serializable
data class Anchor(
    val id: String,
    val type: AnchorType,
    val name: String,
    val dir: String,
    val alongM: Int,
    val endAlongM: Int,
    val zoneBeforeM: Int,
    val zoneAfterM: Int,
    val lat: Double,
    val lon: Double,
    val source: String,
    val sourceRef: String? = null,
    val virtual: Boolean = false,
    val lengthM: Int? = null,
    /** The name in English for screens outside Korea: OSM name:en where it exists, otherwise romanised. */
    val nameEn: String? = null,
) {
    val zoneStartM: Int get() = alongM - zoneBeforeM
    val zoneEndM: Int get() = endAlongM + zoneAfterM
}

@Serializable
data class AnchorSet(
    val spec: String,
    val routeId: String,
    val routeName: String,
    val routeNameEn: String? = null,
    val lengthM: Int,
    val attribution: String,
    val anchors: List<Anchor>,
)

@Serializable
enum class WidthClass { NARROW, MEDIUM, WIDE }

/**
 * What one vehicle saw while passing one anchor's zone. "Before" is the approach (zone start .. alongM),
 * "after" is the exit side (endAlongM .. zone end). A tunnel's interior has no position fixes and adds nothing here.
 *
 * On a segment "before" and "after" are its first and second half, `speedAtKmh` is the mean speed over it, and hard
 * braking and stops count only outside every facility's zone: those belong to the facility's own knot.
 */
@Serializable
data class Knot(
    val spec: String = KNOT_SPEC,
    val anchorId: String,
    val dir: String,
    val pseudonym: String,
    val enteredAtMs: Long,
    val exitedAtMs: Long,
    val speedInKmh: Double? = null,
    val speedAtKmh: Double? = null,
    val speedOutKmh: Double? = null,
    val stoppedS: Int = 0,
    val hardBrake: Boolean = false,
    val lateLaneChange: Boolean = false,
    val laneChangeBy: LaneChangeSignal? = null,
    val wiperBefore: Boolean = false,
    val wiperAfter: Boolean = false,
    val fogBefore: Boolean = false,
    val fogAfter: Boolean = false,
    val widthClass: WidthClass? = null,
) {
    companion object { const val KNOT_SPEC = "maptalk.knot/1" }
}

@Serializable
enum class LaneChangeSignal {
    @SerialName("turn_signal") TURN_SIGNAL,
    @SerialName("wheel") WHEEL,
}

@Serializable
enum class RibbonStatus { PUBLISHED, NOT_ENOUGH }

@Serializable
enum class Deviation { NONE, SOME, HIGH }

@Serializable
data class AnchorRef(val id: String, val type: AnchorType, val dir: String, val name: String, val virtual: Boolean)

/**
 * The records a ribbon is based on: those of the `minutes` before `endMs`. `lastMs` is when the most recent car of them
 * passed, rounded down to 5 minutes: it says how fresh the ribbon is without timing any one car.
 */
@Serializable
data class Window(val endMs: Long, val minutes: Int, val lastMs: Long? = null)

/** Why a ribbon is "different": one metric compared with its own usual value and with neighbouring anchors. */
@Serializable
data class Reason(val metric: String, val value: Double, val baseline: Double?, val neighbors: Double?, val level: Deviation)

/** The published state of one ribbon: `maptalk.ribbon/1`. Numbers only, plus a templated one-line gist. */
@Serializable
data class RibbonState(
    val spec: String = RIBBON_SPEC,
    val anchor: AnchorRef,
    val status: RibbonStatus,
    val window: Window? = null,
    val n: Int,
    val metrics: Map<String, Double> = emptyMap(),
    val baseline: Map<String, Double> = emptyMap(),
    val deviation: Deviation = Deviation.NONE,
    val reasons: List<Reason> = emptyList(),
    val gist: String,
) {
    companion object { const val RIBBON_SPEC = "maptalk.ribbon/1" }
}

/** Metric keys. Rates are 0..1, speeds km/h. */
object Metric {
    const val HARD_BRAKE_RATE = "hardBrakeRate"
    const val LATE_LANE_CHANGE_RATE = "lateLaneChangeRate"
    const val WIPER_TURNED_ON_RATE = "wiperTurnedOnRate"
    const val FOG_TURNED_ON_RATE = "fogTurnedOnRate"
    /** Segments: share of cars with fog lights on anywhere on the segment. */
    const val FOG_ON_RATE = "fogOnRate"
    const val WIPER_ON_RATE = "wiperOnRate"
    const val STOP_RATE = "stopRate"
    const val ENTRY_DECEL_KMH = "entryDecelKmh"
    const val SPEED_AT_KMH = "speedAtKmh"
}
