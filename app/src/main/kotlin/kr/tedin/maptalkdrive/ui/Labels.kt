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

    /** The facility as a driver names it. Tunnel issues are about the exit; virtual cameras are just cameras here. */
    fun place(item: BriefingItem): String = when (item.anchor.type) {
        AnchorType.TUNNEL -> r.getString(R.string.tunnel_exit, name(item.anchor))
        AnchorType.CAMERA -> r.getString(R.string.speed_camera)
        else -> name(item.anchor)
    }

    /** What was different, for the legend and for accessibility; on the map and cards it is an icon. */
    fun issue(metric: String?): String = r.getString(
        when (metric) {
            Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE -> R.string.issue_wiper
            Metric.FOG_TURNED_ON_RATE -> R.string.issue_fog
            Metric.LATE_LANE_CHANGE_RATE -> R.string.issue_late_merge
            Metric.HARD_BRAKE_RATE -> R.string.issue_hard_brake
            Metric.STOP_RATE -> R.string.issue_stop
            Metric.ENTRY_DECEL_KMH -> R.string.issue_entry_decel
            else -> R.string.issue_other
        },
    )

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

        /** "6/9": with eight or nine cars a percentage overstates precision, so say the count. Speeds in km/h. */
        fun count(item: BriefingItem): String {
            val r = top(item) ?: return "${item.state.n}"
            return if (isSpeed(r)) "${r.value.roundToInt()} km/h" else "${(r.value * item.state.n).roundToInt()}/${item.state.n}"
        }

        fun usual(item: BriefingItem): String {
            val r = top(item) ?: return "—"
            val b = r.baseline ?: return "—"
            return if (isSpeed(r)) "${b.roundToInt()} km/h" else "${(b * 100).roundToInt()}%"
        }

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
