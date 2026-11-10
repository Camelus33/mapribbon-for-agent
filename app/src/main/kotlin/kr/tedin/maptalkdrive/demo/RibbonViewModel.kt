package kr.tedin.maptalkdrive.demo

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kr.tedin.maptalkdrive.channel.RibbonChannel
import kr.tedin.maptalkdrive.ribbon.brief.Cautions
import kr.tedin.maptalkdrive.ribbon.brief.Weave
import kr.tedin.maptalkdrive.ribbon.brief.WeavePlan

/** One moment of the replay of how the ribbons on the route came to be ("knots become a ribbon"), `t` seconds in. */
data class WeaveFrame(val plan: WeavePlan, val t: Double)

/**
 * The screen's view of the channel plus what only the screen has: the replay of how the ribbons tied, and the preview
 * run, a dot that runs the route before departure. Both read ribbons and write nothing; knots come only from driving.
 */
class RibbonViewModel(app: Application) : AndroidViewModel(app) {
    val channel = RibbonChannel.get(app)
    val data get() = channel.data
    /** The route on screen. */
    val current get() = channel.current

    private val _preview = MutableStateFlow<Double?>(null)
    /** Position of the preview run along the route in metres, or null when no preview is running. */
    val preview: StateFlow<Double?> = _preview
    private var previewJob: Job? = null

    /**
     * Runs the route in about `runMs`, pausing `dwellMs` at every place that is different from usual so its card can
     * be read. Ends back on the overview.
     */
    fun startPreview(runMs: Long = 24_000, dwellMs: Long = 1_800) {
        stopPreview()
        stopWeave()
        val length = current.route.lengthM
        previewJob = viewModelScope.launch {
            val stops = channel.state.value.briefing.items.filter { it.different }.map { it.pointM.toDouble() }.sorted()
            var along = 0.0
            var last = SystemClock.uptimeMillis()
            var nextStop = 0
            while (along < length) {
                delay(50) // 20 frames a second is smooth enough for one dot on a parked screen
                val now = SystemClock.uptimeMillis()
                along = (along + length * (now - last) / runMs).coerceAtMost(length)
                last = now
                if (nextStop < stops.size && along >= stops[nextStop]) {
                    // Stop just short of the place, so the card still shows it as the next one ahead.
                    along = stops[nextStop] - 1.0
                    _preview.value = along
                    nextStop++
                    delay(dwellMs)
                    last = SystemClock.uptimeMillis()
                    continue
                }
                _preview.value = along
            }
            delay(600)
            _preview.value = null
        }
    }

    fun stopPreview() {
        previewJob?.cancel(); previewJob = null
        _preview.value = null
    }

    private val _weave = MutableStateFlow<WeaveFrame?>(null)
    /** The replay on screen, or null. It runs to [WeavePlan.endS] and then holds its last frame until closed. */
    val weave: StateFlow<WeaveFrame?> = _weave
    private var weaveJob: Job? = null

    /** The replay of the ribbons on the route on screen now, as the screen plans it. */
    fun planWeave(): WeavePlan {
        val s = channel.state.value
        val r = channel.routeData(s.routeId)
        val cautions = Cautions.build(s.briefing, r.curves, r.hairpins, System.currentTimeMillis())
        return Weave.plan(s.briefing, cautions, r.route.lengthM, r.elevation?.highestAtM)
    }

    fun startWeave(plan: WeavePlan) {
        stopPreview()
        stopWeave()
        weaveJob = viewModelScope.launch {
            val t0 = SystemClock.uptimeMillis()
            while (true) {
                val t = (SystemClock.uptimeMillis() - t0) / 1000.0
                _weave.value = WeaveFrame(plan, minOf(t, plan.endS))
                if (t >= plan.endS) break
                delay(32) // 30 frames a second: knots fall and cars run smoothly at half the work of 60
            }
        }
    }

    fun stopWeave() {
        weaveJob?.cancel(); weaveJob = null
        _weave.value = null
    }

    /** A replay of other ribbons (another route, or counts that changed since) no longer says what the screen shows. */
    fun dropWeaveUnless(plan: WeavePlan) {
        val on = _weave.value?.plan ?: return
        if (on.places != plan.places || on.metric != plan.metric) stopWeave()
    }
}
