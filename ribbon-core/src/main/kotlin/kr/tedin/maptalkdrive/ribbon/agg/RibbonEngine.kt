package kr.tedin.maptalkdrive.ribbon.agg

import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorRef
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.Reason
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kr.tedin.maptalkdrive.ribbon.spec.Window
import kotlin.math.abs

data class EngineConfig(
    /** Tried in order; the first window with enough distinct vehicles wins. */
    val windowsMin: List<Int> = listOf(15, 60, 180),
    val minVehicles: Int = 3,
    /** Neighbours are other anchors on the same route and direction within this distance. */
    val neighborRadiusM: Int = 15_000,
    val rateSome: Double = 0.15,
    val rateHigh: Double = 0.35,
    val kmhSome: Double = 10.0,
    val kmhHigh: Double = 20.0,
    /** `Window.lastMs` is rounded down to this. */
    val lastRoundMs: Long = 5 * 60_000L,
)

/**
 * Turns knots into published ribbons. Pure and deterministic: the same knots, time and baseline give the same states,
 * so the vehicle (offline demo) and a server can run the same code.
 *
 * "Different" needs two things: the value is above its own usual value AND above its neighbours on the same road.
 * Rain over the whole road raises every anchor alike, so it is not reported as something about one tunnel.
 */
class RibbonEngine(
    anchors: List<Anchor>,
    private val baseline: Baseline = Baseline(),
    private val cfg: EngineConfig = EngineConfig(),
) {
    private val anchors = anchors.sortedBy { it.alongM }

    private data class Snapshot(val anchor: Anchor, val status: RibbonStatus, val window: Window?, val n: Int, val metrics: Map<String, Double>)

    fun states(knots: List<Knot>, nowMs: Long): List<RibbonState> {
        val byAnchor = knots.filter { it.exitedAtMs <= nowMs }.groupBy { it.anchorId }
        val snaps = anchors.map { snapshot(it, byAnchor[it.id].orEmpty(), nowMs) }
        return snaps.map { publish(it, snaps, nowMs) }
    }

    private fun snapshot(a: Anchor, knots: List<Knot>, nowMs: Long): Snapshot {
        for (minutes in cfg.windowsMin) {
            val inWindow = knots.filter { it.exitedAtMs > nowMs - minutes * 60_000L }
            // One vote per vehicle: the latest knot of each pseudonym.
            val perVehicle = inWindow.groupBy { it.pseudonym }.values.map { ks -> ks.maxBy { it.exitedAtMs } }
            if (perVehicle.size >= cfg.minVehicles) {
                val last = perVehicle.maxOf { it.exitedAtMs }.let { it - Math.floorMod(it, cfg.lastRoundMs) }
                return Snapshot(a, RibbonStatus.PUBLISHED, Window(nowMs, minutes, last), perVehicle.size, metricsOf(a.type, perVehicle))
            }
        }
        val widest = cfg.windowsMin.last()
        val seen = knots.filter { it.exitedAtMs > nowMs - widest * 60_000L }.map { it.pseudonym }.distinct().size
        return Snapshot(a, RibbonStatus.NOT_ENOUGH, null, seen, emptyMap())
    }

    private fun metricsOf(type: AnchorType, ks: List<Knot>): Map<String, Double> {
        fun rate(p: (Knot) -> Boolean) = ks.count(p).toDouble() / ks.size
        val m = linkedMapOf<String, Double>()
        m[Metric.HARD_BRAKE_RATE] = rate { it.hardBrake }
        m[Metric.WIPER_ON_RATE] = rate { it.wiperBefore || it.wiperAfter }
        when (type) {
            AnchorType.TUNNEL -> {
                m[Metric.WIPER_TURNED_ON_RATE] = rate { !it.wiperBefore && it.wiperAfter }
                m[Metric.FOG_TURNED_ON_RATE] = rate { !it.fogBefore && it.fogAfter }
                median(ks.mapNotNull { k -> k.speedInKmh?.let { i -> k.speedAtKmh?.let { i - it } } })?.let { m[Metric.ENTRY_DECEL_KMH] = it }
            }
            AnchorType.JUNCTION -> m[Metric.LATE_LANE_CHANGE_RATE] = rate { it.lateLaneChange }
            AnchorType.CAMERA -> {
                median(ks.mapNotNull { k -> k.speedInKmh?.let { i -> k.speedAtKmh?.let { i - it } } })?.let { m[Metric.ENTRY_DECEL_KMH] = it }
                median(ks.mapNotNull { it.speedAtKmh })?.let { m[Metric.SPEED_AT_KMH] = it }
            }
            AnchorType.TOLL -> m[Metric.STOP_RATE] = rate { it.stoppedS > 0 }
            AnchorType.SEGMENT -> {
                m[Metric.FOG_ON_RATE] = rate { it.fogBefore || it.fogAfter }
                m[Metric.STOP_RATE] = rate { it.stoppedS > 0 }
                median(ks.mapNotNull { it.speedAtKmh })?.let { m[Metric.SPEED_AT_KMH] = it }
            }
        }
        return m.mapValues { (_, v) -> round3(v) }
    }

    private fun publish(s: Snapshot, all: List<Snapshot>, nowMs: Long): RibbonState {
        val ref = AnchorRef(s.anchor.id, s.anchor.type, s.anchor.dir, s.anchor.name, s.anchor.virtual)
        if (s.status == RibbonStatus.NOT_ENOUGH) {
            return RibbonState(anchor = ref, status = s.status, n = s.n, gist = Gist.notEnough(s.anchor.name, cfg.windowsMin.last(), s.n))
        }
        val hour = Baseline.hourOf(nowMs)
        val usual = s.metrics.keys.mapNotNull { k -> baseline.get(s.anchor.id, hour, k)?.let { k to it } }.toMap()
        // Segments are compared with segments and facilities with facilities: a stretch of open road and a tunnel exit
        // are not the same kind of place.
        val segment = s.anchor.type == AnchorType.SEGMENT
        val neighbors = all.filter {
            it !== s && it.status == RibbonStatus.PUBLISHED && it.anchor.dir == s.anchor.dir &&
                (it.anchor.type == AnchorType.SEGMENT) == segment &&
                abs(it.anchor.alongM - s.anchor.alongM) <= cfg.neighborRadiusM
        }
        val reasons = s.metrics.mapNotNull { (k, v) ->
            if (k in contextOnly(s.anchor.type)) return@mapNotNull null
            val near = median(neighbors.mapNotNull { it.metrics[k] })
            val level = levelOf(k, v, usual[k], near)
            if (level == Deviation.NONE) null else Reason(k, v, usual[k], near?.let(::round3), level)
        }.sortedByDescending { score(it) }
        val deviation = reasons.firstOrNull()?.level ?: Deviation.NONE
        return RibbonState(
            anchor = ref,
            status = s.status,
            window = s.window,
            n = s.n,
            metrics = s.metrics,
            baseline = usual,
            deviation = deviation,
            reasons = reasons,
            gist = Gist.of(s.anchor.name, s.window!!.minutes, s.n, reasons.firstOrNull()),
        )
    }

    /** Only "worse than usual" counts, and it must hold against every reference that exists. */
    private fun levelOf(metric: String, value: Double, usual: Double?, neighbors: Double?): Deviation {
        val refs = listOfNotNull(usual, neighbors)
        if (refs.isEmpty()) return Deviation.NONE
        val diff = refs.minOf { value - it }
        val (some, high) = if (metric.endsWith("Kmh")) cfg.kmhSome to cfg.kmhHigh else cfg.rateSome to cfg.rateHigh
        return when {
            diff >= high -> Deviation.HIGH
            diff >= some -> Deviation.SOME
            else -> Deviation.NONE
        }
    }

    private fun score(r: Reason): Double {
        val ref = listOfNotNull(r.baseline, r.neighbors).maxOrNull() ?: 0.0
        val scale = if (r.metric.endsWith("Kmh")) cfg.kmhHigh else cfg.rateHigh
        return (r.value - ref) / scale
    }
}

/**
 * Published for context but never a "difference": speed is not news (the navigation shows traffic). At a facility wiper
 * use is weather, which lights up a whole region at once; there a change of weather shows up as wipers switching on
 * (WIPER_TURNED_ON_RATE). Segments tile the road, so their neighbours cover the region: rain on one stretch and not
 * around it is a difference there, rain everywhere is not.
 */
private fun contextOnly(type: AnchorType): Set<String> =
    if (type == AnchorType.SEGMENT) setOf(Metric.SPEED_AT_KMH) else setOf(Metric.SPEED_AT_KMH, Metric.WIPER_ON_RATE)

internal fun median(xs: List<Double>): Double? {
    if (xs.isEmpty()) return null
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

internal fun round3(v: Double) = Math.round(v * 1000) / 1000.0
