package com.ff.controller

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibrates the phone when the PC game rumbles the virtual Xbox pad. */
class RumbleManager(ctx: Context) {
    var enabled = true

    @Suppress("DEPRECATION")
    private val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    }

    @Suppress("DEPRECATION")
    fun set(large: Float, small: Float) {
        val v = vib ?: return
        val s = maxOf(large, small)
        if (!enabled || s <= 0.02f) { v.cancel(); return }
        if (Build.VERSION.SDK_INT >= 26) {
            val amp = if (v.hasAmplitudeControl()) (s * 255).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(60_000L, amp)) // stops when PC sends 0 / link drops
        } else {
            v.vibrate(60_000L)
        }
    }

    fun stop() { vib?.cancel() }
}
