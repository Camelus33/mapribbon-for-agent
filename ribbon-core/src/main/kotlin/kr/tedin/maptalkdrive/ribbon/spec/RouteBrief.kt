package kr.tedin.maptalkdrive.ribbon.spec

import kotlinx.serialization.Serializable
import kr.tedin.maptalkdrive.ribbon.geo.CurveStretch
import kr.tedin.maptalkdrive.ribbon.geo.Hairpin

/**
 * `maptalk.route/1`: what an agent needs to know about the road itself before it reads the ribbons on it. How the
 * route bends comes from its own geometry (stretches by class, and the hairpins inside them); the facilities are the
 * anchors the ribbons hang on.
 */
@Serializable
data class RouteBrief(
    val spec: String = ROUTE_SPEC,
    val routeId: String,
    val routeName: String,
    val lengthM: Int,
    val attribution: String,
    val curves: List<CurveStretch>,
    val hairpins: List<Hairpin> = emptyList(),
    val anchors: List<AnchorRef>,
) {
    companion object { const val ROUTE_SPEC = "maptalk.route/1" }
}
