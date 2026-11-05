package kr.tedin.maptalkdrive.channel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kr.tedin.maptalkdrive.R

/**
 * Keeps the process alive while this car's agent ties knots, so recording continues with the navigation in front.
 * It draws nothing but its notification; the knots are tied by [RibbonChannel]. If the platform refuses a
 * foreground service (no location permission, started from the background), recording continues in the process
 * for as long as Android keeps it.
 */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.recording))
            .setContentText(getString(R.string.recording_notice))
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "foreground service refused; recording continues in-process", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "RecordService"
        private const val CHANNEL = "recording"
        private const val ID = 1

        fun start(ctx: Context) {
            // A location-type foreground service needs the location permission at start; without it Android would
            // kill the app for not calling startForeground in time, so it is not started at all.
            val located = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (!located) { Log.i(TAG, "no location permission: recording in-process only"); return }
            try { ctx.startForegroundService(Intent(ctx, RecordService::class.java)) } catch (e: Exception) { Log.w(TAG, "start refused", e) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordService::class.java))
        }
    }
}
