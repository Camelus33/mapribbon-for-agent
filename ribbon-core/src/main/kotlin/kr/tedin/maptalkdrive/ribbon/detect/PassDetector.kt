package kr.tedin.maptalkdrive.ribbon.detect

import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.Anchor
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.LaneChangeSignal
import kr.tedin.maptalkdrive.ribbon.spec.WidthClass
import kotlin.math.abs

enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * One reading of the vehicle. `fix` is null when there is no position (inside a long tunnel).
 * `speedKmh` is the vehicle's own speed when available; otherwise speed is derived from consecutive fixes.
 * `wheelAngleDeg` is the front-wheel angle (Pleos reports the wheel, not the steering-wheel rotation).
 */
data class Sample(
    val tMs: Long,
    val fix: LngLat?,
    val speedKmh: Double? = null,
    val brake: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val wheelAngleDeg: Double? = null,
    val wiper: Boolean = false,
    val fogLight: Boolean = false,
)

data class DetectorConfig(
    val maxOffRouteM: Double = 60.0,
    val maxGapMs: Long = 15 * 60_000L,
    val hardBrakeDropKmh: Double = 20.0,
    val hardBrakeWindowMs: Long = 3_000,
    val lateLaneChangeM: Double = 300.0,
    val wheelSwingDeg: Double = 0.8,
    val wheelSwingWindowMs: Long = 4_000,
    val laneChangeMinKmh: Double = 40.0,
    val stoppedBelowKmh: Double = 3.0,
    /** A pass must start within this distance of the zone start; one first seen mid-zone is partial and skipped. */
    val entrySlackM: Double = 150.0,
)

private enum class Phase { BEFORE, AT, INSIDE, AFTER }

private data class PassSample(val s: Sample, val alongM: Double?, val speedKmh: Double?, val phase: Phase)

/**
 * Runs in the vehicle. Turns a stream of samples into knots: one knot per anchor whose influence zone the vehicle
 * crossed from start to end on this route. A pass that leaves the route, stalls past `maxGapMs` or never reaches the
 * zone end produces nothing; a partial observation is not a knot.
 */
