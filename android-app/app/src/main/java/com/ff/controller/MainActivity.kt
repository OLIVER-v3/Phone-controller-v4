package com.ff.controller

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.min

class MainActivity : Activity() {
    private val net = NetworkClient()
    private lateinit var settings: Settings
    private lateinit var controller: ControllerView
    private lateinit var rumble: RumbleManager
    private lateinit var gyro: GyroManager
    private lateinit var tilt: TiltManager
    private lateinit var kbPanel: LinearLayout
    private lateinit var menuPanel: FrameLayout
    private lateinit var input: EditText
    private var autoEnter = true
    private val handler = Handler(Looper.getMainLooper())

    private val modes = listOf(
        "pad" to "Gamepad (Xbox)",
        "minecraft" to "Minecraft Java (Keyboard + Mouse)",
        "roblox" to "Roblox (Keyboard + Mouse)",
        "touchpad" to "Touchpad (Mouse)",
        "racing" to "Racing wheel (tilt steering)",
    )
    private val modeButtons = LinkedHashMap<String, Button>()
    private val themeButtons = LinkedHashMap<String, Button>()
    private val modButtons = LinkedHashMap<String, Button>()
    private val modDown = HashSet<String>()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val PANEL = 0xF2101628.toInt()
    private val BTN = 0xFF2A3350.toInt()

