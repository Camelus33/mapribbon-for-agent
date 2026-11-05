package kr.tedin.maptalkdrive.adapter

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.tedin.maptalkdrive.port.Fix
import kr.tedin.maptalkdrive.port.LocationPort
import kr.tedin.maptalkdrive.port.PortStatus

/**
 * Position from Pleos FusedLocation when the platform offers it, otherwise from Android's GPS provider.
 * FusedLocation is listed as "Not Available Yet" on Pleos Playground, so the fallback is a normal path, not an error.
 */
class PleosLocationAdapter(context: Context) : LocationPort {
    private val ctx = context.applicationContext
    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    override val fixes: SharedFlow<Fix> = _fixes
    private val _status = MutableStateFlow(PortStatus("위치", live = false, note = "시작 전"))
    override val status: StateFlow<PortStatus> = _status

    private var fused: FusedLocation? = null
    private var manager: LocationManager? = null

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            _fixes.tryEmit(Fix(location.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(), location.latitude, location.longitude))
        }
        override fun onError(exception: Exception) {
            Log.w(TAG, "fused location error", exception)
        }
    }

    private val gpsListener = LocationListener { l: Location -> _fixes.tryEmit(Fix(l.time, l.latitude, l.longitude)) }

    @SuppressLint("MissingPermission") // the activity asks for the location permissions before starting the ports
    override fun start() {
        val f = try {
            FusedLocation(ctx).takeIf { it.isSupported() }?.also { it.initialize(); it.registerFusedLocationCallback(fusedListener) }
        } catch (e: Throwable) {
            Log.w(TAG, "FusedLocation unavailable", e)
            null
        }
        fused = f
        if (f != null) {
            _status.value = PortStatus("Pleos FusedLocation", live = true)
            return
        }
        try {
            val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            manager = m
            _status.value = PortStatus("Android GPS", live = true, note = "FusedLocation 없음")
        } catch (e: Throwable) {
            Log.w(TAG, "GPS unavailable", e)
            _status.value = PortStatus("위치", live = false, note = "위치 권한 또는 GPS 없음")
        }
    }

    override fun stop() {
        fused?.let { f -> runCatching { f.unregisterFusedLocationCallback(fusedListener) }; runCatching { f.release() } }
        fused = null
        manager?.let { m -> runCatching { m.removeUpdates(gpsListener) } }
        manager = null
    }

    private companion object { const val TAG = "PleosLocation" }
}
