package com.ff.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

/**
 * Gyro aiming. Reports angular velocity (rad/s) in the SCREEN frame:
 *  yaw   = rotation about the screen's up axis
 *  pitch = rotation about the screen's right axis
 */
class GyroManager(ctx: Context, private val onRate: (yaw: Float, pitch: Float) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var active = false

    val available: Boolean get() = sensor != null

    fun start() {
        if (!active && sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            active = true
        }
    }

    fun stop() {
        if (active) {
            sm.unregisterListener(this)
            active = false
            onRate(0f, 0f)
        }
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]
        val gy = e.values[1]
        val rot = wm.defaultDisplay.rotation
        val pitch: Float
        val yaw: Float
        if (rot == Surface.ROTATION_270) { pitch = -gy; yaw = gx } else { pitch = gy; yaw = -gx }
        onRate(yaw, pitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
