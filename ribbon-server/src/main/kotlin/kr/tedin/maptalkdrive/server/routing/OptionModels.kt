package kr.tedin.maptalkdrive.server.routing

import com.graphhopper.json.Statement
import com.graphhopper.json.Statement.Op.MULTIPLY
import com.graphhopper.util.CustomModel
import kr.tedin.maptalkdrive.ribbon.spec.RouteOption

/**
 * The navigation's route options as request custom models. Landmarks only allow a request to make roads less
 * attractive (factors at most 1, distance influence at least the profile's), so every option is written that way.
 * The factors are starting values, not tuned against the navigation.
 */
object OptionModels {
    fun of(option: RouteOption): CustomModel? = when (option) {
        // School zones are not in OSM in a form the router can use; the recommended route is the closest we have.
        RouteOption.RECOMMENDED, RouteOption.SHORT_TIME, RouteOption.SCHOOL_ZONE -> null
        RouteOption.SHORT_DISTANCE -> CustomModel().setDistanceInfluence(300.0)
        // Korean expressways charge tolls but are rarely tagged toll=yes: an untagged motorway counts as tolled.
        RouteOption.FREE -> CustomModel().addToPriority(
            Statement.If("toll == ALL || (toll == MISSING && road_class == MOTORWAY)", MULTIPLY, "0.05"),
        )
        RouteOption.HIGHWAY -> CustomModel().addToPriority(
            Statement.If("road_class != MOTORWAY && road_class != TRUNK", MULTIPLY, "0.6"),
        )
        RouteOption.COMFORTABLE -> CustomModel()
            .addToPriority(
                Statement.If(
                    "road_class == RESIDENTIAL || road_class == UNCLASSIFIED || road_class == SERVICE || " +
                        "road_class == LIVING_STREET || road_class == TRACK",
                    MULTIPLY, "0.4",
                ),
            )
            .addToPriority(Statement.ElseIf("road_class == TERTIARY", MULTIPLY, "0.8"))
    }
}