class PassDetector(
    private val route: RouteIndex,
    anchors: List<Anchor>,
    private val pseudonym: String,
    private val widthClass: WidthClass? = null,
    private val cfg: DetectorConfig = DetectorConfig(),
) {
    private val anchors = anchors.sortedBy { it.alongM }
    private val open = LinkedHashMap<String, MutableList<PassSample>>()
    private var lastAlong: Double? = null
    private var lastFixT: Long? = null
    private var lastT: Long? = null
    /** Zones running past the end of the route close here (about one second of driving before the end). */
    private val closeAtM = route.lengthM - 30

    fun accept(s: Sample): List<Knot> {
        lastT?.let { if (s.tMs - it > cfg.maxGapMs) reset() }
        lastT = s.tMs

        var along: Double? = null
        var speed = s.speedKmh
        val trackedBefore = lastAlong // last fix on this route, if we have been following the car
        if (s.fix != null) {
            val snap = route.snap(s.fix)
            if (snap.distanceM > cfg.maxOffRouteM) { reset(); return emptyList() }
            val prevAlong = lastAlong
            val prevT = lastFixT
            if (prevAlong != null && snap.alongM < prevAlong - 50) { reset(); return emptyList() } // turned back
            if (speed == null && prevAlong != null && prevT != null && s.tMs > prevT) {
                speed = (snap.alongM - prevAlong) / ((s.tMs - prevT) / 1000.0) * 3.6
            }
            along = snap.alongM
            lastAlong = along
            lastFixT = s.tMs
        }

        if (along != null) {
            for (a in anchors) {
                if (a.id in open || along < a.zoneStartM || along > minOf(a.zoneEndM.toDouble(), closeAtM)) continue
                // Crossing the zone start while tracked counts even across a gap without fixes (the zone may begin
                // inside the previous tunnel). Without a previous fix, only a start close to the zone start counts.
                val entered = if (trackedBefore != null) trackedBefore < a.zoneStartM else along <= a.zoneStartM + cfg.entrySlackM
                if (entered) open[a.id] = mutableListOf()
            }
        }

        val done = mutableListOf<Knot>()
        val iter = open.entries.iterator()
        while (iter.hasNext()) {
            val (id, samples) = iter.next()
            val a = anchors.first { it.id == id }
            val phase = when {
                along == null -> Phase.INSIDE
                along < a.alongM -> Phase.BEFORE
                along > a.endAlongM -> Phase.AFTER
                else -> Phase.AT
            }
            samples += PassSample(s, along, speed, phase)
            if (along != null && along >= minOf(a.zoneEndM.toDouble(), closeAtM)) {
                done += knotOf(a, samples)
                iter.remove()
            }
        }
        return done
    }

    /** Drops every unfinished pass. */
    fun reset() {
        open.clear()
        lastAlong = null
        lastFixT = null
    }

    private fun knotOf(a: Anchor, ps: List<PassSample>): Knot {
        val withSpeed = ps.filter { it.speedKmh != null }
        val before = ps.filter { it.phase == Phase.BEFORE }
        val after = ps.filter { it.phase == Phase.AFTER }
        val speedIn = before.firstOrNull { it.speedKmh != null }?.speedKmh
        val speedAt = if (a.type == AnchorType.TUNNEL) {
            before.lastOrNull { it.speedKmh != null }?.speedKmh
        } else {
            withSpeed.filter { it.alongM != null }.minByOrNull { abs(it.alongM!! - a.alongM) }?.speedKmh
        }
        val speedOut = after.lastOrNull { it.speedKmh != null }?.speedKmh

        var stoppedMs = 0L
        for (i in 1 until ps.size) {
            val v = ps[i - 1].speedKmh
            if (v != null && v < cfg.stoppedBelowKmh) stoppedMs += ps[i].s.tMs - ps[i - 1].s.tMs
        }

        val lane = if (a.type == AnchorType.JUNCTION) laneChange(a, ps) else null
        return Knot(
            anchorId = a.id,
            dir = a.dir,
            pseudonym = pseudonym,
            enteredAtMs = ps.first().s.tMs,
            exitedAtMs = ps.last().s.tMs,
            speedInKmh = speedIn?.round1(),
            speedAtKmh = speedAt?.round1(),
            speedOutKmh = speedOut?.round1(),
            stoppedS = (stoppedMs / 1000).toInt(),
            hardBrake = hardBrake(withSpeed),
            lateLaneChange = lane != null,
            laneChangeBy = lane,
            wiperBefore = before.any { it.s.wiper },
            wiperAfter = after.any { it.s.wiper },
            fogBefore = before.any { it.s.fogLight },
            fogAfter = after.any { it.s.fogLight },
            widthClass = widthClass,
        )
    }

    private fun hardBrake(ps: List<PassSample>): Boolean {
        for (i in ps.indices) {
            for (j in i + 1 until ps.size) {
                if (ps[j].s.tMs - ps[i].s.tMs > cfg.hardBrakeWindowMs) break
                val drop = ps[i].speedKmh!! - ps[j].speedKmh!!
                if (drop >= cfg.hardBrakeDropKmh && (i + 1..j).any { ps[it].s.brake }) return true
            }
        }
        return false
    }

    /**
     * A lane change that starts inside the last `lateLaneChangeM` before the split. The turn signal counts only if it
     * was not already on before that stretch (signalling early is the safe behaviour). Without a signal, a left-right
     * swing of the front wheels at speed counts; a steady angle (the ramp curve) does not.
     */
    private fun laneChange(a: Anchor, ps: List<PassSample>): LaneChangeSignal? {
        val lateFrom = a.alongM - cfg.lateLaneChangeM
        val early = ps.filter { it.alongM != null && it.alongM < lateFrom }
        val late = ps.filter { it.alongM != null && it.alongM >= lateFrom && it.alongM <= a.alongM }
        if (early.none { it.s.turnSignal != TurnSignal.OFF } && late.any { it.s.turnSignal != TurnSignal.OFF }) {
            return LaneChangeSignal.TURN_SIGNAL
        }
        val wheel = late.filter { it.s.wheelAngleDeg != null && (it.speedKmh ?: 0.0) >= cfg.laneChangeMinKmh }
        for (i in wheel.indices) {
            for (j in i + 1 until wheel.size) {
                if (wheel[j].s.tMs - wheel[i].s.tMs > cfg.wheelSwingWindowMs) break
                val x = wheel[i].s.wheelAngleDeg!!; val y = wheel[j].s.wheelAngleDeg!!
                if ((x >= cfg.wheelSwingDeg && y <= -cfg.wheelSwingDeg) || (x <= -cfg.wheelSwingDeg && y >= cfg.wheelSwingDeg)) {
                    return LaneChangeSignal.WHEEL
                }
            }
        }
        return null
    }
}

private fun Double.round1() = Math.round(this * 10) / 10.0
