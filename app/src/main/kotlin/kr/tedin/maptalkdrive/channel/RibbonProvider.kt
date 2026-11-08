package kr.tedin.maptalkdrive.channel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kr.tedin.maptalkdrive.ribbon.brief.Briefing
import kr.tedin.maptalkdrive.ribbon.brief.BriefingItem
import kr.tedin.maptalkdrive.ribbon.brief.Caution
import kr.tedin.maptalkdrive.ribbon.brief.Cautions
import kr.tedin.maptalkdrive.ribbon.spec.AnchorRef
import kr.tedin.maptalkdrive.ribbon.spec.RibbonJson
import kr.tedin.maptalkdrive.ribbon.spec.RibbonState
import kr.tedin.maptalkdrive.ribbon.spec.RouteBrief

/**
 * Where other agents in the car read the ribbon channel. Read-only; every row carries the `maptalk.ribbon/1` (or
 * `maptalk.route/1`) JSON an agent can parse, next to flat columns for a quick look.
 *
 * - `content://kr.tedin.maptalkdrive.ribbons/ribbons`              every facility and segment on the route, in driving order
 * - `content://kr.tedin.maptalkdrive.ribbons/ribbons/<anchorId>`   one facility or segment
 * - `content://kr.tedin.maptalkdrive.ribbons/next?along_m=12000`   the next facility or segment ahead that is different from usual
 * - `content://kr.tedin.maptalkdrive.ribbons/route`                how the route bends (with its hairpins), and its facilities
 * - `content://kr.tedin.maptalkdrive.ribbons/caution`              where to take care, at most three, ranked (`maptalk.caution/1`)
 *
 * Each answers for the route on screen; add `?route=<routeId>` (SY60E, N44E, J1100S, J1135S) for another.
 *
 * Ribbons are aggregates of at least three cars with no identities in them, so the provider needs no permission.
 */
class RibbonProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "ribbons", RIBBONS)
        addURI(AUTHORITY, "ribbons/*", RIBBON)
        addURI(AUTHORITY, "next", NEXT)
        addURI(AUTHORITY, "route", ROUTE)
        addURI(AUTHORITY, "caution", CAUTION)
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val channel = RibbonChannel.get(context!!)
        // The route on screen by default; `?route=N44E` reads another one.
        val r = uri.getQueryParameter("route")?.takeIf { it.isNotBlank() }?.let { id -> channel.data.routes.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown route $id") }
            ?: channel.current
        val anchors = r.anchorSet.anchors
        val ribbons = channel.ribbons(r.id)
        return when (matcher.match(uri)) {
            RIBBONS -> ribbonCursor(Briefing.build(anchors, ribbons).items.map { it to null })
            RIBBON -> {
                val id = uri.lastPathSegment
                ribbonCursor(Briefing.build(anchors, ribbons).items.filter { it.anchor.id == id }.map { it to null })
            }
            NEXT -> {
                val along = uri.getQueryParameter("along_m")?.toDoubleOrNull() ?: 0.0
                ribbonCursor(listOfNotNull(Briefing.build(anchors, ribbons).nextDifferentAhead(along)))
            }
            ROUTE -> {
                val set = r.anchorSet
                val brief = RouteBrief(
                    routeId = set.routeId, routeName = set.routeName, lengthM = set.lengthM, attribution = set.attribution,
                    curves = r.curves, hairpins = r.hairpins,
                    anchors = set.anchors.sortedBy { it.alongM }.map { AnchorRef(it.id, it.type, it.dir, it.name, it.virtual) },
                )
                MatrixCursor(arrayOf("route_id", "name", "length_m", "json")).apply {
                    addRow(arrayOf<Any?>(set.routeId, set.routeName, set.lengthM, RibbonJson.encodeToString(RouteBrief.serializer(), brief)))
                }
            }
            CAUTION -> {
                val now = System.currentTimeMillis()
                val cautions = Cautions.build(Briefing.build(anchors, channel.ribbons(r.id, now)), r.curves, r.hairpins, now)
                MatrixCursor(CAUTION_COLUMNS).apply {
                    for (c in cautions) addRow(arrayOf<Any?>(
                        c.rank, c.reading.name, c.fromM, c.toM, c.pointM, c.metric, c.deviation.name, c.cars, c.n,
                        c.hairpins, if (c.onSharpCurves) 1 else 0, if (c.tunnelExit) 1 else 0, if (c.sudden) 1 else 0,
                        c.actions.joinToString(",") { it.name }, c.lastMs, c.score, RibbonJson.encodeToString(Caution.serializer(), c),
                    ))
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
                    distance?.toInt(), s.status.name, s.deviation.name, s.n, s.window?.minutes, s.window?.lastMs,
                    s.reasons.firstOrNull()?.metric, s.gist, RibbonJson.encodeToString(RibbonState.serializer(), s),
                ))
            }
        }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        RIBBONS, NEXT -> "vnd.android.cursor.dir/vnd.$AUTHORITY.ribbon"
        CAUTION -> "vnd.android.cursor.dir/vnd.$AUTHORITY.caution"
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
        private const val CAUTION = 5
        val COLUMNS = arrayOf(
            "anchor_id", "type", "name", "along_m", "point_m", "distance_m", "status", "deviation", "n", "window_min",
            "last_ms", "top_metric", "gist", "json",
        )
        val CAUTION_COLUMNS = arrayOf(
            "rank", "reading", "from_m", "to_m", "point_m", "metric", "deviation", "cars", "n",
            "hairpins", "on_sharp_curves", "tunnel_exit", "sudden", "actions", "last_ms", "score", "json",
        )
    }
}
