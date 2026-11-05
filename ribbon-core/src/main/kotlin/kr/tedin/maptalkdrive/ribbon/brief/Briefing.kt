package kr.tedin.maptalkdrive.ribbon.brief

import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus

data class BriefingItem(val anchor: Anchor, val state: RibbonState) {
    val different: Boolean get() = state.status == RibbonStatus.PUBLISHED && state.deviation != Deviation.NONE
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

    /** The next anchor ahead that is different from usual, and the distance to where it starts. */
    fun nextDifferentAhead(alongM: Double): Pair<BriefingItem, Double>? =
        items.firstOrNull { it.different && it.anchor.endAlongM > alongM }
            ?.let { it to maxOf(0.0, it.anchor.alongM - alongM) }

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
            val labeled = ranked.take(MAX_LABELS).sortedBy { it.anchor.alongM }
            return Briefing(items, ranked.firstOrNull(), labeled)
        }

        /** How far the top reason is above its reference, in units of the "high" threshold (rates 0.35, speeds 20 km/h). */
        private fun strength(s: RibbonState): Double {
            val r = s.reasons.firstOrNull() ?: return 0.0
            val ref = listOfNotNull(r.baseline, r.neighbors).maxOrNull() ?: 0.0
            return (r.value - ref) / if (r.metric.endsWith("Kmh")) 20.0 else 0.35
        }
    }
}
