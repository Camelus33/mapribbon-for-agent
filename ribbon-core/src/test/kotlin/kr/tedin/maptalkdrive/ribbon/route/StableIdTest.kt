package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ids belong to the road: routes that share a stretch of road share its anchors' ids; the other direction does not. */
class StableIdTest {
    private fun build(f: PathFacts, id: String = "R") = AnchorBuilder.build(id, "test", null, f, Winding.catalog)

    private val a = build(Winding.slice(0))

    /** Segments other than the route's own first piece (which ends at the route's origin). */
    private fun AnchorSet.interiorSegments() = anchors.filter { it.type == AnchorType.SEGMENT }.let { segs ->
        val firstBase = segs.first().id.substringBefore('-')
        segs.filter { it.id.substringBefore('-') != firstBase }
    }

    @Test fun `a route starting later on the same road shares every id after its first piece`() {
        for (b in listOf(build(Winding.slice(100)), build(Winding.fromMidEdge(100)))) {
            val ids = a.anchors.map { it.id }.toSet()
            val facilities = b.anchors.filter { it.type != AnchorType.SEGMENT }
            assertTrue(facilities.isNotEmpty())
            assertTrue(facilities.all { it.id in ids }, "facilities: ${facilities.map { it.id }.filter { it !in ids }}")
            val segs = b.interiorSegments()
            assertTrue(segs.size >= 4)
            assertTrue(segs.all { it.id in ids }, "segments: ${segs.filter { it.id !in ids }.map { it.name }}")
        }
    }

    @Test fun `a facility on the route's first, partial edge keeps its id`() {
        val camera = a.anchors.single { it.type == AnchorType.CAMERA }
        val b = build(Winding.fromMidEdge(259))
        assertEquals(camera.id, b.anchors.single { it.type == AnchorType.CAMERA }.id)
    }

    @Test fun `a route that branches off shares the ids before the branch and not after`() {
        val d = build(Winding.branching(300))
        val ids = a.anchors.map { it.id }.toSet()
        val shared = d.interiorSegments().filter { it.endAlongM < 300 * 50 - 2_500 }
        assertTrue(shared.isNotEmpty())
        assertTrue(shared.all { it.id in ids })
        assertTrue(d.anchors.any { it.type == AnchorType.SEGMENT && it.id !in ids })
        assertTrue(d.anchors.single { it.type == AnchorType.TUNNEL }.id in ids)
    }

    @Test fun `the other direction shares no id`() {
        val r = build(Winding.reversed())
        val ids = a.anchors.map { it.id }.toSet()
        assertTrue(r.anchors.isNotEmpty())
        assertTrue(r.anchors.none { it.id in ids }, r.anchors.filter { it.id in ids }.map { it.id }.toString())
    }

    @Test fun `building twice gives the same anchors, ids unique and safe`() {
        assertEquals(a, build(Winding.slice(0)))
        val ids = a.anchors.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { '~' in it }, "a road re-entering a grid cell must not need a duplicate suffix: $ids")
        assertTrue(ids.all { StableIds.isSafe(it) }, ids.filterNot { StableIds.isSafe(it) }.toString())
    }

    @Test fun `ids do not depend on the route id or name`() {
        val other = AnchorBuilder.build("OTHER", "다른 이름", "Other", Winding.slice(0), Winding.catalog)
        assertEquals(a.anchors.map { it.id }, other.anchors.map { it.id })
    }
}
