package kr.tedin.maptalkdrive.demo

import android.content.Context
import kr.tedin.maptalkdrive.ribbon.agg.Baseline
import kr.tedin.maptalkdrive.ribbon.demo.Scenario
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.Curvature
import kr.tedin.maptalkdrive.ribbon.geo.RouteIndex
import kr.tedin.maptalkdrive.ribbon.spec.AnchorSet
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson

/** Everything bundled in the APK so the demo runs with no server: route, anchors, usual values, scenarios. */
class DemoData(
    val anchorSet: AnchorSet,
    val route: RouteIndex,
    val baseline: Baseline,
    val scenarios: List<Scenario>,
) {
    /** How the route bends, computed once from its own geometry. */
    val curves: List<CurveStretch> by lazy { Curvature.stretches(route) }

    companion object {
        fun load(context: Context): DemoData {
            val a = context.assets
            fun text(path: String) = a.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return DemoData(
                anchorSet = RibbonJson.decodeFromString(text("anchors.json")),
                route = RouteIndex.fromGeoJson(text("route.geojson")),
                baseline = RibbonJson.decodeFromString(text("baseline.json")),
                scenarios = a.list("scenarios").orEmpty().sorted().map { RibbonJson.decodeFromString(text("scenarios/$it")) },
            )
        }
    }
}
