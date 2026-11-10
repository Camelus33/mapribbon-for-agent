package kr.tedin.maptalkdrive.demo

import android.content.Context
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.Curvature
import kr.tedin.maptalkdrive.ribbon.geo.ElevationProfile
import kr.tedin.maptalkdrive.ribbon.geo.Hairpin
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson

/** One route: its line, the facilities on it, and how it bends (computed once from its own geometry). */
class RouteData(val anchorSet: AnchorSet, val route: RouteIndex, val elevation: ElevationProfile? = null) {
    val id: String get() = anchorSet.routeId
    val curves: List<CurveStretch> by lazy { Curvature.stretches(route) }
    val hairpins: List<Hairpin> by lazy { Curvature.hairpins(route) }
}

/**
 * Everything bundled in the APK so the demo runs with no server: the routes (`assets/routes/<id>/`), usual values for
 * every anchor and the scenarios, each of which names the route it drives.
 */
class DemoData(
    val routes: List<RouteData>,
    val baseline: Baseline,
    val scenarios: List<Scenario>,
) {
    fun route(id: String): RouteData = routes.firstOrNull { it.id == id } ?: routes.first()

    /**
     * The scenarios the demo panel offers, in the order they are shown. The others stay in the APK (the tests drive
     * them, and `adb ... --es demo_scenario <id>` still runs them) but are not offered.
     */
    val shownScenarios: List<Scenario> get() = SHOWN.mapNotNull { id -> scenarios.firstOrNull { it.id == id } }

    companion object {
        /** The demo is Jeju City to Jungmun: the app opens on 1100 Road. */
        const val FIRST_ROUTE = "J1100S"

        /**
         * What the second-round demo shows, strongest first: the ice at the 1100 m summit, then the same signal on the
         * whole road that the ribbons rightly leave alone, then hairpins and fog past Yeongsil, and Pyeonghwa-ro, the
         * straight road between the same two places. The Gangwon scenarios tell the same stories and are not offered.
         */
        val SHOWN = listOf("jeju-1100-ice", "jeju-1100-sleet-everywhere", "jeju-1100-fog", "jeju-pyeonghwa")

        fun load(context: Context): DemoData {
            val a = context.assets
            fun text(path: String) = a.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val routes = a.list("routes").orEmpty().sortedBy { if (it == FIRST_ROUTE) "" else it }.map { id ->
                RouteData(
                    RibbonJson.decodeFromString(text("routes/$id/anchors.json")),
                    RouteIndex.fromGeoJson(text("routes/$id/route.geojson")),
                    // Heights are optional: a route without them simply has no elevation strip.
                    if ("elevation.json" in a.list("routes/$id").orEmpty()) ElevationProfile.fromJson(text("routes/$id/elevation.json")) else null,
                )
            }
            return DemoData(
                routes = routes,
                baseline = RibbonJson.decodeFromString(text("baseline.json")),
                scenarios = a.list("scenarios").orEmpty().sorted().map { RibbonJson.decodeFromString(text("scenarios/$it")) },
            )
        }
    }
}
