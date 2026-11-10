package kr.tedin.maptalkdrive.ribbon.brief

import kr.tedin.maptalkdrive.ribbon.agg.EngineConfig
import kr.tedin.maptalkdrive.ribbon.spec.AnchorType
import kr.tedin.maptalkdrive.ribbon.spec.Deviation
import kr.tedin.maptalkdrive.ribbon.spec.Metric
import kr.tedin.maptalkdrive.ribbon.spec.RibbonStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One place the replay drops knots on: how many cars passed it (`n`) and how many of them did the story's thing there
 * (`hits`), as the ribbon counts them; `deviation` is how its ribbon ties at the end (NONE: it does not).
 */
data class WeavePlace(
    val anchorId: String, val type: AnchorType, val alongM: Int, val endAlongM: Int, val pointM: Int,
    val n: Int, val hits: Int, val deviation: Deviation,
)

/** How the replay ends at the focus, in the engine's own terms. */
enum class WeaveOutcome {
    /** Above its usual value and its neighbours: a ribbon ties. */
    TIED,
    /** Fewer than three distinct cars passed it: nothing is judged or published. */
    TOO_FEW,
    /** Its neighbours had as much: weather on the whole road is no danger of one place. */
    LIKE_NEIGHBOURS,
    /** It is usually like this. */
    LIKE_USUAL,
    /** Above both, but by less than the engine's smallest step. */
    SMALL,
    /** No car did anything that tells a story. */
    NOTHING,
}

/**
 * "Knots become a ribbon": a replay of how the ribbons on a route came to be, for the screen before departure. The cars
 * that passed drive the route again one after another, each dropping a knot on every place it passed; a knot is
 * coloured where the car did the story's thing (the first caution's metric, e.g. wipers near 0 °C). At one place, the
 * focus, a counter shows the knots as they come; once three distinct cars passed, the place can be judged and published.
 * Then the verdict: the focus against its neighbours and its usual value; where it is above both, the ribbon ties.
 *
 * Only counts from the ribbons are used, never a car's own track: which car drops which coloured knot is drawn, the
 * number of them is the ribbon's. All timing is a function of time alone, so the screen can draw any moment.
 */
data class WeavePlan(
    /** What the coloured knots mean; null when nothing on the route stands for a story. */
    val metric: String?,
    val cars: Int,
    val lengthM: Double,
    val places: List<WeavePlace>,
    val focus: WeavePlace?,
    /** Places whose ribbon ties at the end: different from usual on the story's metric. */
    val tiedIds: Set<String>,
    /** The focus, its neighbours and its usual value on the story's metric, as rates 0..1 (null: unknown). */
    val focusRate: Double?,
    val neighbourRate: Double?,
    val usualRate: Double?,
    val outcome: WeaveOutcome,
    /** Distinct cars a place needs before it is judged. */
    val minCars: Int = EngineConfig().minVehicles,
) {
    val tied: Boolean get() = outcome == WeaveOutcome.TIED
    /** The fewest cars that passed a place any car passed; [cars] is the most. They differ when windows or roads do. */
    val fewestCars: Int get() = places.filter { it.n > 0 }.minOfOrNull { it.n } ?: 0
    val focusIndex: Int get() = places.indexOfFirst { it.anchorId == focus?.anchorId }

    // ---- timing, in seconds from the start --------------------------------------------------------------------

    /** Seconds between two cars setting off; the cars all start within about six seconds. */
    val spacingS: Double get() = if (cars <= 1) 0.0 else minOf(0.75, 6.0 / (cars - 1))
    val driveS: Double get() = 3.4
    /** The last car has arrived: the verdict starts. */
    val evalS: Double get() = (cars - 1).coerceAtLeast(0) * spacingS + driveS + 0.4
    /** The comparison bars fill. */
    val barsS: Double get() = 0.8
    /** The ribbon draws itself along its stretch. */
    val drawS: Double get() = 0.8
    /** The ribbon is drawn: the map shows the ribbons as they are. */
    val revealS: Double get() = evalS + barsS + drawS
    val endS: Double get() = revealS + 1.6

    /** Where car `car` is at time `t`, in metres along the route; null before it sets off or after it arrives. */
    fun carAlong(car: Int, t: Double): Double? {
        val k = (t - car * spacingS) / driveS * lengthM
        return if (k < 0 || k > lengthM) null else k
    }

    /** Whether car `car` passed place `i` at all (a ribbon of n cars has n knots). */
    fun passes(i: Int, car: Int): Boolean = rank(i, car) < places[i].n

    /** Whether car `car`'s knot at place `i` is coloured. */
    fun colored(i: Int, car: Int): Boolean = rank(i, car) < places[i].hits

    /** When car `car` drops its knot at place `i`. */
    fun dropS(i: Int, car: Int): Double = car * spacingS + places[i].pointM / lengthM * driveS

    /** Knots at place `i` by time `t`. */
    fun passedBy(i: Int, t: Double): Int = (0 until cars).count { passes(i, it) && dropS(i, it) <= t }

    /** Coloured knots at place `i` by time `t`. */
    fun hitsBy(i: Int, t: Double): Int = (0 until cars).count { passes(i, it) && colored(i, it) && dropS(i, it) <= t }

    /** A fixed shuffle of the cars per place, so the same car is not always the one that did it. */
    private fun rank(i: Int, car: Int): Int {
        val id = places[i].anchorId
        return (0 until cars).sortedBy { (id.hashCode() * 31 + it * 7919).let { h -> h xor (h ushr 13) } }.indexOf(car)
    }
}

object Weave {
    private val cfg = EngineConfig()