    private val cb = object : ControllerView.Callbacks {
        override fun onKeys() { toggleKeyboard() }
        override fun onMenu() { toggleMenu() }
        override fun onCycleMode() { cycleMode() }
        override fun onToggleMouse() { toggleMouse() }
        override fun onToggleGyro() { toggleGyro() }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!net.connected) rumble.stop()
            handler.postDelayed(this, 1000)
        }
    }

    private fun accent(): Int = Themes.accent(settings.theme, settings.mode)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        settings = Settings(this)
        rumble = RumbleManager(this)
        rumble.enabled = settings.rumble

        controller = ControllerView(this, net, cb)
        controller.theme = settings.theme
        controller.mouse = settings.mouse
        controller.gyroOn = settings.gyro
        controller.haptics = settings.haptics
        controller.gyroSens = settings.gyroSens
        controller.gyroInvertY = settings.gyroInvertY
        controller.tpSpeed = settings.tpSpeed
        controller.racingKb = settings.racingKb
        controller.pedalAnalog = settings.pedalAnalog
        controller.steerRange = settings.steerRange
        controller.steerInvert = settings.steerInvert
        controller.mode = settings.mode

        gyro = GyroManager(this) { yaw, pitch -> controller.onGyro(yaw, pitch) }
        tilt = TiltManager(this) { deg -> controller.onTilt(deg) }

        net.onMessage = { line -> handleNet(line) }

        val root = FrameLayout(this)
        root.addView(controller, FrameLayout.LayoutParams(MATCH, MATCH))
        buildKeyboard(root)
        buildMenu(root)
        setContentView(root)
        controller.push()
        handler.post(watchdog)
    }

    private fun handleNet(line: String) {
        try {
            val j = JSONObject(line)
            if (j.optString("t") == "rumble") {
                rumble.set(j.optDouble("l", 0.0).toFloat(), j.optDouble("r", 0.0).toFloat())
            }
        } catch (e: Exception) {
            // ignore malformed message
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        background = bg(BTN, 10)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { marginStart = dp(6) }

    private fun label(t: String, color: Int = Color.WHITE, size: Float = 14f): TextView = TextView(this).apply {
        text = t; setTextColor(color); textSize = size
    }

    private fun check(t: String, on: Boolean, onChange: (Boolean) -> Unit): CheckBox = CheckBox(this).apply {
        text = t
        isChecked = on
        setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun seek(value: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        max = 100
        progress = value
        progressTintList = ColorStateList.valueOf(accent())
        thumbTintList = ColorStateList.valueOf(accent())
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { onChange(progress) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun sendKey(k: String) {
        net.sendEvent(JSONObject().put("t", "key").put("k", k).toString())
    }

    private fun sendKeyState(k: String, down: Boolean) {
        net.sendEvent(JSONObject().put("t", "key").put("k", k).put("d", if (down) 1 else 0).toString())
    }

    // ---------------------------------------------------------------- keyboard panel
    private fun buildKeyboard(root: FrameLayout) {
        kbPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = bg(PANEL, 16, accent())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "វាយអក្សរនៅទីនេះ… (type here)"
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            background = bg(0xFF1B2236.toInt(), 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ -> sendText(); true }
        }
        row1.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        row1.addView(btn("Paste") { pasteClipboard() }, lp(WRAP, dp(40)))
        row1.addView(btn("Send") { sendText() }, lp(WRAP, dp(40)))
        row1.addView(btn("✕") { toggleKeyboard() }, lp(WRAP, dp(40)))

        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row2.addView(check("+Enter", true) { autoEnter = it })
        val keysRow = LinearLayout(this)
        for ((lbl, key) in listOf("Ctrl" to "lctrl", "Shift" to "lshift", "Alt" to "lalt")) {
            val b = btn(lbl) { toggleMod(key) }
            modButtons[key] = b
            keysRow.addView(b, lp(WRAP, dp(34)))
        }
        val quick = listOf(
            "Esc" to "esc", "Tab" to "tab", "Enter" to "enter", "Bksp" to "backspace", "Space" to "space",
            "▲" to "up", "▼" to "down", "◀" to "left", "▶" to "right",
            "T" to "t", "/" to "slash", "E" to "e", "F1" to "f1", "F3" to "f3", "F5" to "f5",
        )
        for ((lbl, key) in quick) keysRow.addView(btn(lbl) { sendKey(key) }, lp(WRAP, dp(34)))
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(keysRow)
        }
        row2.addView(scroll, LinearLayout.LayoutParams(0, WRAP, 1f))

        kbPanel.addView(row1, LinearLayout.LayoutParams(MATCH, WRAP))
        kbPanel.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        root.addView(kbPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            setMargins(dp(70), dp(6), dp(70), 0)
        })
    }

    private fun toggleMod(key: String) {
        val on = !modDown.contains(key)
        if (on) modDown.add(key) else modDown.remove(key)
        sendKeyState(key, on)
        refreshMods()
    }

    private fun refreshMods() {
        for ((k, b) in modButtons) {
            val on = modDown.contains(k)
            b.background = bg(if (on) accent() else BTN, 10)
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    private fun releaseMods() {
        for (k in modDown.toList()) sendKeyState(k, false)
        modDown.clear()
        refreshMods()
    }

    private fun pasteClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (t.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        net.sendEvent(JSONObject().put("t", "text").put("s", t).put("enter", false).toString())
    }

    private fun sendText() {
        val t = input.text.toString()
        if (t.isEmpty()) {
            if (autoEnter) sendKey("enter")
            return
        }
        net.sendEvent(JSONObject().put("t", "text").put("s", t).put("enter", autoEnter).toString())
        input.setText("")
    }

    private fun toggleKeyboard() {
        val show = kbPanel.visibility != View.VISIBLE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            menuPanel.visibility = View.GONE
            kbPanel.background = bg(PANEL, 16, accent())
            kbPanel.visibility = View.VISIBLE
            refreshMods()
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } else {
            kbPanel.visibility = View.GONE
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            releaseMods()
        }
    }

    // ---------------------------------------------------------------- menu panel
    private fun buildMenu(root: FrameLayout) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Settings", accent(), 20f), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(btn("✕") { toggleMenu() }, lp(WRAP, dp(36)))
        content.addView(head)

        content.addView(label("Mode", Color.GRAY, 12f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        for ((key, name) in modes) {
            val bt = btn(name) { setMode(key) }
            modeButtons[key] = bt
            content.addView(bt, LinearLayout.LayoutParams(MATCH, dp(38)).apply { topMargin = dp(5) })
        }

        content.addView(label("Theme", Color.GRAY, 12f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        val themeRow = LinearLayout(this)
        for (name in Themes.names) {
            val bt = Button(this).apply {
                text = if (name == "auto") "A" else ""
                setTextColor(Color.WHITE)
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { setTheme(name) }
            }
            themeButtons[name] = bt
            themeRow.addView(bt, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
        }
        content.addView(themeRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })

        content.addView(label("Gyro sensitivity", Color.GRAY, 12f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        content.addView(seek(settings.gyroSens) { v -> settings.gyroSens = v; controller.gyroSens = v })
        content.addView(check("Invert gyro Y", settings.gyroInvertY) { v -> settings.gyroInvertY = v; controller.gyroInvertY = v })

        content.addView(label("Touchpad speed", Color.GRAY, 12f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        content.addView(seek(settings.tpSpeed) { v -> settings.tpSpeed = v; controller.tpSpeed = v })

        content.addView(label("Racing (tilt steering)", Color.GRAY, 12f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        content.addView(label("Phone rotation for full lock (smaller = more sensitive)", Color.WHITE, 12f))
        content.addView(seek(((settings.steerRange - 30) / 0.9f).toInt().coerceIn(0, 100)) { v ->
            val deg = 30 + (v * 0.9f).toInt()
            settings.steerRange = deg
            controller.steerRange = deg
        })
        content.addView(check("Keyboard output (W A S D) instead of Xbox pad", settings.racingKb) { v ->
            settings.racingKb = v
            controller.racingKb = v
            controller.push()
        })
        content.addView(check("Analog pedals (slide your finger for pressure)", settings.pedalAnalog) { v ->
            settings.pedalAnalog = v
            controller.pedalAnalog = v
        })
        content.addView(check("Invert steering", settings.steerInvert) { v ->
            settings.steerInvert = v
            controller.steerInvert = v
        })

        content.addView(check("Haptic feedback on buttons", settings.haptics) { v -> settings.haptics = v; controller.haptics = v },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        content.addView(check("Rumble (vibrate when the game rumbles)", settings.rumble) { v -> settings.rumble = v; rumble.enabled = v; if (!v) rumble.stop() })
        content.addView(btn("Test rumble") {
            rumble.set(1f, 1f)
            handler.postDelayed({ rumble.stop() }, 500)
        }, LinearLayout.LayoutParams(MATCH, dp(38)).apply { topMargin = dp(6) })

        content.addView(label("Phone Controller v4", Color.GRAY, 11f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })

        val sv = ScrollView(this)
        sv.addView(content)
        val short = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        menuPanel = FrameLayout(this).apply {
            visibility = View.GONE
            background = bg(PANEL, 18, accent())
            addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(menuPanel, FrameLayout.LayoutParams(dp(420), (short * 0.90f).toInt(), Gravity.CENTER))
        refreshMenu()
    }

    private fun refreshMenu() {
        val ac = accent()
        for ((k, bt) in modeButtons) {
            val on = k == settings.mode
            bt.background = bg(if (on) ac else BTN, 10)
            bt.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
        for ((k, bt) in themeButtons) {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(if (k == "auto") 0xFF2A3350.toInt() else Themes.color(k))
            d.setStroke(dp(if (k == settings.theme) 3 else 1), if (k == settings.theme) Color.WHITE else 0x55FFFFFF)
            bt.background = d
        }
        menuPanel.background = bg(PANEL, 18, ac)
    }

    private fun toggleMenu() {
        val show = menuPanel.visibility != View.VISIBLE
        if (show && kbPanel.visibility == View.VISIBLE) toggleKeyboard()
        if (show) refreshMenu()
        menuPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- actions
    private fun setMode(m: String) {
        settings.mode = m
        controller.mode = m
        controller.push()
        applyGyro()
        applyTilt()
        refreshMenu()
    }

    private fun cycleMode() {
        val i = modes.indexOfFirst { it.first == settings.mode }
        setMode(modes[(i + 1) % modes.size].first)
    }

    private fun setTheme(t: String) {
        settings.theme = t
        controller.theme = t
        refreshMenu()
    }

    private fun toggleMouse() {
        settings.mouse = !settings.mouse
        controller.mouse = settings.mouse
        controller.push()
    }

    private fun toggleGyro() {
        if (!gyro.available) {
            Toast.makeText(this, "This phone has no gyroscope", Toast.LENGTH_SHORT).show()
            return
        }
        settings.gyro = !settings.gyro
        controller.gyroOn = settings.gyro
        applyGyro()
        controller.push()
    }

    private fun applyGyro() {
        val ok = settings.gyro && settings.mode != "racing" && settings.mode != "touchpad"
        if (ok) gyro.start() else gyro.stop()
    }

    private fun applyTilt() {
        if (settings.mode == "racing") {
            if (!tilt.available) {
                Toast.makeText(this, "This phone has no tilt sensor", Toast.LENGTH_SHORT).show()
                return
            }
            controller.resetTilt()
            tilt.start()
        } else {
            tilt.stop()
        }
    }

    // ---------------------------------------------------------------- system
    override fun onResume() {
        super.onResume()
        applyGyro()
        applyTilt()
    }

    override fun onPause() {
        gyro.stop()
        tilt.stop()
        rumble.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        when {
            kbPanel.visibility == View.VISIBLE -> toggleKeyboard()
            menuPanel.visibility == View.VISIBLE -> toggleMenu()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        rumble.stop()
        gyro.stop()
        tilt.stop()
        net.stop()
        super.onDestroy()
    }
}
