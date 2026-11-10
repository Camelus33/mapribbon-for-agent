package kr.tedin.maptalkdrive.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.tedin.maptalkdrive.ribbon.spec.Knot

/**
 * Sends this car's knots to the ribbon server in small batches. Knots wait in memory (never on disk) until the server
 * takes them; with no connection (a tunnel, no server) they wait and are tried again later, ever less often. A knot
 * older than the widest ribbon window (3 hours) is of no use to anyone and is dropped.
 */
class KnotUploader(
    private val client: RibbonClient,
    private val scope: CoroutineScope,
    private val onSent: (accepted: Int) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val queue = ArrayDeque<Knot>()
    private var loop: Job? = null
    private var waitMs = EVERY_MS

    val pending: Int get() = synchronized(queue) { queue.size }

    fun add(knots: List<Knot>) {
        if (knots.isEmpty()) return
        val size = synchronized(queue) {
            queue.addAll(knots)
            while (queue.size > MAX_QUEUED) queue.removeFirst()
            queue.size
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        if (size >= BATCH_TRIGGER) scope.launch { flush() }
    }

    private suspend fun run() {
        while (scope.isActive) {
            delay(waitMs)
            if (pending == 0) return
            flush()
        }
    }

    /** Sends what is waiting; true when the queue is empty afterwards. */
    suspend fun flush(): Boolean {
        val cutoff = clock() - MAX_AGE_MS
        while (true) {
            val batch = synchronized(queue) {
                queue.removeAll { it.exitedAtMs < cutoff }
                queue.take(BATCH)
            }
            if (batch.isEmpty()) { waitMs = EVERY_MS; return true }
            try {
                val r = client.upload(batch)
                val sent = batch.toHashSet()
                synchronized(queue) { queue.removeAll { it in sent } }
                waitMs = EVERY_MS
                onSent(r.accepted)
            } catch (e: RibbonClient.Failure) {
                Log.i(TAG, "upload failed, ${batch.size} knots wait: ${e.message}")
                waitMs = (waitMs * 2).coerceAtMost(MAX_WAIT_MS)
                return false
            }
        }
    }

    private companion object {
        const val TAG = "KnotUploader"
        const val EVERY_MS = 30_000L
        const val MAX_WAIT_MS = 5 * 60_000L
        const val BATCH = 200
        const val BATCH_TRIGGER = 20
        const val MAX_QUEUED = 2_000
        const val MAX_AGE_MS = 3 * 60 * 60_000L
    }
}
