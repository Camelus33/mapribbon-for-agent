package kr.tedin.maptalkdrive.ribbon.route

import kr.tedin.maptalkdrive.ribbon.spec.MatchVerdict
import kr.tedin.maptalkdrive.ribbon.spec.RouteMatch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Is the line drawn here the navigation's route? The navigation gives its own length only, so length is the test:
 * within the larger of 1 km and 3 % it is taken as the same road.
 */
object RouteMatching {
    fun toleranceM(naviM: Int): Int = max(1_000, (naviM * 0.03).roundToInt())

    /** Index of the candidate to use: the first one if it matches, otherwise the one closest in length. */
    fun pick(lengthsM: List<Double>, naviM: Int?): Int {
        require(lengthsM.isNotEmpty())
        if (naviM == null || lengthsM.size == 1) return 0
        if (abs(lengthsM[0] - naviM) <= toleranceM(naviM)) return 0
        return lengthsM.indices.minBy { abs(lengthsM[it] - naviM) }
    }

    fun match(lengthM: Int, naviM: Int?, alternativeIndex: Int = 0, alternatives: Int = 1): RouteMatch {
        if (naviM == null || naviM <= 0) {
            return RouteMatch(lengthM, verdict = MatchVerdict.UNKNOWN, alternativeIndex = alternativeIndex, alternatives = alternatives)
        }
        val diff = lengthM - naviM
        val tol = toleranceM(naviM)
        return RouteMatch(
            lengthM = lengthM, naviDistanceM = naviM, diffM = diff, toleranceM = tol,
            verdict = if (abs(diff) <= tol) MatchVerdict.SAME else MatchVerdict.DIFFERENT,
            alternativeIndex = alternativeIndex, alternatives = alternatives,
        )
    }
}
