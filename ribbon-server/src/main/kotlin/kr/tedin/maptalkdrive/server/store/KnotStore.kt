package kr.tedin.maptalkdrive.server.store

import kr.tedin.maptalkdrive.ribbon.route.StableIds
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.KnotUpload
import kr.tedin.maptalkdrive.ribbon.spec.KnotUploadResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Knots in memory only, for as long as the widest ribbon window needs them (3 hours), then gone. One knot per car,
 * anchor and entry time: a car that sends the same knot twice counts once. Pseudonyms are never logged.
 */
class KnotStore(
    private val clock: () -> Long = System::currentTimeMillis,
    val retentionMs: Long = 3 * 60 * 60_000L,
) {
    private data class Entry(val knot: Knot, val simulated: Boolean)

    private val byAnchor = ConcurrentHashMap<String, MutableList<Entry>>()
    private val keys = ConcurrentHashMap.newKeySet<String>()

    val size: Int get() = byAnchor.values.sumOf { synchronized(it) { it.size } }

    fun add(upload: KnotUpload): KnotUploadResult {
        val now = clock()
        var accepted = 0; var duplicates = 0; var rejected = 0
        for (k in upload.knots) {
            if (!valid(k, now)) { rejected++; continue }
            if (!keys.add("${k.pseudonym}|${k.anchorId}|${k.enteredAtMs}")) { duplicates++; continue }
            val list = byAnchor.getOrPut(k.anchorId) { mutableListOf() }
            synchronized(list) { list += Entry(k, upload.simulated) }
            accepted++
        }
        return KnotUploadResult(accepted, duplicates, rejected)
    }

    /** The knots kept for these anchors, and whether any of them came from the simulator. */
    fun knotsFor(anchorIds: Collection<String>): Pair<List<Knot>, Boolean> {
        val out = mutableListOf<Knot>()
        var simulated = false
        for (id in anchorIds) {
            val list = byAnchor[id] ?: continue
            synchronized(list) {
                for (e in list) { out += e.knot; if (e.simulated) simulated = true }
            }
        }
        return out to simulated
    }

    fun prune() {
        val cutoff = clock() - retentionMs
        for ((id, list) in byAnchor) {
            synchronized(list) {
                val gone = list.filter { it.knot.exitedAtMs < cutoff }
                list.removeAll(gone)
                gone.forEach { keys.remove("${it.knot.pseudonym}|${it.knot.anchorId}|${it.knot.enteredAtMs}") }
                if (list.isEmpty()) byAnchor.remove(id, list)
            }
        }
    }

    private fun valid(k: Knot, now: Long): Boolean =
        k.spec == Knot.KNOT_SPEC &&
            k.anchorId.length <= 128 && StableIds.isSafe(k.anchorId) &&
            k.pseudonym.isNotBlank() && k.pseudonym.length <= 64 &&
            k.enteredAtMs <= k.exitedAtMs &&
            k.exitedAtMs >= now - retentionMs && k.exitedAtMs <= now + 5 * 60_000L
}
