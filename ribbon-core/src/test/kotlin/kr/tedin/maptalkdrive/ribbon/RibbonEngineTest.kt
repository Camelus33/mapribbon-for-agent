package kr.tedin.maptalkdrive.ribbon

import kotlinx.serialization.encodeToString
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.agg.RibbonEngine
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.pseudo.Pseudonym
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RibbonEngineTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val west = Road.anchor("W", AnchorType.TUNNEL, 2_000, endAlongM = 3_000)
    private val mid = Road.anchor("M", AnchorType.TUNNEL, 8_000, endAlongM = 12_000)
    private val east = Road.anchor("E", AnchorType.TUNNEL, 15_000, endAlongM = 16_000)
    private val tunnels = listOf(west, mid, east)
    private val usual = Baseline(values = tunnels.associate { a ->
        a.id to (0..23).associate { h -> h.toString() to mapOf(Metric.WIPER_TURNED_ON_RATE to 0.05, Metric.WIPER_ON_RATE to 0.1) }
    })

    @Test fun `two vehicles in three hours is not enough, three is`() {
        val two = listOf(knot("M", "a", now - 10 * min), knot("M", "b", now - 100 * min))
        val s2 = RibbonEngine(listOf(mid)).states(two, now).single()
        assertEquals(RibbonStatus.NOT_ENOUGH, s2.status)
        assertEquals("M, 기록 부족 (최근 3시간 2대).", s2.gist)
        assertTrue(s2.metrics.isEmpty())

        val s3 = RibbonEngine(listOf(mid)).states(two + knot("M", "c", now - 170 * min), now).single()
        assertEquals(RibbonStatus.PUBLISHED, s3.status)
        assertEquals(180, s3.window!!.minutes)
        assertEquals(3, s3.n)
    }

    @Test fun `the same vehicle passing five times counts once`() {
        val same = (1..5).map { knot("M", "a", now - it * 10 * min) }
        val s = RibbonEngine(listOf(mid)).states(same, now).single()
        assertEquals(RibbonStatus.NOT_ENOUGH, s.status)
        assertEquals(1, s.n)
    }

    @Test fun `the shortest window with three vehicles wins`() {
        val ks = listOf(knot("M", "a", now - 5 * min), knot("M", "b", now - 10 * min), knot("M", "c", now - 40 * min))
        assertEquals(60, RibbonEngine(listOf(mid)).states(ks, now).single().window!!.minutes)
        val k15 = ks + knot("M", "d", now - 12 * min)
        assertEquals(15, RibbonEngine(listOf(mid)).states(k15, now).single().window!!.minutes)
    }

    @Test fun `the window says when the latest car passed, rounded down to five minutes`() {
        val ks = listOf(knot("M", "a", now - 5 * min - 37_000), knot("M", "b", now - 20 * min), knot("M", "c", now - 40 * min))
        val w = RibbonEngine(listOf(mid)).states(ks, now).single().window!!
        assertEquals(now - 10 * min, w.lastMs)
        assertTrue(w.lastMs!! <= now - 5 * min - 37_000, "never fresher than the knot")
    }

    @Test fun `knots from the future are ignored`() {
        val ks = listOf(knot("M", "a", now - min), knot("M", "b", now - min), knot("M", "c", now + 5 * min))
        assertEquals(RibbonStatus.NOT_ENOUGH, RibbonEngine(listOf(mid)).states(ks, now).single().status)
    }

    /** Rain only east of the middle tunnel: wipers go on at its exit. Neighbours see nothing new. */
    private fun localRain(): List<kr.tedin.maptalkdrive.ribbon.spec.Knot> = (1..9).flatMap { i ->
        val p = "v$i"
        val t = now - i * min
        val rainyExit = i <= 6
        listOf(
            knot("W", p, t),
            knot("M", p, t) { copy(wiperAfter = rainyExit, speedInKmh = 90.0, speedAtKmh = 85.0) },
            knot("E", p, t) { copy(wiperBefore = rainyExit, wiperAfter = rainyExit) },
        )
    }

    /** Rain over the whole road: wipers are on before and after every tunnel. */
    private fun rainEverywhere() = (1..9).flatMap { i ->
        tunnels.map { a -> knot(a.id, "v$i", now - i * min) { copy(wiperBefore = true, wiperAfter = true) } }
    }

    @Test fun `wipers switching on at one tunnel exit is HIGH, rain over the whole road is not`() {
        val local = RibbonEngine(tunnels, usual).states(localRain(), now).associateBy { it.anchor.id }
        assertEquals(Deviation.HIGH, local["M"]!!.deviation)
        assertEquals(Metric.WIPER_TURNED_ON_RATE, local["M"]!!.reasons.first().metric)
        assertEquals(Deviation.NONE, local["W"]!!.deviation)

        val everywhere = RibbonEngine(tunnels, usual).states(rainEverywhere(), now)
        assertTrue(everywhere.all { it.deviation == Deviation.NONE }, everywhere.joinToString { "${it.anchor.id}=${it.deviation} ${it.reasons}" })
        assertTrue(everywhere.all { it.metrics[Metric.WIPER_ON_RATE] == 1.0 }, "the comparison saw the rain")
    }

    @Test fun `on segments rain on one stretch is a difference, rain on every stretch is not, and facilities are not their neighbours`() {
        val segs = (0 until 6).map { Road.anchor("S$it", AnchorType.SEGMENT, it * 2_000, endAlongM = it * 2_000 + 2_000, before = 0, after = 0) }
        val usualSeg = Baseline(values = segs.associate { a -> a.id to (0..23).associate { h -> h.toString() to mapOf(Metric.WIPER_ON_RATE to 0.1) } })
        fun drive(wet: (String) -> Boolean) = (1..9).flatMap { i ->
            segs.map { a -> knot(a.id, "v$i", now - i * min) { copy(wiperBefore = wet(a.id) && i <= 6, wiperAfter = wet(a.id) && i <= 6) } }
        }
        val shower = RibbonEngine(segs, usualSeg).states(drive { it == "S2" }, now).associateBy { it.anchor.id }
        assertEquals(Deviation.HIGH, shower.getValue("S2").deviation)
        assertEquals(Metric.WIPER_ON_RATE, shower.getValue("S2").reasons.single().metric)
        assertTrue(shower.values.filter { it.anchor.id != "S2" }.all { it.deviation == Deviation.NONE })

        val everywhere = RibbonEngine(segs, usualSeg).states(drive { true }, now)
        assertTrue(everywhere.all { it.deviation == Deviation.NONE }, everywhere.joinToString { "${it.anchor.id}=${it.deviation}" })

        // A wet tunnel next door does not make a dry segment look dry by comparison, nor a wet one look usual.
        val withTunnel = RibbonEngine(segs + Road.anchor("T", AnchorType.TUNNEL, 13_000, endAlongM = 14_000), usualSeg)
            .states(drive { it == "S2" } + (1..9).map { i -> knot("T", "v$i", now - i * min) { copy(wiperBefore = true, wiperAfter = true) } }, now)
            .associateBy { it.anchor.id }
        assertEquals(Deviation.HIGH, withTunnel.getValue("S2").deviation)
    }

    @Test fun `a signal fewer than three cars report is not published for a segment`() {
        val seg = Road.anchor("S", AnchorType.SEGMENT, 0, endAlongM = 2_000, before = 0, after = 0)
        fun drive(reporting: Int) = (1..9).map { i -> knot("S", "v$i", now - i * min) { copy(wiperBefore = true, minTempC = if (i <= reporting) 0.5 else null) } }
        val two = RibbonEngine(listOf(seg)).states(drive(2), now).single()
        assertNull(two.metrics[Metric.ICE_RISK_RATE])
        assertNull(two.metrics[Metric.OUTSIDE_TEMP_C])
        val three = RibbonEngine(listOf(seg)).states(drive(3), now).single()
        assertEquals(1.0, three.metrics[Metric.ICE_RISK_RATE], "all three that report are wet near freezing")
        assertEquals(0.5, three.metrics[Metric.OUTSIDE_TEMP_C])
    }

    @Test fun `without neighbours a high value is judged against the usual value alone`() {
        val alone = RibbonEngine(listOf(mid), usual).states(localRain().filter { it.anchorId == "M" }, now).single()
        assertEquals(Deviation.HIGH, alone.deviation)
        val noRefs = RibbonEngine(listOf(mid)).states(localRain().filter { it.anchorId == "M" }, now).single()
        assertEquals(Deviation.NONE, noRefs.deviation, "nothing to compare with means nothing to report")
    }

    @Test fun `gist uses only the ribbon's own numbers`() {
        val m = RibbonEngine(tunnels, usual).states(localRain(), now).first { it.anchor.id == "M" }
        assertEquals("M 출구, 최근 15분 9대 중 67%가 출구에서 와이퍼를 켬 (평소 5%).", m.gist)
        val numbers = Regex("\\d+").findAll(m.gist).map { it.value }.toList()
        assertEquals(listOf("15", "9", "67", "5"), numbers)
    }

    @Test fun `ribbon state round-trips through the published JSON`() {
        val s = RibbonEngine(tunnels, usual).states(localRain(), now).first { it.anchor.id == "M" }
        val json = RibbonJson.encodeToString(s)
        assertTrue(json.contains("\"spec\":\"maptalk.ribbon/1\""))
        assertEquals(s, RibbonJson.decodeFromString<RibbonState>(json))
    }

    @Test fun `briefing headlines the most different anchor and keeps empty ones visible`() {
        val far = Road.anchor("F", AnchorType.CAMERA, 19_000, before = 400, after = 100)
        val anchors = tunnels + far
        val states = RibbonEngine(anchors, usual).states(localRain(), now)
        val b = Briefing.build(anchors, states)
        assertEquals("M", b.headline!!.anchor.id)
        // East of the middle tunnel it rains. Only M, where wipers switch on, is different; E's wiper use is weather.
        assertEquals(listOf("M" to Deviation.HIGH), b.items.filter { it.different }.map { it.anchor.id to it.state.deviation })
        assertEquals(0.667, b.items.first { it.anchor.id == "E" }.state.metrics[Metric.WIPER_ON_RATE], "E still reports the rain as context")
        assertEquals(1, b.notEnoughCount)
        assertEquals(4, b.items.size)
        assertEquals(listOf("M"), b.labeled.map { it.anchor.id })
        val (next, dist) = b.nextDifferentAhead(5_000.0)!!
        assertEquals("M", next.anchor.id)
        assertEquals(7_000.0, dist, "distance runs to the tunnel exit at 12 km")
        assertEquals(2_000.0, b.nextDifferentAhead(10_000.0)!!.second, "inside the tunnel the exit is still ahead")
        assertNull(b.nextDifferentAhead(13_000.0), "nothing different left after the middle tunnel")
    }

    @Test fun `pseudonyms are stable within a day and unlinkable across days`() {
        val secret = ByteArray(32) { it.toByte() }
        val d = LocalDate.of(2026, 11, 6)
        assertEquals(Pseudonym.forDay(secret, d), Pseudonym.forDay(secret, d))
        assertNotEquals(Pseudonym.forDay(secret, d), Pseudonym.forDay(secret, d.plusDays(1)))
        assertNotEquals(Pseudonym.forDay(secret, d), Pseudonym.forDay(ByteArray(32) { 7 }, d))
        assertFailsWith<IllegalArgumentException> { Pseudonym.forDay(ByteArray(8), d) }
    }
}
