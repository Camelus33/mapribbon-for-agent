package kr.tedin.maptalkdrive.channel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.spec.AnchorRef
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RouteBrief

/**
 * Where other agents in the car read the ribbon channel. Read-only; every row carries the `maptalk.ribbon/1` (or
 * `maptalk.route/1`) JSON an agent can parse, next to flat columns for a quick look.
 *
 * - `content://kr.tedin.maptalkdrive.ribbons/ribbons`              every facility on the route, in driving order
 * - `content://kr.tedin.maptalkdrive.ribbons/ribbons/<anchorId>`   one facility
 * - `content://kr.tedin.maptalkdrive.ribbons/next?along_m=12000`   the next facility ahead that is different from usual
 * - `content://kr.tedin.maptalkdrive.ribbons/route`                how the route bends, and its facilities
 *
 * Ribbons are aggregates of at least three cars with no identities in them, so the provider needs no permission.
 */
class RibbonProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "ribbons", RIBBONS)
        addURI(AUTHORITY, "ribbons/*", RIBBON)
        addURI(AUTHORITY, "next", NEXT)
        addURI(AUTHORITY, "route", ROUTE)
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val channel = RibbonChannel.get(context!!)
        val anchors = channel.data.anchorSet.anchors
        return when (matcher.match(uri)) {
            RIBBONS -> ribbonCursor(Briefing.build(anchors, channel.ribbons()).items.map { it to null })
            RIBBON -> {
                val id = uri.lastPathSegment
                ribbonCursor(Briefing.build(anchors, channel.ribbons()).items.filter { it.anchor.id == id }.map { it to null })
            }
            NEXT -> {
                val along = uri.getQueryParameter("along_m")?.toDoubleOrNull() ?: 0.0
                ribbonCursor(listOfNotNull(Briefing.build(anchors, channel.ribbons()).nextDifferentAhead(along)))
            }
            ROUTE -> {
                val set = channel.data.anchorSet
                val brief = RouteBrief(
                    routeId = set.routeId, routeName = set.routeName, lengthM = set.lengthM, attribution = set.attribution,
                    curves = channel.data.curves,
                    anchors = set.anchors.sortedBy { it.alongM }.map { AnchorRef(it.id, it.type, it.dir, it.name, it.virtual) },
                )
                MatrixCursor(arrayOf("route_id", "name", "length_m", "json")).apply {
                    addRow(arrayOf<Any?>(set.routeId, set.routeName, set.lengthM, RibbonJson.encodeToString(RouteBrief.serializer(), brief)))
                }
            }
            else -> throw IllegalArgumentException("unknown uri $uri")
        }
    }

    private fun ribbonCursor(rows: List<Pair<BriefingItem, Double?>>): Cursor =
        MatrixCursor(COLUMNS).apply {
            for ((item, distance) in rows) {
                val s = item.state
                addRow(arrayOf<Any?>(
                    item.anchor.id, item.anchor.type.name, item.anchor.name, item.anchor.alongM, item.pointM,
                    distance?.toInt(), s.status.name, s.deviation.name, s.n, s.window?.minutes,
                    s.reasons.firstOrNull()?.metric, s.gist, RibbonJson.encodeToString(RibbonState.serializer(), s),
                ))
            }
        }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        RIBBONS, NEXT -> "vnd.android.cursor.dir/vnd.$AUTHORITY.ribbon"
        RIBBON -> "vnd.android.cursor.item/vnd.$AUTHORITY.ribbon"
        ROUTE -> "vnd.android.cursor.item/vnd.$AUTHORITY.route"
        else -> throw IllegalArgumentException("unknown uri $uri")
    }

    // Agents write knots only through their own car's detector, never through this door.
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    companion object {
        const val AUTHORITY = "kr.tedin.maptalkdrive.ribbons"
        private const val RIBBONS = 1
        private const val RIBBON = 2
        private const val NEXT = 3
        private const val ROUTE = 4
        val COLUMNS = arrayOf(
            "anchor_id", "type", "name", "along_m", "point_m", "distance_m", "status", "deviation", "n", "window_min",
            "top_metric", "gist", "json",
        )
    }
}
