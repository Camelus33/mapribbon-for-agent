package kr.tedin.maptalkdrive.server.sim

import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.demo.ScenarioDrive
import kr.tedin.maptalkdrive.ribbon.demo.SimVehicle
import kr.tedin.maptalkdrive.ribbon.detect.PassDetector
import kr.tedin.maptalkdrive.ribbon.geo.LngLat
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.live.TestInput
import kr.tedin.maptalkdrive.ribbon.spec.Knot
import kr.tedin.maptalkdrive.ribbon.spec.KnotUpload
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonsResponse
import kr.tedin.maptalkdrive.ribbon.spec.RouteRequest
import kr.tedin.maptalkdrive.ribbon.spec.RouteResponse
import kr.tedin.maptalkdrive.ribbon.spec.RouteSource
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * Verification only. Asks the server for a route exactly as the car would (so it gets the same route id), drives
 * simulated cars over it with the shared detector and uploads their knots marked `simulated`, then prints the ribbons.
 * The car shows the "simulated data" chip for any ribbon built from them.
 *
 * ```
 * ./gradlew :ribbon-server:simulate --args="--from 37.914718,128.290595 --heading 61 --to 38.063314,128.596721 \
 *     --navi-distance 34508 --scenario app/src/main/assets/scenarios/local-weather.json"
 * ```
 * Without `--scenario`, `--cars N` drives N cars that do nothing unusual.
 */
fun main(args: Array<String>) {
    val o = args.toList().windowed(2, 1, partialWindows = true).associate { it[0] to it.getOrNull(1) }
    val server = (o["--server"] ?: "http://127.0.0.1:8787").trimEnd('/')
    val route = TestInput.route(
        origin = o["--from"], headingDeg = o["--heading"]?.toDoubleOrNull(), via = o["--via"], destination = o["--to"],
        destinationName = o["--name"], option = o["--option"], naviDistanceM = o["--navi-distance"]?.toIntOrNull(),
    )
    val req = route?.request(null) ?: run {
        System.err.println("need --from lat,lon and --to lat,lon")
        exitProcess(2)
    }
    val http = HttpClient.newHttpClient()
    fun post(path: String, body: String): String {
        val r = http.send(
            HttpRequest.newBuilder(URI("$server$path")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(r.statusCode() == 200) { "$path → ${r.statusCode()} ${r.body().take(300)}" }
        return r.body()
    }

    val res = RibbonJson.decodeFromString(RouteResponse.serializer(), post("/v1/route", RibbonJson.encodeToString(RouteRequest.serializer(), req.copy(source = RouteSource.TEST))))
    val anchors = res.anchorSet.anchors
    val line = RouteIndex(res.route.geometry.coordinates.map { LngLat(it[0], it[1]) })
    println("route ${res.routeId}: ${res.anchorSet.lengthM} m, ${anchors.size} anchors, ${res.match.verdict} (navi ${res.match.naviDistanceM})")

    val vehicles: List<SimVehicle> = o["--scenario"]?.let { RibbonJson.decodeFromString(Scenario.serializer(), File(it).readText()).vehicles }
        ?: (1..(o["--cars"]?.toIntOrNull() ?: 9)).map { SimVehicle(id = "plain$it", startMinutesAgo = 10 + it * 5) }
    val now = System.currentTimeMillis()
    val knots = mutableListOf<Knot>()
    for (v in vehicles) {
        val detector = PassDetector(line, anchors, pseudonym = "sim-${v.id}", widthClass = v.widthClass)
        val mine = ScenarioDrive.samples(v, line, anchors, startMs = now - v.startMinutesAgo * 60_000L).flatMap { detector.accept(it) }
            .filter { it.exitedAtMs <= now }.toList()
        knots += mine
    }
    println("${vehicles.size} simulated cars tied ${knots.size} knots")
    if ("--dry-run" in args) return
    for (batch in knots.chunked(500)) println("upload: " + post("/v1/knots", RibbonJson.encodeToString(KnotUpload.serializer(), KnotUpload(simulated = true, knots = batch))))

    val rib = http.send(HttpRequest.newBuilder(URI("$server/v1/ribbons?route=${res.routeId}")).GET().build(), HttpResponse.BodyHandlers.ofString())
    val ribbons = RibbonJson.decodeFromString(RibbonsResponse.serializer(), rib.body())
    val byId = ribbons.ribbons.associateBy { it.anchor.id }
    println("%-26s %-9s %-22s %8s  %-10s %-8s %s".format("id", "type", "name", "km", "status", "dev", "top reason"))
    for (a in anchors) {
        val s = byId[a.id] ?: continue
        println(
            "%-26s %-9s %-22s %8.1f  %-10s %-8s %s".format(
                a.id.take(26), a.type, a.name.take(22), a.alongM / 1000.0, s.status, s.deviation,
                s.reasons.maxByOrNull { it.level }?.let { "${it.metric} ${"%.2f".format(it.value)} (n ${s.n})" } ?: "",
            ),
        )
    }
    val b = Briefing.build(anchors, ribbons.ribbons)
    println("different from usual: ${b.items.count { it.different }} · simulated=${ribbons.simulated}")
}
