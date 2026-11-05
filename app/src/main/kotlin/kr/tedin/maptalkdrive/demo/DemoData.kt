package kr.tedin.maptalkdrive.demo

import android.content.Context
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.Curvature
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson

/** One route: its line, the facilities on it, and how it bends (computed once from its own geometry). */
class RouteData(val anchorSet: AnchorSet, val route: RouteIndex) {
    val id: String get() = anchorSet.routeId
    val curves: List<CurveStretch> by lazy { Curvature.stretches(route) }
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

    companion object {
        /** The expressway comes first: it is the route the app opens on. */
        const val FIRST_ROUTE = "SY60E"

        fun load(context: Context): DemoData {
            val a = context.assets
            fun text(path: String) = a.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val routes = a.list("routes").orEmpty().sortedBy { if (it == FIRST_ROUTE) "" else it }.map { id ->
                RouteData(RibbonJson.decodeFromString(text("routes/$id/anchors.json")), RouteIndex.fromGeoJson(text("routes/$id/route.geojson")))
            }
            return DemoData(
                routes = routes,
                baseline = RibbonJson.decodeFromString(text("baseline.json")),
                scenarios = a.list("scenarios").orEmpty().sorted().map { RibbonJson.decodeFromString(text("scenarios/$it")) },
            )
        }
    }
}
