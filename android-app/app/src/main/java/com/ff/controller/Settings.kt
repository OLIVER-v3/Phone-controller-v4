package com.ff.controller

import android.content.Context
import android.content.SharedPreferences

/** Persistent app settings. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var mode: String
        get() = p.getString("mode", "pad") ?: "pad"
        set(v) { p.edit().putString("mode", v).apply() }
    var theme: String
        get() = p.getString("theme", "auto") ?: "auto"
        set(v) { p.edit().putString("theme", v).apply() }
    var mouse: Boolean
        get() = p.getBoolean("mouse", false)
        set(v) { p.edit().putBoolean("mouse", v).apply() }
    var gyro: Boolean
        get() = p.getBoolean("gyro", false)
        set(v) { p.edit().putBoolean("gyro", v).apply() }
    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) { p.edit().putBoolean("haptics", v).apply() }
    var rumble: Boolean
        get() = p.getBoolean("rumble", true)
        set(v) { p.edit().putBoolean("rumble", v).apply() }
    var gyroInvertY: Boolean
        get() = p.getBoolean("gyroInvertY", false)
        set(v) { p.edit().putBoolean("gyroInvertY", v).apply() }
    var gyroSens: Int
        get() = p.getInt("gyroSens", 50)
        set(v) { p.edit().putInt("gyroSens", v).apply() }
    var tpSpeed: Int
        get() = p.getInt("tpSpeed", 50)
        set(v) { p.edit().putInt("tpSpeed", v).apply() }
    var racingKb: Boolean
        get() = p.getBoolean("racingKb", false)
        set(v) { p.edit().putBoolean("racingKb", v).apply() }
    var pedalAnalog: Boolean
        get() = p.getBoolean("pedalAnalog", true)
        set(v) { p.edit().putBoolean("pedalAnalog", v).apply() }
    var steerInvert: Boolean
        get() = p.getBoolean("steerInvert", false)
        set(v) { p.edit().putBoolean("steerInvert", v).apply() }
    /** Phone rotation (degrees) that gives full steering lock. */
    var steerRange: Int
        get() = p.getInt("steerRange", 60)
        set(v) { p.edit().putInt("steerRange", v).apply() }
}

object Themes {
    val names = listOf("auto", "cyan", "pink", "lime", "orange", "violet")

    fun color(name: String): Int = when (name) {
        "cyan" -> 0xFF00E5FF.toInt()
        "pink" -> 0xFFFF4081.toInt()
        "lime" -> 0xFFC6FF00.toInt()
        "orange" -> 0xFFFFAB40.toInt()
        "violet" -> 0xFFB388FF.toInt()
        else -> 0xFF8A93B2.toInt()
    }

    /** "auto" = colour follows the current mode. */
    fun accent(theme: String, mode: String): Int {
        if (theme != "auto") return color(theme)
        return when (mode) {
            "minecraft" -> 0xFF55E06A.toInt()
            "roblox" -> 0xFFFF5252.toInt()
            "touchpad" -> 0xFFB388FF.toInt()
            "racing" -> 0xFFFF9100.toInt()
            else -> 0xFF00E5FF.toInt()
        }
    }
}
