package kr.tedin.maptalkdrive.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.channel.LiveError
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.spec.MatchVerdict
import kr.tedin.maptalkdrive.ribbon.spec.RouteMatch
import kr.tedin.maptalkdrive.ribbon.brief.CautionAction
import kr.tedin.maptalkdrive.ribbon.brief.Reading
import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.Reason
import kotlin.math.roundToInt

/**
 * The few words left on the screens, from resources so they follow the system language. What was different is said
 * with an icon; words name places, the legend, and the controls. Every number comes from the ribbon itself.
 */
class Words(private val r: Resources) {
    private val english = r.getBoolean(R.bool.english_names)

    fun name(a: Anchor): String = if (english) a.nameEn ?: a.name else a.name

    /**
     * Start and end of the route. Names read "<road> <start> → <end>"; the English one puts a colon after the road
     * ("Seoul-Yangyang Expressway: Inje IC → Yangyang JC") because its road name has spaces.
     */
    /** The road a route runs on, without its trip: "1100도로", "1100 Road over Hallasan". */
    fun road(set: AnchorSet): String {
        val name = if (english) set.routeNameEn ?: set.routeName else set.routeName
        return if (": " in name) name.substringBefore(": ") else name.substringBefore(' ')
    }

    fun ends(set: AnchorSet): Pair<String, String> {
        val name = if (english) set.routeNameEn ?: set.routeName else set.routeName
        val trip = if (": " in name) name.substringAfter(": ") else name.substringAfter(' ')
        val parts = trip.split(" → ")
        return parts.first() to parts.last()
    }

    /**
     * The place as a driver names it. Tunnel issues are about the exit; virtual cameras are just cameras here; a segment
     * is its stretch of kilometres ("26.0–27.3 km 구간").
     */
    fun place(item: BriefingItem): String = when (item.anchor.type) {
        AnchorType.TUNNEL -> r.getString(R.string.tunnel_exit, name(item.anchor))
        AnchorType.CAMERA -> r.getString(R.string.speed_camera)
        AnchorType.SEGMENT -> r.getString(R.string.segment_place, name(item.anchor))
        else -> name(item.anchor)
    }

    /** Why the navigation's route is not on screen. */
    fun liveError(e: LiveError?): String = r.getString(
        when (e) {
            LiveError.UNREACHABLE -> R.string.live_failed_unreachable
            LiveError.SERVER_LOADING -> R.string.live_failed_loading
            LiveError.NO_ROUTE -> R.string.live_failed_no_route
            LiveError.NO_POSITION -> R.string.live_failed_no_position
            else -> R.string.live_failed_other
        },
    )

    /** Whether the line on screen is the navigation's route, judged by its length, with both lengths when they differ. */
    fun match(m: RouteMatch): String = when (m.verdict) {
        // The title already gives the length; only a difference needs both.
        MatchVerdict.SAME -> r.getString(R.string.match_same)
        MatchVerdict.DIFFERENT -> r.getString(R.string.match_different, at(m.lengthM), at(m.naviDistanceM ?: 0))
        MatchVerdict.UNKNOWN -> r.getString(R.string.match_unknown)
    }

    /** What was different, for the legend and for accessibility; on the map and cards it is an icon. */
    fun issue(metric: String?): String = r.getString(
        when (metric) {
            Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE -> R.string.issue_wiper
            Metric.FOG_TURNED_ON_RATE, Metric.FOG_ON_RATE -> R.string.issue_fog
            Metric.WIPER_FAST_RATE -> R.string.issue_wiper_fast
            Metric.REAR_FOG_RATE -> R.string.issue_rear_fog
            Metric.DAY_LOW_BEAM_RATE -> R.string.issue_day_beam
            Metric.ICE_RISK_RATE -> R.string.issue_ice
            Metric.LATE_LANE_CHANGE_RATE -> R.string.issue_late_merge
            Metric.HARD_BRAKE_RATE -> R.string.issue_hard_brake
            Metric.STOP_RATE -> R.string.issue_stop
            Metric.ENTRY_DECEL_KMH -> R.string.issue_entry_decel
            else -> R.string.issue_other
        },
    )

    /**
     * What the behaviour may mean for the driver, always said as a possibility (see [Reading]). The issue icon and the
     * count of cars next to it are the evidence; this line is the reading of it.
     */
    fun meaning(item: BriefingItem): String = reading(Reading.of(item))

    fun reading(x: Reading): String =
        r.getString(
            when (x) {
                Reading.ICE -> R.string.meaning_ice
                Reading.QUEUE -> R.string.meaning_queue
                Reading.HOLDUP -> R.string.meaning_holdup
                Reading.THICK_FOG -> R.string.meaning_thick_fog
                Reading.FOG -> R.string.meaning_fog
                Reading.HEAVY_RAIN -> R.string.meaning_heavy_rain
                Reading.RAIN -> R.string.meaning_rain
                Reading.POOR_LIGHT -> R.string.meaning_dark
                Reading.EXIT_BUSY -> R.string.meaning_exit_busy
                Reading.CAMERA_BRAKE -> R.string.meaning_camera_brake
                Reading.SLOW -> R.string.meaning_slow
                Reading.OTHER -> R.string.meaning_other
            },
        )

