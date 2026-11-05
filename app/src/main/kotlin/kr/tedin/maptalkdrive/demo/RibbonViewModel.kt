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

/**
 * The screen's view of the channel plus the one thing only the screen has: the preview run, a dot that runs the
 * route before departure. The preview reads ribbons and writes nothing; knots come only from driving.
 */
class RibbonViewModel(app: Application) : AndroidViewModel(app) {
    val channel = RibbonChannel.get(app)
    val data get() = channel.data

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
        val length = data.route.lengthM
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
}
