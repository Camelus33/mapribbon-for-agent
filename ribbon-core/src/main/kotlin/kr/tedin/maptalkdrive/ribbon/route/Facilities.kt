package kr.tedin.maptalkdrive.ribbon.route

import kotlinx.serialization.Serializable
import kotlin.math.floor

/** What kind of OSM node a facility candidate is. */
@Serializable
enum class FacilityKind {
    /** highway=motorway_junction: an expressway exit or interchange. */
    MOTORWAY_JUNCTION,
    /** A named node whose name says it is a crossing (…교차로, …삼거리, …IC, …입구). */
    NAMED_NODE,
    /** barrier=toll_booth or highway=toll_gantry. */
    TOLL,
    /** highway=speed_camera. */
    CAMERA,
}

@Serializable
data class FacilityNode(
    val id: Long,
    val kind: FacilityKind,
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val nameEn: String? = null,
    /** A camera's `direction` tag in degrees, when it is a number. */
    val directionDeg: Double? = null,
)

/** The name of a tunnel way (tunnel:name, else a name that says tunnel). */
@Serializable
data class TunnelWay(val wayId: Long, val name: String? = null, val nameEn: String? = null)

/** Everything a scan of the OSM file keeps for anchors, as written to the server's cache. */
@Serializable
data class FacilityFile(
    val spec: String = "maptalk.facilities/1",
    val dataVersion: String,
    val nodes: List<FacilityNode>,
    val tunnels: List<TunnelWay>,
)

/** Facility candidates with a lookup by area (0.01° cells) and tunnel names by way id. */
class FacilityCatalog(val file: FacilityFile) {
    private val byCell: Map<Long, List<FacilityNode>> = file.nodes.groupBy { cellKey(it.lat, it.lon) }
    private val tunnelById: Map<Long, TunnelWay> = file.tunnels.associateBy { it.wayId }

    val dataVersion: String get() = file.dataVersion

    fun tunnel(wayId: Long): TunnelWay? = tunnelById[wayId]

    /** Nodes in the given cells. */
    fun nodesIn(cells: Collection<Long>): List<FacilityNode> = cells.flatMap { byCell[it].orEmpty() }

    companion object {
        const val CELL_DEG = 0.01
        fun cellKey(lat: Double, lon: Double): Long = key(floor(lat / CELL_DEG).toLong(), floor(lon / CELL_DEG).toLong())
        fun key(cy: Long, cx: Long): Long = cy * 1_000_000L + cx
        val EMPTY = FacilityCatalog(FacilityFile(dataVersion = "empty", nodes = emptyList(), tunnels = emptyList()))
    }
}