    /** What a careful driver does there: general safe driving, never route guidance. */
    fun action(a: CautionAction): String = r.getString(
        when (a) {
            CautionAction.SLOW_DOWN_EARLY -> R.string.action_slow
            CautionAction.KEEP_DISTANCE -> R.string.action_distance
            CautionAction.AVOID_SUDDEN_INPUTS -> R.string.action_smooth
            CautionAction.CHOOSE_LANE_EARLY -> R.string.action_lane
            CautionAction.LIGHTS_ON -> R.string.action_lights
        },
    )

    /**
     * This time, in cars: "6/9대", "6 of 9". With eight or nine cars a percentage overstates precision, and a bare
     * "6/9" reads as a date. Speeds stay in km/h.
     */
    fun count(item: BriefingItem): String {
        val top = top(item) ?: return "${item.state.n}"
        return if (isSpeed(top)) "${top.value.roundToInt()} km/h"
        else r.getString(R.string.count_cars, (top.value * item.state.n).roundToInt(), item.state.n)
    }

    /**
     * The usual value on the same scale as [count]: out of the same number of cars ("usually ~1 of 9"), or "almost none"
     * when that rounds to zero. Null when there is no usual value (the place was judged against its neighbours).
     */
    fun usual(item: BriefingItem): String? {
        val top = top(item) ?: return null
        val b = top.baseline ?: return null
        if (isSpeed(top)) return r.getString(R.string.usual_value, "${b.roundToInt()} km/h")
        val cars = (b * item.state.n).roundToInt()
        return if (cars == 0) r.getString(R.string.usual_none) else r.getString(R.string.usual_cars, cars, item.state.n)
    }

    /** How long ago the most recent car passed. `lastMs` is already rounded down to 5 minutes by the channel. */
    fun lastCar(lastMs: Long, nowMs: Long): String {
        val min = ((nowMs - lastMs) / 60_000).toInt()
        return when {
            min < 5 -> r.getString(R.string.last_car_recent)
            min < 60 -> r.getString(R.string.last_car_min, min / 5 * 5)
            else -> r.getString(R.string.last_car_hour, min / 60)
        }
    }

    /** One place said in full, for accessibility. */
    fun describe(item: BriefingItem): String {
        val top = top(item)
        val cars = top?.takeIf { !isSpeed(it) }?.let { r.getString(R.string.count_cars_a11y, (it.value * item.state.n).roundToInt(), item.state.n) } ?: count(item)
        return listOfNotNull(
            meaning(item), place(item), issue(top?.metric), cars, usual(item), level(item.state.deviation),
            item.state.window?.let { minutes(it.minutes) },
        ).joinToString(", ")
    }

    fun plural(id: Int, n: Int): String = r.getQuantityString(id, n, n)

    fun level(d: Deviation): String = r.getString(
        when (d) {
            Deviation.HIGH -> R.string.legend_high
            Deviation.SOME -> R.string.legend_some
            Deviation.NONE -> R.string.legend_usual
        },
    )

    fun str(id: Int, vararg args: Any): String = r.getString(id, *args)

    fun minutes(m: Int): String = r.getString(R.string.minutes, m)

    /** Distance ahead for the preview card: whole km from 10 km, one decimal below, metres under 1 km. */
    fun ahead(m: Double): Pair<String, String> = when {
        m < 150 -> r.getString(R.string.soon) to ""
        m < 1000 -> "${((m / 100).roundToInt() * 100).coerceAtLeast(100)}" to r.getString(R.string.m_ahead)
        m < 10_000 -> "%.1f".format(m / 1000) to r.getString(R.string.km_ahead)
        else -> "${(m / 1000).roundToInt()}" to r.getString(R.string.km_ahead)
    }

    companion object {
        /** The reason a place is shown by: the one behind its reading (see [Reading.evidence]), so icon, count and meaning agree. */
        fun top(item: BriefingItem): Reason? = Reading.evidence(item)

        private fun isSpeed(r: Reason) = r.metric.endsWith("Kmh")

        /** The count is of cars (a car icon goes with it), not a speed. */
        fun isCars(item: BriefingItem): Boolean = top(item)?.let { !isSpeed(it) } ?: false

        /** This time and usual on one 0..1 scale for the bullet bar (speeds against the larger of the two, plus room). */
        fun shares(item: BriefingItem): Pair<Float, Float?>? {
            val r = top(item) ?: return null
            if (!isSpeed(r)) return r.value.toFloat().coerceIn(0f, 1f) to r.baseline?.toFloat()?.coerceIn(0f, 1f)
            val max = maxOf(r.value, r.baseline ?: 0.0) * 1.25
            if (max <= 0) return null
            return (r.value / max).toFloat() to r.baseline?.let { (it / max).toFloat() }
        }

        /** Position along the route, always one decimal. */
        fun at(m: Number): String = "%.1f km".format(m.toDouble() / 1000)

        /** Degrees without a negative zero. */
        fun degrees(v: Double?): String = v?.let { "%.1f°".format(if (kotlin.math.abs(it) < 0.05) 0.0 else it) } ?: "—"
    }
}

@Composable
fun rememberWords(): Words {
    val ctx = LocalContext.current
    return remember(ctx) { Words(ctx.resources) }
}
