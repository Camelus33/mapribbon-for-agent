package kr.tedin.maptalkdrive.adapter

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.port.Fix
import kr.tedin.maptalkdrive.port.LocationPort
import kr.tedin.maptalkdrive.port.PortStatus

/**
 * Position from Pleos FusedLocation when the platform offers it, otherwise from Android's GPS provider.
 * FusedLocation is listed as "Not Available Yet" on Pleos Playground, so the fallback is a normal path: if no fused
 * fix arrives within [FUSED_GRACE_MS], the adapter switches to GPS. The status is live only after a real fix.
 */
class PleosLocationAdapter(context: Context) : LocationPort {
    private val ctx = context.applicationContext
    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    override val fixes: SharedFlow<Fix> = _fixes
    private val _status = MutableStateFlow(PortStatus(ctx.getString(R.string.port_location_name), live = false, note = ctx.getString(R.string.port_not_started)))
    override val status: StateFlow<PortStatus> = _status

    private val main = Handler(Looper.getMainLooper())
    private var fused: FusedLocation? = null
    private var manager: LocationManager? = null
    @Volatile private var source = ctx.getString(R.string.port_location_name)
    @Volatile private var gotFix = false

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            emit(Fix(location.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(), location.latitude, location.longitude))
        }
        override fun onError(exception: Exception) {
            Log.w(TAG, "fused location error", exception)
        }
    }

    private val gpsListener = LocationListener { l: Location -> emit(Fix(l.time, l.latitude, l.longitude)) }

    private val fallBackIfSilent = Runnable {
        if (!gotFix && fused != null) {
            Log.i(TAG, "no fused fix in ${FUSED_GRACE_MS} ms, switching to GPS")
            stopFused()
            startGps(ctx.getString(R.string.port_fused_silent))
        }
    }

    override fun start() {
        gotFix = false
        val f = try {
            FusedLocation(ctx).takeIf { it.isSupported() }?.also { it.initialize(); it.registerFusedLocationCallback(fusedListener) }
        } catch (e: Throwable) {
            Log.w(TAG, "FusedLocation unavailable", e)
            null
        }
        fused = f
        if (f != null) {
            source = "Pleos FusedLocation"
            _status.value = PortStatus(source, live = false, note = ctx.getString(R.string.port_registered_waiting_fix))
            main.postDelayed(fallBackIfSilent, FUSED_GRACE_MS)
        } else {
            startGps(ctx.getString(R.string.port_fused_missing))
        }
    }

    @SuppressLint("MissingPermission") // the activity asks for the location permissions before starting the ports
    private fun startGps(why: String) {
        try {
            val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            manager = m
            source = "Android GPS"
            _status.value = PortStatus(source, live = false, note = ctx.getString(R.string.port_waiting_fix, why))
        } catch (e: Throwable) {
            Log.w(TAG, "GPS unavailable", e)
            _status.value = PortStatus(ctx.getString(R.string.port_location_name), live = false, note = ctx.getString(R.string.port_no_gps, why))
        }
    }

    private fun emit(fix: Fix) {
        if (!gotFix) {
            gotFix = true
            _status.value = PortStatus(source, live = true, note = ctx.getString(R.string.port_receiving_fixes))
        }
        _fixes.tryEmit(fix)
    }

    private fun stopFused() {
        fused?.let { f -> runCatching { f.unregisterFusedLocationCallback(fusedListener) }; runCatching { f.release() } }
        fused = null
    }

    override fun stop() {
        main.removeCallbacks(fallBackIfSilent)
        stopFused()
        manager?.let { m -> runCatching { m.removeUpdates(gpsListener) } }
        manager = null
    }

    private companion object {
        const val TAG = "PleosLocation"
        const val FUSED_GRACE_MS = 10_000L
    }
}
