package kr.tedin.maptalkdrive.ribbon.brief

import kotlinx.serialization.Serializable
import kr.tedin.maptalkdrive.ribbon.geo.CurveClass
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.Hairpin
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kotlin.math.roundToInt

/**
 * `maptalk.caution/1`: one place on the route where the driver should drive differently now. What (`reading`), where
 * (`fromM`..`toM`, met first at `pointM`), why it weighs (on sharp curves or hairpins; a sudden change after a tunnel
 * or a usual stretch), the evidence (`cars` of `n`, the last car at `lastMs`) and what a careful driver does there.
 */
@Serializable
data class Caution(
    val spec: String = CAUTION_SPEC,
    val rank: Int,
    val reading: Reading,
    val fromM: Int,
    val toM: Int,
    val pointM: Int,
    val anchorIds: List<String>,
    /** The place whose ribbon is the evidence (the most severe of them), and its top reason. */
    val leadId: String,
    val metric: String,
    val deviation: Deviation,
    val cars: Int,
    val n: Int,
    val hairpins: Int,
    val onSharpCurves: Boolean,
    val tunnelExit: Boolean,
    val sudden: Boolean,
    val actions: List<CautionAction>,
    val lastMs: Long?,
    val score: Double,
) {
    companion object { const val CAUTION_SPEC = "maptalk.caution/1" }
}

/**
 * Where to take care: the different places of a briefing, ranked by what it costs to meet them unprepared, at most
 * [MAX] of them. Built from the ribbons alone: a route with nothing different has no caution, and road shape on its
 * own never makes one (it only weighs a place that is already different).
 *
 * - Places that follow each other with the same kind of thing and no usual or unknown place between them are one
 *   caution (fog at a tunnel exit and on the two segments after it).
 * - score = severity of the reading × level (clearly 1, somewhat 0.6) × 1.5 on sharp curves or hairpins
 *   × 1.3 when it comes suddenly (right after a tunnel, or after a stretch that is usual) × freshness
 *   (last car within 30 min 1, within an hour 0.9, older 0.75).
 */
object Cautions {
    const val MAX = 3

    fun build(b: Briefing, curves: List<CurveStretch>, hairpins: List<Hairpin>, nowMs: Long, max: Int = MAX): List<Caution> {
        val items = b.items.sortedBy { it.pointM }
        val groups = mutableListOf<MutableList<BriefingItem>>()
        var open: MutableList<BriefingItem>? = null
        for (item in items) {
            when {
                item.different -> {
                    val g = open
                    if (g != null && Reading.of(g.last()).family == Reading.of(item).family) g += item
                    else open = mutableListOf(item).also { groups += it }
                }
                // A usual place, or one with too few cars, between two differences: they are not one stretch.
                else -> open = null
            }
        }
        return groups.map { g -> caution(g, items, curves, hairpins, nowMs) }
            .sortedWith(compareByDescending<Caution> { it.score }.thenBy { it.pointM })
            .take(max)
            .mapIndexed { i, c -> c.copy(rank = i + 1) }
    }

    private fun caution(g: List<BriefingItem>, items: List<BriefingItem>, curves: List<CurveStretch>, hairpins: List<Hairpin>, nowMs: Long): Caution {
        val lead = g.maxWith(compareBy<BriefingItem> { Reading.of(it).severity * level(it.state.deviation) }.thenBy { strength(it) })
        val reading = g.map { Reading.of(it) }.maxBy { it.severity }
        val from = g.minOf { range(it).first }
        val to = g.maxOf { range(it).last }
        val first = g.first()
        val before = items.lastOrNull { it.pointM < first.pointM && it !in g }
        val tunnelExit = first.anchor.type == AnchorType.TUNNEL
        val sudden = tunnelExit || (before != null && before.state.status == RibbonStatus.PUBLISHED && !before.different)
        val pins = hairpins.count { it.atM >= from - NEAR_M && it.atM <= to + NEAR_M }
        val sharp = curves.any { it.curve == CurveClass.SHARP && it.fromM <= to + NEAR_M && it.toM >= from - NEAR_M }
        val deviation = g.maxOf { it.state.deviation }
        val lastMs = lead.state.window?.lastMs
        val score = reading.severity * level(deviation) *
            (if (pins > 0 || sharp) ON_CURVES else 1.0) *
            (if (sudden) SUDDEN else 1.0) *
            freshness(lastMs, nowMs)
        val top = Reading.evidence(lead)!!
        return Caution(
            rank = 0, reading = reading, fromM = from, toM = to, pointM = first.pointM, anchorIds = g.map { it.anchor.id },
            leadId = lead.anchor.id, metric = top.metric, deviation = deviation,
            cars = if (top.metric.endsWith("Kmh")) 0 else (top.value * lead.state.n).roundToInt(), n = lead.state.n,
            hairpins = pins, onSharpCurves = sharp, tunnelExit = tunnelExit, sudden = sudden,
            actions = reading.actions(), lastMs = lastMs, score = Math.round(score * 100) / 100.0,
        )
    }

    /** The stretch a place covers on the road: a segment its length, a tunnel its exit, anything else its point. */
    private fun range(item: BriefingItem): IntRange = when (item.anchor.type) {
        AnchorType.SEGMENT -> item.anchor.alongM..item.anchor.endAlongM
        AnchorType.TUNNEL -> item.anchor.endAlongM..item.anchor.endAlongM
        else -> item.anchor.alongM..item.anchor.alongM
    }

    private fun level(d: Deviation) = when (d) { Deviation.HIGH -> 1.0; Deviation.SOME -> 0.6; Deviation.NONE -> 0.0 }

    private fun strength(item: BriefingItem): Double {
        val r = item.state.reasons.firstOrNull() ?: return 0.0
        return r.value - (listOfNotNull(r.baseline, r.neighbors).maxOrNull() ?: 0.0)
    }

    private fun freshness(lastMs: Long?, nowMs: Long): Double {
        val min = lastMs?.let { (nowMs - it) / 60_000 } ?: return OLD
        return when { min <= 30 -> 1.0; min <= 60 -> 0.9; else -> OLD }
    }

    private const val NEAR_M = 300
    private const val ON_CURVES = 1.5
    private const val SUDDEN = 1.3
    private const val OLD = 0.75
}
