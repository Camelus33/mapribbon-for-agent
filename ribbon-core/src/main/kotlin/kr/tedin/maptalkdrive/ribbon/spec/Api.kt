package kr.tedin.maptalkdrive.ribbon.spec

import kotlinx.serialization.Serializable

/**
 * What the car and the ribbon server say to each other. The car knows where the navigation is going (points, the
 * navigation's own distance and route option) but not the line; the server draws the line on OSM, hangs anchors on it
 * and answers with ribbons for those anchors.
 */
@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** The navigation's route options, as the Pleos NaviHelper SDK names them. */
@Serializable
enum class RouteOption { RECOMMENDED, SHORT_DISTANCE, SHORT_TIME, FREE, HIGHWAY, COMFORTABLE, SCHOOL_ZONE }

/** Where a route request came from: the car's navigation, or a test input typed in by hand. */
@Serializable
enum class RouteSource { NAVI, TEST }

@Serializable
data class RouteRequest(
    val spec: String = ROUTE_REQUEST_SPEC,
    val origin: LatLon,
    /** Direction of travel at the origin, degrees clockwise from north: keeps the start on the right carriageway. */
    val originHeadingDeg: Double? = null,
    val waypoints: List<LatLon> = emptyList(),
    val destination: LatLon,
    val option: RouteOption = RouteOption.RECOMMENDED,
    /** The navigation's own length of its route, to check that the line drawn here is the same road. */
    val naviDistanceM: Int? = null,
    val destinationName: String? = null,
    val source: RouteSource = RouteSource.NAVI,
) {
    companion object { const val ROUTE_REQUEST_SPEC = "maptalk.route-request/1" }
}

@Serializable
data class LineGeometry(val type: String = "LineString", val coordinates: List<List<Double>>)

@Serializable
data class LineFeature(
    val type: String = "Feature",
    val properties: Map<String, String> = emptyMap(),
    val geometry: LineGeometry,
)

/** Whether the line drawn here is the navigation's route, judged by length. */
@Serializable
enum class MatchVerdict { SAME, DIFFERENT, UNKNOWN }

@Serializable
data class RouteMatch(
    val lengthM: Int,
    val naviDistanceM: Int? = null,
    val diffM: Int? = null,
    val toleranceM: Int? = null,
    val verdict: MatchVerdict,
    /** Which of the candidate lines was taken (0 = the router's first choice) and how many there were. */
    val alternativeIndex: Int = 0,
    val alternatives: Int = 1,
)

@Serializable
data class RouteResponse(
    val spec: String = ROUTE_RESPONSE_SPEC,
    val routeId: String,
    val dataVersion: String,
    val route: LineFeature,
    val anchorSet: AnchorSet,
    val match: RouteMatch,
) {
    companion object { const val ROUTE_RESPONSE_SPEC = "maptalk.route-response/1" }
}

/** Knots one car sends. `simulated` marks knots from the verification simulator, never from a car. */
@Serializable
data class KnotUpload(
    val spec: String = KNOT_UPLOAD_SPEC,
    val simulated: Boolean = false,
    val knots: List<Knot>,
) {
    companion object { const val KNOT_UPLOAD_SPEC = "maptalk.knot-upload/1" }
}

@Serializable
data class KnotUploadResult(val accepted: Int, val duplicates: Int, val rejected: Int)

/** The ribbons of one route as of `nowMs`. `simulated` is true when any knot behind them came from the simulator. */
@Serializable
data class RibbonsResponse(
    val spec: String = RIBBONS_SPEC,
    val routeId: String,
    val nowMs: Long,
    val simulated: Boolean,
    val knots: Int,
    val ribbons: List<RibbonState>,
) {
    companion object { const val RIBBONS_SPEC = "maptalk.ribbons/1" }
}

@Serializable
data class ApiError(val error: String, val message: String? = null)

@Serializable
data class ServerHealth(
    val ready: Boolean,
    val phase: String,
    val dataVersion: String? = null,
    val routesCached: Int = 0,
    val knots: Int = 0,
)
