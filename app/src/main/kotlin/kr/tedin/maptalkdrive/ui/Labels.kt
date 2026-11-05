package kr.tedin.maptalkdrive.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
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

    /** What was different, for the legend and for accessibility; on the map and cards it is an icon. */
    fun issue(metric: String?): String = r.getString(
        when (metric) {
            Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE -> R.string.issue_wiper
            Metric.FOG_TURNED_ON_RATE, Metric.FOG_ON_RATE -> R.string.issue_fog
            Metric.LATE_LANE_CHANGE_RATE -> R.string.issue_late_merge
            Metric.HARD_BRAKE_RATE -> R.string.issue_hard_brake
            Metric.STOP_RATE -> R.string.issue_stop
            Metric.ENTRY_DECEL_KMH -> R.string.issue_entry_decel
            else -> R.string.issue_other
        },
    )

    /**
     * What the behaviour may mean for the driver, always said as a possibility. The issue icon and the count of cars
     * next to it are the evidence; this line is the reading of it. Hard braking at a camera is the camera's doing, not
     * a hold-up.
     */
    fun meaning(item: BriefingItem): String = r.getString(
        when (top(item)?.metric) {
            Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE -> R.string.meaning_rain
            Metric.FOG_TURNED_ON_RATE, Metric.FOG_ON_RATE -> R.string.meaning_fog
            Metric.HARD_BRAKE_RATE -> if (item.anchor.type == AnchorType.CAMERA) R.string.meaning_camera_brake else R.string.meaning_holdup
            Metric.LATE_LANE_CHANGE_RATE -> R.string.meaning_exit_busy
            Metric.STOP_RATE -> R.string.meaning_queue
            Metric.ENTRY_DECEL_KMH, Metric.SPEED_AT_KMH -> R.string.meaning_slow
            else -> R.string.meaning_other
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
        fun top(item: BriefingItem): Reason? = item.state.reasons.firstOrNull()

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
