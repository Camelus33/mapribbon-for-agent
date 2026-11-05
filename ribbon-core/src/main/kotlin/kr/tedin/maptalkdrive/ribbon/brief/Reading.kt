package kr.tedin.maptalkdrive.ribbon.brief

import kotlinx.serialization.Serializable
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.Reason

/**
 * What a different place may mean for the driver, read from its reasons; the screen says it as a possibility. Hard
 * braking at a camera is the camera's doing, not a hold-up.
 *
 * `severity` is how much it costs to meet it unprepared (ice the most, a camera slowdown the least): common sense, not
 * fitted to data. `family` groups readings that are one thing where they follow each other on the road.
 */
@Serializable
enum class Reading(val severity: Double, val family: String) {
    ICE(5.0, "ice"),
    QUEUE(4.0, "traffic"),
    HOLDUP(4.0, "traffic"),
    THICK_FOG(4.0, "fog"),
    FOG(3.0, "fog"),
    HEAVY_RAIN(3.0, "wet"),
    RAIN(2.0, "wet"),
    POOR_LIGHT(2.0, "light"),
    EXIT_BUSY(2.0, "merge"),
    CAMERA_BRAKE(1.5, "camera"),
    SLOW(1.5, "slow"),
    OTHER(1.0, "other");

    companion object {
        /**
         * The most severe reading among all of a place's reasons, not only its top one: the reasons are ranked by how
         * far they stand out, a reading by what it costs. Low beams by day next to fast wipers are the rain's doing, so
         * the place reads as heavy rain; fast wipers grade rain up, rear fog lights fog, and ice outranks everything.
         */
        fun of(item: BriefingItem): Reading = evidence(item)?.let { of(it.metric, item.anchor.type) } ?: OTHER

        /** The reason behind [of]: its icon and its count of cars are what the screen shows as evidence. */
        fun evidence(item: BriefingItem): Reason? =
            item.state.reasons.withIndex().maxWithOrNull(compareBy<IndexedValue<Reason>> { of(it.value.metric, item.anchor.type).severity }.thenByDescending { it.index })?.value

        fun of(metric: String, type: AnchorType): Reading = when (metric) {
            Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE -> RAIN
            Metric.WIPER_FAST_RATE -> HEAVY_RAIN
            Metric.FOG_TURNED_ON_RATE, Metric.FOG_ON_RATE -> FOG
            Metric.REAR_FOG_RATE -> THICK_FOG
            Metric.ICE_RISK_RATE -> ICE
            Metric.DAY_LOW_BEAM_RATE -> POOR_LIGHT
            Metric.HARD_BRAKE_RATE -> if (type == AnchorType.CAMERA) CAMERA_BRAKE else HOLDUP
            Metric.STOP_RATE -> QUEUE
            Metric.LATE_LANE_CHANGE_RATE -> EXIT_BUSY
            Metric.ENTRY_DECEL_KMH, Metric.SPEED_AT_KMH -> SLOW
            else -> OTHER
        }
    }
}

/** What a careful driver does before such a place: general safe driving, never route guidance. */
@Serializable
enum class CautionAction { SLOW_DOWN_EARLY, KEEP_DISTANCE, AVOID_SUDDEN_INPUTS, CHOOSE_LANE_EARLY, LIGHTS_ON }

fun Reading.actions(): List<CautionAction> = when (this) {
    Reading.ICE -> listOf(CautionAction.SLOW_DOWN_EARLY, CautionAction.AVOID_SUDDEN_INPUTS)
    Reading.QUEUE, Reading.HOLDUP -> listOf(CautionAction.KEEP_DISTANCE, CautionAction.SLOW_DOWN_EARLY)
    Reading.THICK_FOG, Reading.FOG -> listOf(CautionAction.SLOW_DOWN_EARLY, CautionAction.LIGHTS_ON)
    Reading.HEAVY_RAIN, Reading.RAIN -> listOf(CautionAction.SLOW_DOWN_EARLY, CautionAction.KEEP_DISTANCE)
    Reading.POOR_LIGHT -> listOf(CautionAction.LIGHTS_ON)
    Reading.EXIT_BUSY -> listOf(CautionAction.CHOOSE_LANE_EARLY)
    Reading.CAMERA_BRAKE, Reading.SLOW -> listOf(CautionAction.KEEP_DISTANCE)
    Reading.OTHER -> listOf(CautionAction.SLOW_DOWN_EARLY)
}
