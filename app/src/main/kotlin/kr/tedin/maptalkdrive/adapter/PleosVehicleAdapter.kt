package kr.tedin.maptalkdrive.adapter

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
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
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.tedin.maptalkdrive.port.Gear
import kr.tedin.maptalkdrive.port.PortStatus
import kr.tedin.maptalkdrive.port.VehiclePort
import kr.tedin.maptalkdrive.port.VehicleSignals
import kr.tedin.maptalkdrive.ribbon.detect.TurnSignal

/**
 * Vehicle signals from the Pleos Vehicle SDK, plus speed from AAOS CarPropertyManager (the SDK has no speed).
 * Every registration is guarded on its own: a platform without one signal still delivers the others, and a platform
 * without the Pleos service at all leaves the app running with `live = false`.
 */
class PleosVehicleAdapter(context: Context) : VehiclePort {
    private val ctx = context.applicationContext
    private val _signals = MutableStateFlow(VehicleSignals())
    override val signals: StateFlow<VehicleSignals> = _signals
    private val _status = MutableStateFlow(PortStatus("Pleos Vehicle SDK", live = false, note = "시작 전"))
    override val status: StateFlow<PortStatus> = _status

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private val failed = mutableListOf<String>()

    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = _signals.update { it.copy(brake = status == true) }
        override fun onFailed(exception: Exception) { Log.w(TAG, "brake failed", exception) }
    }
    private val turn = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = _signals.update {
            it.copy(turnSignal = when (state) { TurnSignalValue.LEFT -> TurnSignal.LEFT; TurnSignalValue.RIGHT -> TurnSignal.RIGHT; else -> TurnSignal.OFF })
        }
        override fun onFailed(exception: Exception) { Log.w(TAG, "turn failed", exception) }
    }
    private val wheel = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = _signals.update { it.copy(wheelAngleDeg = angle?.toDouble()) }
        override fun onFailed(exception: Exception) { Log.w(TAG, "wheel failed", exception) }
    }
    private val wiper = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) = _signals.update { it.copy(wiper = state == WiperState.ON) }
        override fun onFailed(exception: Exception) { Log.w(TAG, "wiper failed", exception) }
    }
    private val fog = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) = _signals.update { it.copy(fogLight = state == LightState.ON) }
        override fun onFailed(exception: Exception) { Log.w(TAG, "fog failed", exception) }
    }
    private val gear = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = _signals.update {
            it.copy(gear = when (gear) {
                VehicleGear.GEAR_PARK -> Gear.PARK
                VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                else -> Gear.DRIVE
            })
        }
        override fun onFailed(exception: Exception) { Log.w(TAG, "gear failed", exception) }
    }
    private val speed = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            _signals.update { it.copy(speedKmh = mps * 3.6) }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    override fun start() {
        failed.clear()
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
            guard("gear") { v.getGear().registerGearSelection(gear) }
        }
        guard("speed") {
            val c = Car.createCar(ctx)
            car = c
            val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm.registerCallback(speed, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
        _status.value = PortStatus(
            source = "Pleos Vehicle SDK",
            live = v != null,
            note = when {
                v == null -> "Vehicle 서비스 없음"
                failed.isEmpty() -> "신호 6종 + 속도 수신 중"
                else -> "수신 실패: ${failed.joinToString()}"
            },
        )
    }

    override fun stop() {
        vehicle?.let { v ->
            runCatching { v.getBrake().unregisterBrakePedalStatus(brake) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turn) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(wheel) }
            runCatching { v.getWiper().unregisterWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiper) }
            runCatching { v.getLight().unregisterFrontFogLightState(fog) }
            runCatching { v.getGear().unregisterGearSelection(gear) }
            runCatching { v.release() }
        }
        vehicle = null
        car?.let { c ->
            runCatching { (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).unregisterCallback(speed) }
            runCatching { c.disconnect() }
        }
        car = null
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w(TAG, "register $name failed", e); failed += name }
    }

    private companion object { const val TAG = "PleosVehicle" }
}
