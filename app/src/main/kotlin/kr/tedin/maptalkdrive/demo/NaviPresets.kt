package kr.tedin.maptalkdrive.demo

import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.ribbon.live.NaviRoute
import kr.tedin.maptalkdrive.ribbon.spec.LatLon
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption
import kr.tedin.maptalkdrive.ribbon.spec.RouteSource

/**
 * Navigation routes for the demo, typed in as tests (the emulator's navigation does not answer): the same trips as the
 * bundled Jeju routes, so the bundled scenarios can be sent down them. Each starts where its bundled route starts,
 * heading the same way, and gives the bundled length as the navigation's.
 */
data class NaviPreset(
    val id: String, val label: Int, val bundledRouteId: String, val route: NaviRoute,
    /** Where the trip starts, by name: a test input has no name of its own, but the preset knows its place. */
    val originName: String, val originNameEn: String,
)

object NaviPresets {
    private val NOHYEONG_1100 = LatLon(33.484742, 126.480066)
    private val NOHYEONG_1135 = LatLon(33.484405, 126.473094)
    private val JUNGMUN = LatLon(33.251378, 126.431463)
    /** Without it the router leaves Pyeonghwa-ro before Donggwang IC and cuts across to Jungmun. */
    private val SEOGWANG_1 = LatLon(33.288571, 126.32422)

    val all = listOf(
        NaviPreset(
            "jeju-1100", R.string.preset_1100, "J1100S",
            NaviRoute(
                origin = NOHYEONG_1100, originIsCurrent = false, originHeadingDeg = 162.0, waypoints = emptyList(),
                destination = JUNGMUN, destinationName = "중문", option = RouteOption.RECOMMENDED, naviDistanceM = 34_243,
                source = RouteSource.TEST,
            ),
            originName = "노형", originNameEn = "Nohyeong",
        ),
        NaviPreset(
            "jeju-pyeonghwa", R.string.preset_pyeonghwa, "J1135S",
            NaviRoute(
                origin = NOHYEONG_1135, originIsCurrent = false, originHeadingDeg = 140.0, waypoints = listOf(SEOGWANG_1),
                destination = JUNGMUN, destinationName = "중문", option = RouteOption.RECOMMENDED, naviDistanceM = 39_162,
                source = RouteSource.TEST,
            ),
            originName = "노형", originNameEn = "Nohyeong",
        ),
    )

    /** The preset a navigation route came from, if it did. */
    fun of(route: NaviRoute?): NaviPreset? = route?.let { r -> all.firstOrNull { it.route.samePlaces(r) } }
}
