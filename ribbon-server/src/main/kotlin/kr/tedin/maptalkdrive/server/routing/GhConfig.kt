package kr.tedin.maptalkdrive.server.routing

import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.LMProfile
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.TurnCostsConfig
import java.io.File

/**
 * GraphHopper 11.1 for drawing the navigation's route on OSM. One car profile prepared for landmarks (LM), so each
 * request can bring its own custom model for the navigation's route option (CH would not allow that). The encoded
 * values listed are the ones the path details need: which OSM way, tunnel or not, road class, toll.
 */
object GhConfig {
    const val PROFILE = "car"

    /** Bump when anything below changes: the graph on disk was built for this configuration. */
    const val GRAPH_VERSION = "v1"

    val PATH_DETAILS = listOf("osm_way_id", "road_environment", "road_class", "road_class_link", "street_ref", "street_name")

    /** GraphHopper's car model (custom_models/car.json) written out, so nothing depends on parsing the bundled file. */
    fun carModel(): CustomModel = CustomModel()
        .setDistanceInfluence(90.0)
        .addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
        .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))

    fun config(osmFile: File, graphDir: File, small: Boolean = false): GraphHopperConfig {
        val car = Profile(PROFILE)
            .setCustomModel(carModel())
            .setTurnCostsConfig(TurnCostsConfig.car().setUTurnCosts(60))
        return GraphHopperConfig()
            .putObject("datareader.file", osmFile.absolutePath)
            .putObject("graph.location", graphDir.absolutePath)
            .putObject(
                "graph.encoded_values",
                "car_access, car_average_speed, road_access, road_class, road_class_link, road_environment, toll, osm_way_id",
            )
            .putObject("graph.dataaccess.default_type", "RAM_STORE")
            .putObject(
                "import.osm.ignored_highways",
                "footway,cycleway,path,pedestrian,steps,track,bridleway,corridor,platform,construction,proposed,elevator,bus_guideway,raceway",
            )
            .putObject("datareader.worker_threads", 2)
            .putObject("prepare.min_network_size", if (small) 0 else 200)
            .putObject("prepare.lm.landmarks", if (small) 4 else 16)
            .putObject("prepare.lm.threads", 2)
            .putObject("routing.non_ch.max_waypoint_distance", 1_000_000)
            .putObject("routing.max_visited_nodes", 3_000_000)
            .putObject("routing.timeout_ms", 10_000)
            .setProfiles(listOf(car))
            .setLMProfiles(listOf(LMProfile(PROFILE)))
    }

    /** Imports the OSM file into `graphDir` the first time (minutes for Korea), loads it afterwards. */
    fun open(osmFile: File, graphDir: File, small: Boolean = false): GraphHopper =
        GraphHopper().init(config(osmFile, graphDir, small)).also { it.importOrLoad() }
}
