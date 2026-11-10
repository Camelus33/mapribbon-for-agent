package kr.tedin.maptalkdrive

import android.Manifest
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.tedin.maptalkdrive.demo.RibbonViewModel
import kr.tedin.maptalkdrive.ribbon.live.TestInput
import kr.tedin.maptalkdrive.ui.AppScreen
import kr.tedin.maptalkdrive.ui.MaptalkTheme

class MainActivity : ComponentActivity() {
    private val vm: RibbonViewModel by viewModels()
    private val restricted = mutableStateOf(false)
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    // Dangerous-level permissions; the rest are declared in the manifest and granted at install.
    private val runtimePermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add("pleos.car.permission.FUSED_LOCATION")
        add("android.car.permission.CAR_SPEED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.channel.startPorts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaptalkTheme { AppScreen(vm, restricted.value) } }
        watchDrivingRestrictions()
        val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.channel.startPorts() else ask.launch(missing.toTypedArray())
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        testRoute(intent)
        runScriptedDemo(intent)
    }

    /**
     * A navigation route typed in for testing, for when the navigation itself cannot be driven (the emulator's does not
     * answer). It goes down the same path as the navigation's own route and is marked as a test on screen:
     * `adb shell am start -n kr.tedin.maptalkdrive/.MainActivity --es navi_origin 37.914718,128.290595 --ef navi_heading 61
     *  --es navi_dest 38.063314,128.596721 --ei navi_distance_m 34508 --es navi_dest_name %EC%96%91%EC%96%91JC`
     * Without `navi_origin` the route starts where the car is. `navi_via` takes "lat,lon;lat,lon", `navi_option` a
     * route option name (FREE, SHORT_DISTANCE, ...), `navi_dest_name` may be percent-encoded.
     */
    private fun testRoute(intent: Intent) {
        val dest = intent.getStringExtra("navi_dest") ?: return
        val heading = if (intent.hasExtra("navi_heading")) intent.getFloatExtra("navi_heading", Float.NaN).toDouble().takeIf { !it.isNaN() } else null
        val route = TestInput.route(
            origin = intent.getStringExtra("navi_origin"), headingDeg = heading, via = intent.getStringExtra("navi_via"),
            destination = dest, destinationName = intent.getStringExtra("navi_dest_name")?.let { Uri.decode(it) },
            option = intent.getStringExtra("navi_option"),
            naviDistanceM = if (intent.hasExtra("navi_distance_m")) intent.getIntExtra("navi_distance_m", 0) else null,
        )
        if (route == null) Log.w("MainActivity", "test route ignored: navi_dest must be lat,lon") else vm.channel.navi.inject(route)
    }

    /**
     * Repeatable demo for recording and measurement:
     * `adb shell am start -n kr.tedin.maptalkdrive/.MainActivity --es demo_scenario local-weather --ez demo_preview true`
     */
    private fun runScriptedDemo(intent: Intent) {
        val scenario = intent.getStringExtra("demo_scenario") ?: return
        val preview = intent.getBooleanExtra("demo_preview", false) || intent.getIntExtra("demo_drive_speedup", 0) > 0
        lifecycleScope.launch {
            vm.channel.runScenario(scenario)
            vm.channel.state.first { !it.replaying }
            if (preview) {
                delay(3_000) // leave the filled briefing on screen for a moment
                vm.startPreview()
            }
        }
    }

    /**
     * While the car moves AAOS requires a distraction-optimised UI. This app answers by stepping aside: the screen only
     * says it is recording, and this car's agent ties knots until the car is parked again.
     */
    private fun watchDrivingRestrictions() {
        try {
            val c = Car.createCar(this)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = m
            onRestriction(m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true)
            m.registerListener { r -> onRestriction(r.isRequiresDistractionOptimization) }
        } catch (e: Throwable) {
            Log.w("MainActivity", "UX restrictions unavailable", e)
        }
    }

    private fun onRestriction(now: Boolean) {
        val was = restricted.value
        restricted.value = now
        if (now && !was) vm.channel.startRecording()
        if (!now && was) vm.channel.stopRecording()
    }

    override fun onDestroy() {
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }
}
