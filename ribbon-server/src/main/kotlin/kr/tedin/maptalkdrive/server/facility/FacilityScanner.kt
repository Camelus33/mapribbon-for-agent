package kr.tedin.maptalkdrive.server.facility

import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMFileHeader
import com.graphhopper.reader.osm.OSMInputFile
import com.graphhopper.reader.osm.SkipOptions
import kr.tedin.maptalkdrive.ribbon.route.FacilityFile
import kr.tedin.maptalkdrive.ribbon.route.FacilityKind
import kr.tedin.maptalkdrive.ribbon.route.FacilityNode
import kr.tedin.maptalkdrive.ribbon.route.TunnelWay
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One pass over the OSM file for what anchors hang on and the router does not keep: named expressway junctions and
 * crossings, toll booths, speed cameras, and the names of tunnel ways. The result is cached next to the OSM file and
 * reused while the file is unchanged.
 */
object FacilityScanner {
    private val CROSSING = Regex("(교차로|삼거리|사거리|오거리|IC|JC|입구|나들목|분기점)$")
    private val TUNNEL_NAME = Regex("(터널|지하차도)$")
    /** A named node with one of these tags is a shop, stop or station named after a crossing, not the crossing. */
    private val NOT_A_CROSSING = listOf("amenity", "shop", "tourism", "public_transport", "railway", "bus", "place", "office", "leisure", "building", "craft")

    fun loadOrScan(osmFile: File, cacheDir: File = osmFile.parentFile): FacilityFile {
        val cache = File(cacheDir, "facilities-v1-${osmFile.length()}-${osmFile.lastModified()}.json.gz")
        if (cache.isFile) {
            GZIPInputStream(cache.inputStream()).bufferedReader(Charsets.UTF_8).use {
                return RibbonJson.decodeFromString(FacilityFile.serializer(), it.readText())
            }
        }
        val file = scan(osmFile)
        val tmp = File(cacheDir, cache.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter(Charsets.UTF_8).use {
            it.write(RibbonJson.encodeToString(FacilityFile.serializer(), file))
        }
        tmp.renameTo(cache)
        return file
    }

    fun scan(osmFile: File): FacilityFile {
        val nodes = mutableListOf<FacilityNode>()
        val tunnels = mutableListOf<TunnelWay>()
        var timestamp: String? = null
        OSMInputFile(osmFile).setWorkerThreads(2).setSkipOptions(SkipOptions(false, false, true)).open().use { input ->
            while (true) {
                val e: ReaderElement = input.next ?: break
                when (e) {
                    is OSMFileHeader -> timestamp = e.getTag("timestamp")
                    is ReaderNode -> node(e)?.let { nodes += it }
                    is ReaderWay -> tunnel(e)?.let { tunnels += it }
                    else -> Unit
                }
            }
        }
        val version = "${osmFile.nameWithoutExtension.substringBefore('.')}@${timestamp ?: "${osmFile.length()}-${osmFile.lastModified()}"}"
        return FacilityFile(dataVersion = version, nodes = nodes, tunnels = tunnels)
    }

    private fun node(n: ReaderNode): FacilityNode? {
        if (!n.hasTags()) return null
        val name = n.getTag("name") ?: n.getTag("name:ko")
        val nameEn = n.getTag("name:en")
        val kind = when {
            n.hasTag("highway", "motorway_junction") -> FacilityKind.MOTORWAY_JUNCTION
            n.hasTag("barrier", "toll_booth") || n.hasTag("highway", "toll_gantry") -> FacilityKind.TOLL
            n.hasTag("highway", "speed_camera") -> FacilityKind.CAMERA
            name != null && CROSSING.containsMatchIn(name) && NOT_A_CROSSING.none { n.getTag(it) != null } &&
                !n.hasTag("highway", "bus_stop") -> FacilityKind.NAMED_NODE
            else -> return null
        }
        val direction = if (kind == FacilityKind.CAMERA) n.getTag("direction")?.toDoubleOrNull() else null
        return FacilityNode(n.id, kind, n.lat, n.lon, name, nameEn, direction)
    }

    private fun tunnel(w: ReaderWay): TunnelWay? {
        if (!w.hasTag("tunnel", "yes") || w.getTag("highway") == null) return null
        val tunnelName = w.getTag("tunnel:name") ?: w.getTag("tunnel:name:ko")
        val name = tunnelName ?: w.getTag("name")?.takeIf { TUNNEL_NAME.containsMatchIn(it) } ?: return null
        val nameEn = if (tunnelName != null) w.getTag("tunnel:name:en") else w.getTag("name:en")
        return TunnelWay(w.id, name, nameEn)
    }
}
