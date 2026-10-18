package com.wrth.esp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

object PhoneSensorManager : SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var linearAccel: Sensor? = null
    private var isTracking = false

    fun start(context: Context) {
        if (isTracking) return

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        linearAccel?.let {
            // SENSOR_DELAY_GAME gives high-refresh rate telemetry without draining battery
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            isTracking = true
        }
    }

    fun stop() {
        if (!isTracking) return
        sensorManager?.unregisterListener(this)
        isTracking = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION && BleManager.currentEspPage == 5) {

            // grab raw physical hardware forces
            val hardwareX = event.values[0]
            val hardwareY = event.values[1]
            val hardwareZ = event.values[2]

            // lateral force for turning
            val lateralForce = hardwareX

            // longitudinal force for accel and brake
            val longitudinalForce = -hardwareZ

            // convert forces to G
            val rawX = lateralForce / 9.81f
            val rawZ = longitudinalForce / 9.81f

            BleManager.sendGForcePacket(rawX, rawZ)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}