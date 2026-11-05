package kr.tedin.maptalkdrive.ribbon

import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.distanceM
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.LaneChangeSignal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassDetectorTest {
    private val junction = Road.anchor("J", AnchorType.JUNCTION, 10_000, before = 1000, after = 200)
    private val tunnel = Road.anchor("T", AnchorType.TUNNEL, 5_000, endAlongM = 8_000)

    private fun run(drive: Drive, vararg anchors: kr.tedin.maptalkdrive.ribbon.spec.Anchor): List<Knot> {
        val d = PassDetector(Road.route, anchors.toList(), pseudonym = "p1")
        return drive.samples().flatMap { d.accept(it) }
    }

    @Test fun `geometry port matches known distances`() {
        val oneDegLat = distanceM(LngLat(128.0, 37.0), LngLat(128.0, 38.0))
        assertTrue(abs(oneDegLat - 111_195) < 50, "1 degree of latitude was $oneDegLat m")
        val snap = Road.route.snap(Road.at(12_345.0))
        assertTrue(abs(snap.alongM - 12_345) < 1 && snap.distanceM < 1)
    }

    @Test fun `turn signal started in the last 300 m is a late lane change`() {
        val k = run(Drive(turn = { if (it in 9_800.0..10_000.0) TurnSignal.RIGHT else TurnSignal.OFF }), junction).single()
        assertTrue(k.lateLaneChange)
        assertEquals(LaneChangeSignal.TURN_SIGNAL, k.laneChangeBy)
    }

    @Test fun `turn signal already on 800 m before the split is not late`() {
        val k = run(Drive(turn = { if (it in 9_200.0..10_000.0) TurnSignal.RIGHT else TurnSignal.OFF }), junction).single()
        assertFalse(k.lateLaneChange)
    }

    @Test fun `a steady wheel angle on the ramp curve is not a lane change, a left-right swing is`() {
        val curve = run(Drive(wheel = { if (it in 9_700.0..10_000.0) 2.0 else 0.0 }), junction).single()
        assertFalse(curve.lateLaneChange)
        val swing = run(Drive(wheel = { if (it in 9_800.0..9_850.0) 1.2 else if (it in 9_850.0..9_900.0) -1.2 else 0.0 }), junction).single()
        assertTrue(swing.lateLaneChange)
        assertEquals(LaneChangeSignal.WHEEL, swing.laneChangeBy)
    }

    @Test fun `tunnel without fixes inside still yields one knot, with wiper switched on only after the exit`() {
        val k = run(
            Drive(noFix = { it in 5_000.0..8_000.0 }, wiper = { it > 8_000 }),
            tunnel,
        ).single()
        assertFalse(k.wiperBefore)
        assertTrue(k.wiperAfter)
        assertTrue(k.speedAtKmh!! in 85.0..95.0, "entry speed was ${k.speedAtKmh}")
    }

    @Test fun `wiper on during the whole drive is on before and after`() {
        val k = run(Drive(noFix = { it in 5_000.0..8_000.0 }, wiper = { true }), tunnel).single()
        assertTrue(k.wiperBefore && k.wiperAfter)
    }

    @Test fun `hard brake needs both the speed drop and the brake pedal`() {
        val speed: (Double) -> Double = { if (it < 9_500) 90.0 else 65.0 }
        val braked = run(Drive(speedAt = speed, brake = { it in 9_480.0..9_600.0 }), junction).single()
        assertTrue(braked.hardBrake)
        val coasted = run(Drive(speedAt = speed), junction).single()
        assertFalse(coasted.hardBrake)
    }

    @Test fun `leaving the road or stopping before the zone end produces no knot`() {
        val d = PassDetector(Road.route, listOf(junction), pseudonym = "p1")
        val partial = Drive(toM = 9_900.0).samples().flatMap { d.accept(it) }
        assertTrue(partial.isEmpty())

        val off = PassDetector(Road.route, listOf(junction), pseudonym = "p1")
        val samples = Drive().samples().map { s ->
            val along = Road.route.snap(s.fix!!).alongM
            if (along in 9_500.0..9_600.0) s.copy(fix = LngLat(s.fix!!.lon, 38.01)) else s // ~1.1 km off the road
        }
        assertTrue(samples.flatMap { off.accept(it) }.isEmpty())
    }

    @Test fun `no knot is tied for an anchor the car never reached`() {
        val far = Road.anchor("FAR", AnchorType.CAMERA, 19_990, before = 400, after = 100)
        assertNull(run(Drive(toM = 15_000.0), far).firstOrNull())
    }

    private val segment = Road.anchor("S", AnchorType.SEGMENT, 12_000, endAlongM = 14_000, before = 0, after = 0)
    private val camera = Road.anchor("C", AnchorType.CAMERA, 13_500, before = 300, after = 100)

    @Test fun `a segment knot covers the stretch with its halves, its mean speed and a stop on it`() {
        val k = run(Drive(
            speedAt = { if (it in 12_880.0..12_910.0) 2.0 else if (it < 13_000) 100.0 else 80.0 },
            brake = { it in 12_850.0..12_910.0 },
            fog = { it >= 13_000 },
        ), segment).single()
        assertEquals("S", k.anchorId)
        assertTrue(k.hardBrake, "100 to 2 km/h with the brake on is hard braking")
        assertTrue(k.stoppedS > 0, "standing at 2 km/h is a stop")
        assertFalse(k.fogBefore); assertTrue(k.fogAfter, "fog lights on in the second half only")
        // Time-weighted, like distance over time: the minute spent standing pulls it well below the cruising speeds.
        assertTrue(k.speedAtKmh!! in 40.0..80.0, "mean speed over the segment: ${k.speedAtKmh}")
    }

    @Test fun `braking inside a facility's zone counts for the facility, not for the segment around it`() {
        val speed: (Double) -> Double = { if (it < 13_400) 90.0 else 60.0 }
        val ks = run(Drive(speedAt = speed, brake = { it in 13_380.0..13_450.0 }), segment, camera).associateBy { it.anchorId }
        assertTrue(ks.getValue("C").hardBrake)
        assertFalse(ks.getValue("S").hardBrake)
        val outside = run(Drive(speedAt = { if (it < 12_400) 90.0 else 60.0 }, brake = { it in 12_380.0..12_450.0 }), segment, camera).associateBy { it.anchorId }
        assertTrue(outside.getValue("S").hardBrake)
        assertFalse(outside.getValue("C").hardBrake)
    }
}
