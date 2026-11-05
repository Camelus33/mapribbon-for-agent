package kr.tedin.maptalkdrive

import android.Manifest
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
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
import kr.tedin.maptalkdrive.ui.AppScreen
import kr.tedin.maptalkdrive.ui.MaptalkTheme

class MainActivity : ComponentActivity() {
    private val vm: RibbonViewModel by viewModels()
    private val restricted = mutableStateOf(false)
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    // Dangerous-level permissions; the rest are declared in the manifest and granted at install.
    private val runtimePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        "pleos.car.permission.FUSED_LOCATION",
        "android.car.permission.CAR_SPEED",
    )

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.startPorts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaptalkTheme { AppScreen(vm, restricted.value) } }
        watchDrivingRestrictions()
        val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.startPorts() else ask.launch(missing.toTypedArray())
        if (savedInstanceState == null) runScriptedDemo()
    }

    /**
     * Repeatable demo for recording and measurement:
     * `adb shell am start -n kr.tedin.maptalkdrive/.MainActivity --es demo_scenario local-weather --ei demo_drive_speedup 20`
     */
    private fun runScriptedDemo() {
        val scenario = intent.getStringExtra("demo_scenario") ?: return
        val speedup = intent.getIntExtra("demo_drive_speedup", 0)
        lifecycleScope.launch {
            vm.runScenario(scenario)
            vm.ui.first { !it.replaying }
            if (speedup > 0) {
                delay(3_000) // leave the filled briefing on screen for a moment
                vm.startDemoDrive(speedup)
            }
        }
    }

    /** While the car is moving, AAOS requires distraction-optimised UI: the app switches to the one-line view. */
    private fun watchDrivingRestrictions() {
        try {
            val c = Car.createCar(this)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = m
            restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> restricted.value = r.isRequiresDistractionOptimization }
        } catch (e: Throwable) {
            Log.w("MainActivity", "UX restrictions unavailable", e)
        }
    }

    override fun onDestroy() {
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }
}
