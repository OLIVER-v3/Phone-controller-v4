package com.ff.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Steering-wheel tilt for racing games. Hold the phone in landscape like a wheel and rotate it.
 * Reports the roll angle in degrees: positive = rotated clockwise (steer right).
 */
class TiltManager(ctx: Context, private val onAngle: (Float) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val smooth = sensor?.type != Sensor.TYPE_GRAVITY   // raw accelerometer needs a low-pass filter
    private var active = false
    private var init = false
    private var fx = 0f
    private var fy = 0f
    private var lastAngle = 0f

    val available: Boolean get() = sensor != null

    fun start() {
        if (!active && sensor != null) {
            init = false
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            active = true
        }
    }

    fun stop() {
        if (active) {
            sm.unregisterListener(this)
            active = false
        }
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        if (!init) { fx = x; fy = y; init = true }
        else if (smooth) { fx += 0.25f * (x - fx); fy += 0.25f * (y - fy) }
        else { fx = x; fy = y }

        // convert to the screen frame (x' = screen right, y' = screen up)
        val rot = wm.defaultDisplay.rotation
        val vx: Float
        val vy: Float
        if (rot == Surface.ROTATION_270) { vx = -fy; vy = fx } else { vx = fy; vy = -fx }

        if (hypot(vx, vy) < 2.5f) { onAngle(lastAngle); return }   // phone lying flat: keep last angle
        val deg = Math.toDegrees(atan2((-vx).toDouble(), vy.toDouble())).toFloat()
        lastAngle = deg
        onAngle(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
