package kr.tedin.maptalkdrive.adapter

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.tedin.maptalkdrive.R
import kr.tedin.maptalkdrive.port.Gear
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.port.VehiclePort
import kr.tedin.maptalkdrive.port.VehicleSignals
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal
import kr.tedin.maptalkdrive.ribbon.spec.WiperLevel

/**
 * Vehicle signals from the Pleos Vehicle SDK, plus speed and outside temperature from AAOS CarPropertyManager (the SDK
 * has neither).
 * Every registration is guarded on its own: a platform without one signal still delivers the others, and a platform
 * without the Pleos service at all leaves the app running.
 *
 * A registration that succeeds proves nothing (the SDK accepts listeners even when its service is down), so the
 * status turns live only when a value has actually arrived.
 */
class PleosVehicleAdapter(context: Context) : VehiclePort {
    private val ctx = context.applicationContext
    private val _signals = MutableStateFlow(VehicleSignals())
    override val signals: StateFlow<VehicleSignals> = _signals
    private val _status = MutableStateFlow(PortStatus("Pleos Vehicle SDK", live = false, note = ctx.getString(R.string.port_not_started)))
    override val status: StateFlow<PortStatus> = _status

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private val failed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() // callbacks arrive on binder threads
    private val heard: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            got("brake"); _signals.update { it.copy(brake = status == true) }
        }
        override fun onFailed(exception: Exception) = fail("brake", exception)
    }
    private val turn = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            got("turn")
            _signals.update {
                it.copy(turnSignal = when (state) { TurnSignalValue.LEFT -> TurnSignal.LEFT; TurnSignalValue.RIGHT -> TurnSignal.RIGHT; else -> TurnSignal.OFF })
            }
        }
        override fun onFailed(exception: Exception) = fail("turn", exception)
    }
    private val wheel = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            got("wheel"); _signals.update { it.copy(wheelAngleDeg = angle?.toDouble()) }
        }
        override fun onFailed(exception: Exception) = fail("wheel", exception)
    }
    private val wiper = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            got("wiper"); _signals.update { it.copy(wiper = state == WiperState.ON) }
        }
        override fun onFailed(exception: Exception) = fail("wiper", exception)
    }
    private val fog = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            got("fog"); _signals.update { it.copy(fogLight = state == LightState.ON) }
        }
        override fun onFailed(exception: Exception) = fail("fog", exception)
    }
    private val lowBeam = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            // Daytime running lights are not low beams: they are on whenever the car runs.
            got("lowBeam"); _signals.update { it.copy(lowBeam = state == LightState.ON) }
        }
        override fun onFailed(exception: Exception) = fail("lowBeam", exception)
    }
    private val rearFog = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            got("rearFog"); _signals.update { it.copy(rearFog = state == LightState.ON) }
        }
        override fun onFailed(exception: Exception) = fail("rearFog", exception)
    }
    private val temp = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val c = (value.value as? Float) ?: return
            got("temp"); _signals.update { it.copy(outsideTempC = c.toDouble()) }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    /** The SDK reports the wiper switch only on request, so it is asked every few seconds. */
    private val handler = Handler(Looper.getMainLooper())
    private val pollWiper = object : Runnable {
        override fun run() {
            val v = vehicle ?: return
            runCatching {
                v.getWiper().getCurrentWiperSwitch(
                    WindowArea(WindowValue.FRONT_WINDSHIELD),
                    { sw -> got("wiperSwitch"); _signals.update { it.copy(wiperLevel = levelOf(sw)) } },
                    { e -> fail("wiperSwitch", e) },
                )
            }.onFailure { fail("wiperSwitch", it as? Exception ?: Exception(it)) }
            handler.postDelayed(this, WIPER_POLL_MS)
        }
    }

    private val gear = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            got("gear")
            _signals.update {
                it.copy(gear = when (gear) {
                    VehicleGear.GEAR_PARK -> Gear.PARK
                    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                    else -> Gear.DRIVE
                })
            }
        }
        override fun onFailed(exception: Exception) = fail("gear", exception)
    }
    private val speed = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            got("speed"); _signals.update { it.copy(speedKmh = mps * 3.6) }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    override fun start() {
        failed.clear()
        heard.clear()
        val v = try {
            Vehicle(ctx).also { it.initialize() }
        } catch (e: Throwable) {
            Log.w(TAG, "Vehicle SDK unavailable", e)
            null
        }
        vehicle = v
        if (v != null) {
            guard("brake") { v.getBrake().registerBrakePedalStatus(brake) }
            guard("turn") { v.getTurnSignal().registerTurnSignalState(turn) }
            guard("wheel") { v.getSteeringWheel().registerSteeringWheelAngle(wheel) }
            guard("wiper") { v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiper) }
            guard("fog") { v.getLight().registerFrontFogLightState(fog) }
            guard("rearFog") { v.getLight().registerRearFogLightState(rearFog) }
            guard("lowBeam") { v.getLight().registerLowBeamLightState(lowBeam) }
            guard("gear") { v.getGear().registerGearSelection(gear) }
            handler.post(pollWiper)
        }
        guard("speed") {
            val c = Car.createCar(ctx)
            car = c
            val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm.registerCallback(speed, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
        guard("temp") {
            val cpm = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm.registerCallback(temp, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
        refreshStatus()
    }

    override fun stop() {
        vehicle?.let { v ->
            runCatching { v.getBrake().unregisterBrakePedalStatus(brake) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turn) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(wheel) }
            runCatching { v.getWiper().unregisterWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiper) }
            runCatching { v.getLight().unregisterFrontFogLightState(fog) }
            runCatching { v.getLight().unregisterRearFogLightState(rearFog) }
            runCatching { v.getLight().unregisterLowBeamLightState(lowBeam) }
            runCatching { v.getGear().unregisterGearSelection(gear) }
            runCatching { v.release() }
        }
        handler.removeCallbacks(pollWiper)
        vehicle = null
        car?.let { c ->
            runCatching { (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).unregisterCallback(speed) }
            runCatching { (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).unregisterCallback(temp) }
            runCatching { c.disconnect() }
        }
        car = null
    }

    private fun got(name: String) {
        if (heard.add(name)) refreshStatus()
    }

    private fun fail(name: String, e: Exception) {
        Log.w(TAG, "$name failed", e)
        if (failed.add(name)) refreshStatus()
    }

    private fun refreshStatus() {
        val missing = failed - heard
        _status.value = PortStatus(
            source = "Pleos Vehicle SDK",
            live = heard.isNotEmpty(),
            note = when {
                vehicle == null && heard.isEmpty() -> ctx.getString(R.string.port_vehicle_missing)
                heard.isEmpty() -> ctx.getString(R.string.port_vehicle_registered)
                else -> ctx.getString(R.string.port_vehicle_signals, heard.size, SIGNALS) + if (missing.isNotEmpty()) " · " + ctx.getString(R.string.port_vehicle_failed, missing.joinToString()) else ""
            },
        )
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w(TAG, "register $name failed", e); failed += name }
    }

    private companion object {
        const val TAG = "PleosVehicle"
        const val WIPER_POLL_MS = 3_000L
        /** brake, turn, wheel, wiper, wiperSwitch, fog, rearFog, lowBeam, gear, speed, temp */
        const val SIGNALS = 11

        /** The switch, coarsened to what says something about rain: mist and auto (speed unknown) are intermittent. */
        fun levelOf(sw: WiperSwitch?): WiperLevel = when (sw) {
            WiperSwitch.CONTINUOUS_LEVEL_1 -> WiperLevel.LOW
            WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.CONTINUOUS_LEVEL_3, WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> WiperLevel.HIGH
            WiperSwitch.MIST, WiperSwitch.AUTO, WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2,
            WiperSwitch.INTERMITTENT_LEVEL_3, WiperSwitch.INTERMITTENT_LEVEL_4, WiperSwitch.INTERMITTENT_LEVEL_5 -> WiperLevel.INTERMITTENT
            else -> WiperLevel.OFF
        }
    }
}
