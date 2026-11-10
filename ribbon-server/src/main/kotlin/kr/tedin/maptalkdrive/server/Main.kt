package kr.tedin.maptalkdrive.server

import kr.tedin.maptalkdrive.ribbon.route.FacilityCatalog
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.server.api.HttpApi
import kr.tedin.maptalkdrive.server.elevation.ElevationService
import kr.tedin.maptalkdrive.server.elevation.OpenMeteoHeights
import kr.tedin.maptalkdrive.server.facility.FacilityScanner
import kr.tedin.maptalkdrive.server.routing.GhConfig
import kr.tedin.maptalkdrive.server.routing.GhRouter
import kr.tedin.maptalkdrive.server.service.RouteService
import kr.tedin.maptalkdrive.server.store.KnotStore
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val OSM_URL = "https://download.geofabrik.de/asia/south-korea-latest.osm.pbf"

/**
 * The ribbon server. `--data <dir>` holds south-korea-latest.osm.pbf (download it first; see the README), the graph
 * built from it and the facility cache. The first start imports Korea (minutes); later starts load in under a minute.
 * `--bench` imports, times a few routes and exits.
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 1, partialWindows = true).associate { it[0] to it.getOrNull(1) }
    val data = File(opts["--data"] ?: "ribbon-server/data")
    val port = opts["--port"]?.toIntOrNull() ?: 8787
    val bind = opts["--bind"] ?: "127.0.0.1"
    val osm = File(data, "south-korea-latest.osm.pbf")
    if (!osm.isFile) {
        System.err.println("No OSM file at ${osm.path}. Download it once (291 MB) with:")
        System.err.println("  ./gradlew :ribbon-server:downloadOsm")
        System.err.println("or: curl -fL -o ${osm.path} $OSM_URL")
        exitProcess(2)
    }

    val knots = KnotStore()
    val api = HttpApi(knots, host = bind, port = port)
    if ("--bench" !in args) {
        api.start()
        println("ribbon-server: listening on http://$bind:${api.port} (loading the map)")
    }
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "prune").apply { isDaemon = true } }
        .scheduleAtFixedRate({ knots.prune() }, 60, 60, TimeUnit.SECONDS)

    val t0 = System.currentTimeMillis()
    val facilities = CompletableFuture.supplyAsync {
        api.phase.set("scanning facilities")
        FacilityScanner.loadOrScan(osm)
    }
    val hopper = GhConfig.open(osm, File(data, "graph-cache-${GhConfig.GRAPH_VERSION}"))
    val file = facilities.get()
    val router = GhRouter(hopper)
    val catalog = FacilityCatalog(file)
    val elevation = ElevationService(OpenMeteoHeights(File(data, "heights-v1.tsv")), tunnelName = { catalog.tunnel(it)?.name })
    val service = RouteService(router::route, catalog, file.dataVersion, onDrawn = { id, facts -> elevation.ensure(id, facts) })
    api.elevation.set(elevation)
    Runtime.getRuntime().addShutdownHook(Thread { hopper.close() })
    api.service.set(service)
    api.phase.set("ready")
    println(
        "ribbon-server: ready in ${(System.currentTimeMillis() - t0) / 1000} s · ${file.dataVersion} · " +
            "${file.nodes.size} facility nodes, ${file.tunnels.size} named tunnel ways",
    )
    if ("--bench" in args) {
        bench(service)
        hopper.close()
        exitProcess(0)
    }
}

private fun bench(service: RouteService) {
    val cases = listOf(
        "SY60E 인제IC→양양JC" to RouteRequest(origin = LatLon(37.914718, 128.290595), originHeadingDeg = 65.0, destination = LatLon(38.063314, 128.596721), naviDistanceM = 34_508),
        "N44E 인제→양양" to RouteRequest(origin = LatLon(38.0697, 128.1702), destination = LatLon(38.0752, 128.6190), naviDistanceM = 51_156),
        "서울→부산" to RouteRequest(origin = LatLon(37.5547, 126.9707), destination = LatLon(35.1151, 129.0422)),
        "제주 1100도로" to RouteRequest(origin = LatLon(33.4846, 126.4810), destination = LatLon(33.2531, 126.4123)),
    )
    for ((name, base) in cases) {
        val times = RouteOption.entries.map { opt ->
            val req = base.copy(option = opt)
            val t = System.nanoTime()
            val r = runCatching { service.route(req) }
            val ms = (System.nanoTime() - t) / 1_000_000
            val res = r.getOrNull()
            println(
                "%-18s %-15s %6d ms  %s".format(
                    name, opt, ms,
                    res?.let { "len ${it.anchorSet.lengthM} m, ${it.anchorSet.anchors.size} anchors, ${it.match.verdict}" } ?: "error ${r.exceptionOrNull()?.message}",
                ),
            )
            ms
        }.sorted()
        println("%-18s p50 %d ms, max %d ms".format(name, times[times.size / 2], times.last()))
    }
}
