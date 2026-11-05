package kr.tedin.maptalkdrive.ribbon.brief

import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus

data class BriefingItem(val anchor: Anchor, val state: RibbonState) {
    val different: Boolean get() = state.status == RibbonStatus.PUBLISHED && state.deviation != Deviation.NONE

    /** The point a driver should mind: a tunnel's exit (where the open road comes back), a segment's middle, any other facility itself. */
    val pointM: Int get() = when (anchor.type) {
        AnchorType.TUNNEL -> anchor.endAlongM
        AnchorType.SEGMENT -> (anchor.alongM + anchor.endAlongM) / 2
        else -> anchor.alongM
    }
}

/**
 * What the route strip shows: every anchor on the route in order, one headline, at most three labels.
 * Grey (usual) and dashed (not enough records) anchors stay on the strip so an empty stretch never reads as "safe".
 */
data class Briefing(
    val items: List<BriefingItem>,
    val headline: BriefingItem?,
    val labeled: List<BriefingItem>,
) {
    val differentCount: Int get() = items.count { it.different }
    val notEnoughCount: Int get() = items.count { it.state.status == RibbonStatus.NOT_ENOUGH }

    /** The next anchor ahead that is different from usual, and the distance to its point (a tunnel's exit). */
    fun nextDifferentAhead(alongM: Double): Pair<BriefingItem, Double>? =
        items.filter { it.different && it.pointM > alongM }.minByOrNull { it.pointM }
            ?.let { it to it.pointM - alongM }

    companion object {
        const val MAX_LABELS = 3

        fun build(anchors: List<Anchor>, states: List<RibbonState>): Briefing {
            val byId = states.associateBy { it.anchor.id }
            val items = anchors.sortedBy { it.alongM }.mapNotNull { a -> byId[a.id]?.let { BriefingItem(a, it) } }
            val ranked = items.filter { it.different }.sortedWith(
                compareByDescending<BriefingItem> { it.state.deviation.ordinal }
                    .thenByDescending { strength(it.state) }
                    .thenBy { it.anchor.alongM },
            )
            // Labels go to different things: a place that says the same as a labeled one close by (fog at a tunnel exit
            // and on the segments after it) leaves its label to the next thing down the ranking.
            val labeled = mutableListOf<BriefingItem>()
            for (item in ranked) {
                if (labeled.size == MAX_LABELS) break
                if (labeled.none { family(it) == family(item) && kotlin.math.abs(it.pointM - item.pointM) <= SAME_THING_WITHIN_M }) labeled += item
            }
            return Briefing(items, ranked.firstOrNull(), labeled.sortedBy { it.anchor.alongM })
        }

        const val SAME_THING_WITHIN_M = 3_000

        /** What kind of thing a place's top reason is: fog lights on or switched on are both fog. */
        private fun family(item: BriefingItem): String {
            val m = item.state.reasons.firstOrNull()?.metric ?: return ""
            return when {
                m.startsWith("fog") -> "fog"
                m.startsWith("wiper") -> "wiper"
                else -> m
            }
        }

        /** How far the top reason is above its reference, in units of the "high" threshold (rates 0.35, speeds 20 km/h). */
        private fun strength(s: RibbonState): Double {
            val r = s.reasons.firstOrNull() ?: return 0.0
            val ref = listOfNotNull(r.baseline, r.neighbors).maxOrNull() ?: 0.0
            return (r.value - ref) / if (r.metric.endsWith("Kmh")) 20.0 else 0.35
        }
    }
}