    /** Metrics that tell no story of their own in a replay: speeds and the temperature context. */
    private val NOT_A_STORY = setOf(Metric.OUTSIDE_TEMP_C, Metric.ENTRY_DECEL_KMH, Metric.SPEED_AT_KMH)

    /** A share of cars, which knots can show; a speed cannot be one car's knot. */
    private fun isStory(metric: String) = metric !in NOT_A_STORY && !metric.endsWith("Kmh")

    /**
     * The replay of `b` on a route of `lengthM` metres. The story is the first caution's metric that is a share of cars;
     * without one, the metric most cars did on the segments (the control: the same thing everywhere, so no ribbon). The
     * focus is that caution's place, or else the place where most cars did it, nearest `highestAtM` (the top of the
     * climb) among equals.
     */
    fun plan(b: Briefing, cautions: List<Caution>, lengthM: Double, highestAtM: Double? = null): WeavePlan {
        val segments = b.items.filter { it.anchor.type == AnchorType.SEGMENT }
        val headline = cautions.firstOrNull { isStory(it.metric) }
        val metric = headline?.metric ?: storyMetric(segments)
        val lead = headline?.let { c -> b.items.firstOrNull { it.anchor.id == c.leadId } }
        val tied = if (metric == null) emptySet() else b.items
            .filter { it.different && it.state.reasons.any { r -> r.metric == metric } }
            .map { it.anchor.id }.toSet()
        val onPlaces = (segments + listOfNotNull(lead)).distinctBy { it.anchor.id }.sortedBy { it.pointM }
        val places = onPlaces.map { item ->
            val n = item.state.n
            val rate = metric?.let { item.state.metrics[it] } ?: 0.0
            WeavePlace(
                item.anchor.id, item.anchor.type, item.anchor.alongM, item.anchor.endAlongM, item.pointM,
                n, (rate * n).roundToInt().coerceIn(0, n), if (item.anchor.id in tied) item.state.deviation else Deviation.NONE,
            )
        }
        val focus = lead?.let { l -> places.firstOrNull { it.anchorId == l.anchor.id } }
            ?: places.filter { it.hits > 0 }.maxWithOrNull(
                compareBy<WeavePlace> { it.hits.toDouble() / maxOf(1, it.n) }.thenBy { -abs(it.pointM - (highestAtM ?: 0.0)) },
            )
        val focusItem = focus?.let { f -> b.items.first { it.anchor.id == f.anchorId } }
        val reason = focusItem?.state?.reasons?.firstOrNull { it.metric == metric }
        val focusRate = metric?.let { focusItem?.state?.metrics?.get(it) }
        // The engine's own comparison when it made one; otherwise the same one made here: the median over the published
        // places of the same kind (segments with segments) within its neighbour radius.
        val neighbours = reason?.neighbors ?: if (metric == null || focusItem == null) null else {
            val segment = focusItem.anchor.type == AnchorType.SEGMENT
            median(
                b.items.filter {
                    it !== focusItem && it.state.status == RibbonStatus.PUBLISHED &&
                        (it.anchor.type == AnchorType.SEGMENT) == segment &&
                        abs(it.anchor.alongM - focusItem.anchor.alongM) <= cfg.neighborRadiusM
                }.mapNotNull { it.state.metrics[metric] },
            )
        }
        val usual = reason?.baseline ?: metric?.let { focusItem?.state?.baseline?.get(it) }
        val outcome = when {
            metric == null || focus == null || focusRate == null -> WeaveOutcome.NOTHING
            focus.anchorId in tied -> WeaveOutcome.TIED
            focus.n < cfg.minVehicles -> WeaveOutcome.TOO_FEW
            neighbours != null && focusRate - neighbours < cfg.rateSome -> WeaveOutcome.LIKE_NEIGHBOURS
            usual != null && focusRate - usual < cfg.rateSome -> WeaveOutcome.LIKE_USUAL
            else -> WeaveOutcome.SMALL
        }
        return WeavePlan(
            metric = metric, cars = places.maxOfOrNull { it.n } ?: 0, lengthM = lengthM, places = places, focus = focus,
            tiedIds = tied, focusRate = focusRate, neighbourRate = neighbours, usualRate = usual, outcome = outcome,
            minCars = cfg.minVehicles,
        )
    }

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * Among equally common signals the more specific one tells the story: wet road near freezing says more than wipers,
     * which every rain shows. Unlisted metrics come last.
     */
    private val SPECIFIC_FIRST = listOf(
        Metric.ICE_RISK_RATE, Metric.WIPER_FAST_RATE, Metric.REAR_FOG_RATE, Metric.FOG_ON_RATE, Metric.FOG_TURNED_ON_RATE,
        Metric.DAY_LOW_BEAM_RATE, Metric.STOP_RATE, Metric.HARD_BRAKE_RATE, Metric.LATE_LANE_CHANGE_RATE,
        Metric.WIPER_TURNED_ON_RATE, Metric.WIPER_ON_RATE,
    )

    private fun storyMetric(segments: List<BriefingItem>): String? = segments
        .filter { it.state.status == RibbonStatus.PUBLISHED }
        .flatMap { it.state.metrics.entries }
        .filter { isStory(it.key) }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, v) -> v.average() }
        .filter { it.value > 0.0 }
        .entries
        .maxWithOrNull(
            compareBy<Map.Entry<String, Double>> { (it.value * 10).roundToInt() }
                .thenBy { e -> SPECIFIC_FIRST.indexOf(e.key).let { if (it < 0) -SPECIFIC_FIRST.size else -it } },
        )?.key
}
