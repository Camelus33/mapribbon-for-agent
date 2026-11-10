package kr.tedin.maptalkdrive.server.elevation

import kr.tedin.maptalkdrive.ribbon.geo.ElevationFile
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.route.ElevationBuilder
import kr.tedin.maptalkdrive.ribbon.route.PathFacts
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The height of the road along each route the server drew, built in the background as soon as the route is drawn:
 * a long route needs a few dozen height requests, and the route itself should not wait for them. A failed build is
 * tried again when asked for, at most once a minute.
 */
class ElevationService(
    private val heights: HeightSource,
    private val tunnelName: (Long) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    threads: Int = 2,
) {
    sealed interface State {
        data object Pending : State
        data class Ready(val file: ElevationFile) : State
        data class Failed(val reason: String, val atMs: Long) : State
    }

    private val states: MutableMap<String, State> = Collections.synchronizedMap(
        object : LinkedHashMap<String, State>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?) = size > 256
        },
    )
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "elevation").apply { isDaemon = true } }

    /** The state for this route, starting (or retrying) the build when there is none yet or the last one failed. */
    fun ensure(routeId: String, facts: PathFacts): State = synchronized(states) {
        val s = states[routeId]
        if (s is State.Pending || s is State.Ready) return s
        if (s is State.Failed && clock() - s.atMs < RETRY_MS) return s
        states[routeId] = State.Pending
        pool.submit { states[routeId] = build(routeId, facts) }
        State.Pending
    }

    private fun build(routeId: String, facts: PathFacts): State = try {
        val route = RouteIndex(facts.path)
        val structures = ElevationBuilder.structures(facts, route.cum, tunnelName)
        val alongs = ElevationBuilder.sampleAlongs(route)
        val asked = ElevationBuilder.askedIndices(alongs.size)
        val got = heights.heights(asked.map { route.pointAt(alongs[it]) })
        val raw = ElevationBuilder.fill(alongs, asked, got)
        State.Ready(ElevationBuilder.build(routeId, route, structures, raw))
    } catch (e: Throwable) {
        System.err.println("ribbon-server: elevation for $routeId failed: $e")
        State.Failed(e.message ?: e.javaClass.simpleName, clock())
    }

    private companion object {
        const val RETRY_MS = 60_000L
    }
}
