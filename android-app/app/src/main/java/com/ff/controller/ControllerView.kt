package com.ff.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The whole controller: neon gamepad (sticks, D-pad, ABXY, analog triggers, bumpers),
 * touchpad mode, gyro aiming overlay, top chips and status line. One multi-touch view.
 */
class ControllerView(
    ctx: Context,
    private val net: NetworkClient,
    private val cb: Callbacks,
) : View(ctx) {

    interface Callbacks {
        fun onKeys()
        fun onMenu()
        fun onCycleMode()
        fun onToggleMouse()
        fun onToggleGyro()
    }

    // ---------------------------------------------------------------- settings (set by the activity)
    var mode: String = "pad"
        set(v) { field = v; clearInput(); layoutAll(); invalidate() }
    var theme: String = "auto"
        set(v) { field = v; invalidate() }
    var mouse: Boolean = false
        set(v) { field = v; invalidate() }
    var gyroOn: Boolean = false
        set(v) { field = v; if (!v) { gyroX = 0f; gyroY = 0f }; layoutAll(); invalidate() }
    var haptics: Boolean = true
    var gyroSens: Int = 50
    var gyroInvertY: Boolean = false
    var tpSpeed: Int = 50
    var racingKb: Boolean = false
    var pedalAnalog: Boolean = true
    var steerRange: Int = 60
    var steerInvert: Boolean = false

    // ---------------------------------------------------------------- model
    private class Stick {
        var cx = 0f; var cy = 0f; var r = 0f
        var dx = 0f; var dy = 0f; var pid = -1
    }

    /** kind: 0 face, 1 dpad arm, 2 pill, 3 stick click, 4 analog trigger (x), 5 pedal (y) */
    private class Btn(val name: String, val defLabel: String, val defKind: Int) {
        val rect = RectF(); var down = false
        var label = defLabel
        var kind = defKind
    }

    private class Chip(val id: String) { val rect = RectF() }

    private val density = resources.displayMetrics.density

    private val left = Stick()
    private val right = Stick()
    private val sticks = listOf(left, right)

    private val buttons = listOf(
        Btn("A", "A", 0), Btn("B", "B", 0), Btn("X", "X", 0), Btn("Y", "Y", 0),
        Btn("UP", "", 1), Btn("DOWN", "", 1), Btn("LEFT", "", 1), Btn("RIGHT", "", 1),
        Btn("LB", "LB", 2), Btn("RB", "RB", 2), Btn("BACK", "BACK", 2), Btn("START", "START", 2),
        Btn("LS", "L3", 3), Btn("RS", "R3", 3),
        Btn("LT", "LT", 4), Btn("RT", "RT", 4),
        Btn("ML", "LEFT CLICK", 2), Btn("MR", "RIGHT CLICK", 2),
    )
    private val btnMap = buttons.associateBy { it.name }
    private fun b(n: String): Btn = btnMap.getValue(n)
    private val padBtns = buttons.filter { it.name != "ML" && it.name != "MR" }
    private val tpBtns = buttons.filter { it.name == "ML" || it.name == "MR" }
    private val racingNames = setOf("LB", "RB", "LT", "RT", "UP", "DOWN", "LEFT", "RIGHT", "A", "B", "X", "Y", "BACK", "START")
    private val racingBtns = buttons.filter { it.name in racingNames }
    private fun activeBtns(): List<Btn> = when (mode) {
        "touchpad" -> tpBtns
        "racing" -> racingBtns
        else -> padBtns
    }

    private val chipKeys = Chip("keys")
    private val chipMode = Chip("mode")
    private val chipMouse = Chip("mouse")
    private val chipGyro = Chip("gyro")
    private val chipCalib = Chip("calib")
    private val chipMenu = Chip("menu")
    private val chips = listOf(chipKeys, chipMode, chipMouse, chipGyro, chipCalib, chipMenu)
    private fun visibleChips(): List<Chip> = chips.filter {
        when (it.id) {
            "mouse" -> mode == "pad"
            "gyro" -> mode != "touchpad" && mode != "racing"
            "calib" -> mode == "racing"
            else -> true
        }
    }

    private var dpX = 0f; private var dpY = 0f; private var dpD = 0f; private var dpH = 0f
    private val tpRect = RectF()

    // analog trigger position (0..1 along the bar)
    private var ltFrac = 0f
    private var rtFrac = 0f

    // gyro contribution to the right stick
    private var gyroX = 0f
    private var gyroY = 0f

    // racing: tilt steering
    private var tiltFirst = true
    private var tiltAngle = 0f
    private var tiltOffset = 0f
    private var smoothSteer = 0f
    private var steer = 0f
    private var wheelDeg = 0f
    private var wheelCx = 0f
    private var wheelCy = 0f
    private var wheelR = 0f

    // touchpad gesture state
    private val tpIds = BooleanArray(32)
    private val tpLastX = FloatArray(32)
    private val tpLastY = FloatArray(32)
    private var tpStart = 0L
    private var tpMoved = 0f
    private var tpMax = 0
    private var accX = 0f
    private var accY = 0f
    private var scrollAcc = 0f

    // ---------------------------------------------------------------- paints
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val dotPaint = Paint()
    private val glowPaint = Paint()
    private var bgShader: Shader? = null

    private val faceColors = mapOf(
        "A" to 0xFF3DDC84.toInt(), "B" to 0xFFFF5252.toInt(),
        "X" to 0xFF448AFF.toInt(), "Y" to 0xFFFFD740.toInt(),
    )

    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 700) }
    }

    init {
        val tile = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tile)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = 0x22FFFFFF
        tc.drawCircle(18f, 18f, 1.4f, tp)
        dotPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    // ---------------------------------------------------------------- helpers
    private fun accent(): Int = Themes.accent(theme, mode)
    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)
    private fun mix(a: Int, bb: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(bb) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(bb) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(bb) * t).toInt()
        return Color.rgb(r, g, bl)
    }
    private fun lighten(c: Int, t: Float): Int = mix(c, Color.WHITE, t)
    private fun darken(c: Int, t: Float): Int = mix(c, Color.BLACK, t)
    private fun f(v: Float): String = "%.3f".format(Locale.US, v)

    private fun fill(color: Int) { p.style = Paint.Style.FILL; p.shader = null; p.color = color }
    private fun stroke(color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.shader = null; p.color = color; p.strokeWidth = w
    }
    private fun text(c: Canvas, t: String, cx: Float, cy: Float, size: Float, color: Int) {
        p.style = Paint.Style.FILL; p.shader = null; p.color = color
        p.textSize = size; p.textAlign = Paint.Align.CENTER
        c.drawText(t, cx, cy + size * 0.35f, p)
    }
    private fun tapFeedback() { if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    // ---------------------------------------------------------------- layout
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF070A16.toInt(), 0xFF141B33.toInt(), Shader.TileMode.CLAMP)
        layoutAll()
    }

    private fun layoutAll() {
        if (width == 0 || height == 0) return
        val W = width.toFloat(); val H = height.toFloat(); val u = min(W, H)
        for (bt in buttons) { bt.label = bt.defLabel; bt.kind = bt.defKind }

        left.cx = W * 0.17f; left.cy = H * 0.68f; left.r = u * 0.19f
        right.cx = W * 0.60f; right.cy = H * 0.72f; right.r = u * 0.19f

        fun circle(n: String, cx: Float, cy: Float, r: Float) { b(n).rect.set(cx - r, cy - r, cx + r, cy + r) }
        val ax = W * 0.86f; val ay = H * 0.58f; val d = u * 0.165f; val br = u * 0.085f
        circle("A", ax, ay + d, br); circle("B", ax + d, ay, br)
        circle("X", ax - d, ay, br); circle("Y", ax, ay - d, br)

        dpX = W * 0.40f; dpY = H * 0.64f; dpD = u * 0.15f; dpH = u * 0.07f
        circle("UP", dpX, dpY - dpD, dpH); circle("DOWN", dpX, dpY + dpD, dpH)
        circle("LEFT", dpX - dpD, dpY, dpH); circle("RIGHT", dpX + dpD, dpY, dpH)

        circle("LS", left.cx, left.cy - left.r * 1.5f, u * 0.05f)
        circle("RS", right.cx, right.cy - right.r * 1.5f, u * 0.05f)

        val sw = W * 0.14f; val sh = H * 0.10f; val m = W * 0.03f
        b("LT").rect.set(m, H * 0.04f, m + sw, H * 0.04f + sh)
        b("LB").rect.set(m, H * 0.17f, m + sw, H * 0.17f + sh)
        b("RT").rect.set(W - m - sw, H * 0.04f, W - m, H * 0.04f + sh)
        b("RB").rect.set(W - m - sw, H * 0.17f, W - m, H * 0.17f + sh)

        val cw = W * 0.075f; val chh = H * 0.09f; val gp = W * 0.006f
        b("BACK").rect.set(W * 0.5f - gp - cw, H * 0.17f, W * 0.5f - gp, H * 0.17f + chh)
        b("START").rect.set(W * 0.5f + gp, H * 0.17f, W * 0.5f + gp + cw, H * 0.17f + chh)

        tpRect.set(W * 0.05f, H * 0.17f, W * 0.95f, H * 0.79f)
        b("ML").rect.set(W * 0.05f, H * 0.83f, W * 0.495f, H * 0.96f)
        b("MR").rect.set(W * 0.505f, H * 0.83f, W * 0.95f, H * 0.96f)

        if (mode == "racing") layoutRacing(W, H)
        layoutChips()
    }

    /** Racing layout: pedals at the sides, wheel in the middle, function buttons around it. */
    private fun layoutRacing(W: Float, H: Float) {
        fun put(n: String, l: Float, t: Float, r: Float, bm: Float, label: String, kind: Int) {
            val bt = b(n)
            bt.rect.set(W * l, H * t, W * r, H * bm)
            bt.label = label
            bt.kind = kind
        }
        put("LB", 0.04f, 0.05f, 0.17f, 0.17f, "SHIFT -", 2)
        put("RB", 0.83f, 0.05f, 0.96f, 0.17f, "SHIFT +", 2)
        put("LT", 0.04f, 0.30f, 0.17f, 0.94f, "BRAKE", 5)
        put("RT", 0.83f, 0.22f, 0.96f, 0.94f, "GAS", 5)
        val y0 = 0.27f; val st = 0.135f; val hh = 0.115f
        val leftCol = listOf("UP" to "LIGHTS", "LEFT" to "SIGNAL <", "RIGHT" to "SIGNAL >", "DOWN" to "LOOK BACK")
        val rightCol = listOf("B" to "NITRO", "A" to "HANDBRAKE", "X" to "HORN", "Y" to "CAMERA")
        for (i in leftCol.indices) put(leftCol[i].first, 0.20f, y0 + i * st, 0.32f, y0 + i * st + hh, leftCol[i].second, 2)
        for (i in rightCol.indices) put(rightCol[i].first, 0.68f, y0 + i * st, 0.80f, y0 + i * st + hh, rightCol[i].second, 2)
        put("BACK", 0.40f, 0.82f, 0.495f, 0.91f, "RESET", 2)
        put("START", 0.505f, 0.82f, 0.60f, 0.91f, "PAUSE", 2)
        wheelCx = W * 0.5f; wheelCy = H * 0.53f; wheelR = H * 0.26f
    }

    private fun layoutChips() {
        if (width == 0 || height == 0) return
        val W = width.toFloat(); val H = height.toFloat()
        val vis = visibleChips()
        val ch = H * 0.085f; val cy0 = H * 0.045f
        val gap = W * 0.010f
        fun wOf(c: Chip): Float = when (c.id) {
            "mode", "mouse" -> W * 0.135f
            "calib" -> W * 0.12f
            else -> W * 0.082f
        }
        val total = vis.fold(0f) { a, c -> a + wOf(c) } + gap * (vis.size - 1)
        var x = W / 2f - total / 2f
        for (c in vis) { c.rect.set(x, cy0, x + wOf(c), cy0 + ch); x += wOf(c) + gap }
    }

    // ---------------------------------------------------------------- input
    private fun clearInput() {
        for (s in sticks) { s.pid = -1; s.dx = 0f; s.dy = 0f }
        for (i in 0 until 32) tpIds[i] = false
        tpMax = 0
        for (bt in buttons) {
            if (bt.down && (bt.name == "ML" || bt.name == "MR")) sendMouseButton(bt.name, false)
            bt.down = false
        }
        ltFrac = 0f; rtFrac = 0f
    }

    private fun sendMouseButton(name: String, down: Boolean) {
        val which = if (name == "ML") "left" else "right"
        net.sendEvent("""{"t":"mb","b":"$which","d":${if (down) 1 else 0}}""")
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val act = e.actionMasked
        val ai = e.actionIndex

        if (act == MotionEvent.ACTION_CANCEL) { clearInput(); push(); return true }

        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            val id = e.getPointerId(ai); val x = e.getX(ai); val y = e.getY(ai)
            for (c in visibleChips()) {
                if (c.rect.contains(x, y)) {
                    tapFeedback()
                    when (c.id) {
                        "keys" -> cb.onKeys()
                        "menu" -> cb.onMenu()
                        "mouse" -> cb.onToggleMouse()
                        "gyro" -> cb.onToggleGyro()
                        "calib" -> calibrate()
                        else -> cb.onCycleMode()
                    }
                    return true
                }
            }
            if (mode == "touchpad") {
                if (id < 32 && tpRect.contains(x, y)) tpDown(id, x, y)
            } else if (mode != "racing") {
                for (s in sticks) {
                    if (s.pid == -1 && hypot(x - s.cx, y - s.cy) < s.r * 1.2f) { s.pid = id; break }
                }
            }
        }

        val releasing = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP
        val relId = if (releasing) e.getPointerId(ai) else -1

        if (mode == "touchpad") {
            touchpadEvent(e, act, relId)
        } else {
            if (releasing) for (s in sticks) if (s.pid == relId) { s.pid = -1; s.dx = 0f; s.dy = 0f }
            for (s in sticks) {
                if (s.pid == -1) continue
                val i = e.findPointerIndex(s.pid)
                if (i < 0) continue
                var dx = (e.getX(i) - s.cx) / s.r
                var dy = (e.getY(i) - s.cy) / s.r
                val len = hypot(dx, dy)
                if (len > 1f) { dx /= len; dy /= len }
                s.dx = dx; s.dy = dy
            }
        }

        // buttons (a finger may slide between buttons; triggers read the finger's x position)
        val btns = activeBtns()
        val before = btns.filter { it.down }.map { it.name }.toSet()
        btns.forEach { it.down = false }
        ltFrac = 0f; rtFrac = 0f
        for (i in 0 until e.pointerCount) {
            if (releasing && i == ai) continue
            val id = e.getPointerId(i)
            if (mode == "touchpad") { if (id < 32 && tpIds[id]) continue }
            else if (id == left.pid || id == right.pid) continue
            val x = e.getX(i); val y = e.getY(i)
            for (bt in btns) {
                val slop = bt.rect.width() * 0.10f
                if (x > bt.rect.left - slop && x < bt.rect.right + slop &&
                    y > bt.rect.top - slop && y < bt.rect.bottom + slop) {
                    bt.down = true
                    if (bt.kind == 4 || bt.kind == 5) {
                        val raw = if (bt.kind == 4) (x - bt.rect.left) / bt.rect.width() else (y - bt.rect.top) / bt.rect.height()
                        val fr = raw.coerceIn(0f, 1f)
                        if (bt.name == "LT") ltFrac = max(ltFrac, fr) else rtFrac = max(rtFrac, fr)
                    }
                    break
                }
            }
        }
        if (mode == "touchpad") {
            for (nm in listOf("ML", "MR")) {
                val now = b(nm).down
                if (now != (nm in before)) sendMouseButton(nm, now)
            }
        }
        if (haptics && btns.any { it.down && it.name !in before }) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        push()
        return true
    }

    // ---- touchpad
    private fun tpCount(): Int { var c = 0; for (v in tpIds) if (v) c++; return c }

    private fun tpDown(id: Int, x: Float, y: Float) {
        if (tpCount() == 0) { tpStart = SystemClock.uptimeMillis(); tpMoved = 0f; tpMax = 0; scrollAcc = 0f }
        tpIds[id] = true; tpLastX[id] = x; tpLastY[id] = y
        tpMax = max(tpMax, tpCount())
    }

    private fun touchpadEvent(e: MotionEvent, act: Int, relId: Int) {
        if (act == MotionEvent.ACTION_MOVE) {
            var n = 0
            var firstDx = 0f; var firstDy = 0f; var sumDy = 0f
            for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id >= 32 || !tpIds[id]) continue
                val x = e.getX(i); val y = e.getY(i)
                val dx = x - tpLastX[id]; val dy = y - tpLastY[id]
                tpLastX[id] = x; tpLastY[id] = y
                tpMoved += abs(dx) + abs(dy)
                if (n == 0) { firstDx = dx; firstDy = dy }
                sumDy += dy
                n++
            }
            if (n == 1) moveCursor(firstDx, firstDy)
            else if (n >= 2) scrollBy(sumDy / n)
        }
        if (relId in 0..31 && tpIds[relId]) {
            tpIds[relId] = false
            if (tpCount() == 0) {
                val dt = SystemClock.uptimeMillis() - tpStart
                if (dt < 250 && tpMoved < density * 14f) {
                    val which = if (tpMax >= 2) "right" else "left"
                    net.sendEvent("""{"t":"click","b":"$which"}""")
                }
                tpMax = 0
            }
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val mult = 0.6f + tpSpeed / 100f * 2.4f
        accX += dx * mult; accY += dy * mult
        val ix = accX.toInt(); val iy = accY.toInt()
        if (ix != 0 || iy != 0) {
            accX -= ix; accY -= iy
            net.sendEvent("""{"t":"mv","dx":$ix,"dy":$iy}""")
        }
    }

    private fun scrollBy(dy: Float) {
        scrollAcc += dy
        val step = density * 16f
        while (scrollAcc > step) { scrollAcc -= step; net.sendEvent("""{"t":"scroll","d":1}""") }
        while (scrollAcc < -step) { scrollAcc += step; net.sendEvent("""{"t":"scroll","d":-1}""") }
    }

    // ---- gyro (called by GyroManager with screen-frame rates in rad/s)
    fun onGyro(yaw: Float, pitch: Float) {
        val gain = 0.15f + gyroSens / 100f * 0.85f
        val dz = 0.02f
        val yw = if (abs(yaw) < dz) 0f else yaw
        val pt = if (abs(pitch) < dz) 0f else pitch
        gyroX = (-yw * gain).coerceIn(-1f, 1f)
        gyroY = ((if (gyroInvertY) -pt else pt) * gain).coerceIn(-1f, 1f)
        if (gyroOn && mode != "touchpad") push()
    }

    // ---- tilt steering (called by TiltManager with the roll angle in degrees)
    fun resetTilt() { tiltFirst = true }

    fun calibrate() {
        tiltOffset = tiltAngle
        tapFeedback()
        invalidate()
    }

    fun onTilt(angle: Float) {
        if (tiltFirst) { tiltOffset = angle; tiltFirst = false }
        tiltAngle = angle
        var a = angle - tiltOffset
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        wheelDeg = a
        val range = max(10, steerRange).toFloat()
        val raw = (a / range).coerceIn(-1f, 1f)
        val mag = abs(raw)
        val dz = 0.03f
        var sv = if (mag < dz) 0f else ((mag - dz) / (1f - dz)).pow(1.15f)
        if (raw < 0f) sv = -sv
        smoothSteer += (sv - smoothSteer) * 0.5f
        steer = if (steerInvert) -smoothSteer else smoothSteer
        if (mode == "racing") push()
    }

    // ---- send
    fun push() {
        val racing = mode == "racing"
        val analog = (mode == "pad" && !mouse) || (racing && !racingKb && pedalAnalog)
        val lt = if (b("LT").down) (if (analog) 0.35f + 0.65f * ltFrac else 1f) else 0f
        val rt = if (b("RT").down) (if (analog) 0.35f + 0.65f * rtFrac else 1f) else 0f
        val src = if (racing) racingBtns else padBtns
        val names = src.filter { it.down && it.name != "LT" && it.name != "RT" }
            .joinToString(",") { "\"${it.name}\"" }
        val useGyro = gyroOn && mode != "touchpad" && !racing
        val rx = if (racing) 0f else if (useGyro) (right.dx + gyroX).coerceIn(-1f, 1f) else right.dx
        val ry = if (racing) 0f else if (useGyro) (-right.dy + gyroY).coerceIn(-1f, 1f) else -right.dy
        val lx = if (racing) steer else left.dx
        val ly = if (racing) 0f else -left.dy
        net.sendState(
            """{"t":"s","mode":"$mode","ms":${if (mouse) 1 else 0},"kb":${if (racingKb) 1 else 0},"lx":${f(lx)},"ly":${f(ly)},"rx":${f(rx)},"ry":${f(ry)},"lt":${f(lt)},"rt":${f(rt)},"b":[$names]}"""
        )
        invalidate()
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        val ac = accent()
        val W = width.toFloat(); val H = height.toFloat()

        bgPaint.shader = bgShader
        c.drawRect(0f, 0f, W, H, bgPaint)
        c.drawRect(0f, 0f, W, H, dotPaint)
        glowPaint.shader = LinearGradient(0f, 0f, W, 0f,
            intArrayOf(withAlpha(ac, 0), withAlpha(ac, 220), withAlpha(ac, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, 3f * density, glowPaint)

        when (mode) {
            "touchpad" -> drawTouchpad(c, ac)
            "racing" -> drawRacingUi(c, ac)
            else -> drawPad(c, ac)
        }

        for (ch in visibleChips()) drawChip(c, ch, ac)
        drawStatus(c)
    }

    private fun drawPad(c: Canvas, ac: Int) {
        for (s in sticks) drawStick(c, s, ac)
        drawDpad(c, ac)
        for (bt in padBtns) {
            when (bt.kind) {
                0 -> drawFace(c, bt)
                2 -> drawPill(c, bt, ac)
                3 -> drawClick(c, bt, ac)
                4 -> drawTrigger(c, bt, ac)
            }
        }
    }

    private fun drawStick(c: Canvas, s: Stick, ac: Int) {
        val active = s.pid != -1
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(s.cx, s.cy, s.r,
            intArrayOf(0xFF232C4B.toInt(), 0xFF0A0F1E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(s.cx, s.cy, s.r, p)
        p.shader = null
        if (active) { stroke(withAlpha(ac, 60), 10f * density); c.drawCircle(s.cx, s.cy, s.r * 1.02f, p) }
        stroke(withAlpha(ac, if (active) 240 else 120), 2.5f * density)
        c.drawCircle(s.cx, s.cy, s.r, p)
        stroke(withAlpha(Color.WHITE, 40), 1.5f * density)
        for (i in 0 until 16) {
            c.save()
            c.rotate(i * 22.5f, s.cx, s.cy)
            c.drawLine(s.cx, s.cy - s.r * 0.78f, s.cx, s.cy - s.r * 0.90f, p)
            c.restore()
        }
        stroke(withAlpha(ac, 40), 1.5f * density)
        c.drawCircle(s.cx, s.cy, s.r * 0.55f, p)

        val kx = s.cx + s.dx * s.r * 0.62f
        val ky = s.cy + s.dy * s.r * 0.62f
        val kr = s.r * 0.40f
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(kx - kr * 0.3f, ky - kr * 0.3f, kr * 1.4f,
            intArrayOf(lighten(ac, 0.5f), ac, darken(ac, 0.45f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        p.alpha = if (active) 255 else 170
        c.drawCircle(kx, ky, kr, p)
        p.shader = null
        p.alpha = 255
        stroke(withAlpha(Color.WHITE, 110), 1.5f * density)
        c.drawCircle(kx, ky, kr, p)
    }

    private fun drawFace(c: Canvas, bt: Btn) {
        val base = faceColors[bt.name] ?: Color.WHITE
        val cx = bt.rect.centerX(); val cy = bt.rect.centerY()
        val r = bt.rect.width() / 2f * (if (bt.down) 0.93f else 1f)
        if (bt.down) { stroke(withAlpha(base, 90), 12f * density); c.drawCircle(cx, cy, r * 1.05f, p) }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy - r * 0.4f, r * 1.6f,
            intArrayOf(if (bt.down) base else 0xFF283256.toInt(), if (bt.down) darken(base, 0.25f) else 0xFF121830.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        stroke(withAlpha(base, 235), 2.5f * density)
        c.drawCircle(cx, cy, r, p)
        text(c, bt.label, cx, cy, r * 0.95f, if (bt.down) Color.BLACK else base)
    }

    private fun drawDpad(c: Canvas, ac: Int) {
        val h = dpH; val d = dpD
        val path = Path()
        path.addRoundRect(RectF(dpX - h * 0.95f, dpY - d - h, dpX + h * 0.95f, dpY + d + h), h * 0.5f, h * 0.5f, Path.Direction.CW)
        path.addRoundRect(RectF(dpX - d - h, dpY - h * 0.95f, dpX + d + h, dpY + h * 0.95f), h * 0.5f, h * 0.5f, Path.Direction.CW)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(dpX, dpY, d + h,
            intArrayOf(0xFF283256.toInt(), 0xFF101630.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        stroke(withAlpha(ac, 120), 2f * density)
        c.drawPath(path, p)

        val names = arrayOf("UP", "RIGHT", "DOWN", "LEFT")
        for (i in 0 until 4) {
            val on = b(names[i]).down
            c.save()
            c.rotate(i * 90f, dpX, dpY)
            val ty = dpY - d
            val tri = Path()
            tri.moveTo(dpX, ty - h * 0.45f)
            tri.lineTo(dpX - h * 0.5f, ty + h * 0.35f)
            tri.lineTo(dpX + h * 0.5f, ty + h * 0.35f)
            tri.close()
            if (on) { fill(withAlpha(ac, 70)); c.drawCircle(dpX, ty, h * 0.95f, p) }
            fill(if (on) ac else withAlpha(Color.WHITE, 120))
            c.drawPath(tri, p)
            c.restore()
        }
    }

    private fun drawPill(c: Canvas, bt: Btn, ac: Int) {
        val r = bt.rect; val rad = r.height() * 0.35f
        fill(if (bt.down) withAlpha(ac, 210) else 0xFF161D38.toInt())
        c.drawRoundRect(r, rad, rad, p)
        stroke(withAlpha(ac, if (bt.down) 255 else 140), 2f * density)
        c.drawRoundRect(r, rad, rad, p)
        val size = r.height() * (if (bt.label.length > 4) 0.30f else 0.38f)
        text(c, bt.label, r.centerX(), r.centerY(), size, if (bt.down) Color.BLACK else Color.WHITE)
    }

    private fun drawClick(c: Canvas, bt: Btn, ac: Int) {
        val cx = bt.rect.centerX(); val cy = bt.rect.centerY(); val r = bt.rect.width() / 2f
        fill(if (bt.down) withAlpha(ac, 210) else 0xFF161D38.toInt())
        c.drawCircle(cx, cy, r, p)
        stroke(withAlpha(ac, if (bt.down) 255 else 110), 2f * density)
        c.drawCircle(cx, cy, r, p)
        text(c, bt.label, cx, cy, r * 0.8f, if (bt.down) Color.BLACK else withAlpha(Color.WHITE, 200))
    }

    private fun drawTrigger(c: Canvas, bt: Btn, ac: Int) {
        val r = bt.rect; val rad = r.height() * 0.35f
        fill(0xFF161D38.toInt())
        c.drawRoundRect(r, rad, rad, p)
        if (bt.down) {
            val frac = max(if (bt.name == "LT") ltFrac else rtFrac, 0.06f)
            c.save()
            val clip = Path()
            clip.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(r.left, 0f, r.right, 0f, withAlpha(ac, 90), withAlpha(ac, 235), Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.top, r.left + r.width() * frac, r.bottom, p)
            p.shader = null
            c.restore()
        }
        stroke(withAlpha(ac, if (bt.down) 255 else 140), 2f * density)
        c.drawRoundRect(r, rad, rad, p)
        text(c, bt.label, r.centerX(), r.centerY(), r.height() * 0.38f, if (bt.down) Color.WHITE else withAlpha(Color.WHITE, 220))
    }

    private fun drawChip(c: Canvas, ch: Chip, ac: Int) {
        val on = (ch.id == "mouse" && mouse) || (ch.id == "gyro" && gyroOn)
        val r = ch.rect; val rad = r.height() / 2f
        fill(if (on) withAlpha(ac, 80) else 0xCC141A33.toInt())
        c.drawRoundRect(r, rad, rad, p)
        stroke(withAlpha(ac, if (on || ch.id == "mode") 240 else 150), 2f * density)
        c.drawRoundRect(r, rad, rad, p)
        val t = when (ch.id) {
            "keys" -> "KEYS"
            "menu" -> "MENU"
            "mouse" -> if (mouse) "MOUSE ON" else "MOUSE OFF"
            "gyro" -> if (gyroOn) "GYRO ON" else "GYRO"
            "calib" -> "CALIBRATE"
            else -> modeLabel()
        }
        val col = if (ch.id == "mode" || on) ac else Color.WHITE
        text(c, t, r.centerX(), r.centerY(), r.height() * 0.36f, col)
    }

    private fun modeLabel(): String = when (mode) {
        "minecraft" -> "MINECRAFT"
        "roblox" -> "ROBLOX"
        "touchpad" -> "TOUCHPAD"
        "racing" -> "RACING"
        else -> "GAMEPAD"
    }

    private fun drawStatus(c: Canvas) {
        val ok = net.connected
        val ping = net.pingMs
        val label = if (!ok) "● Waiting for PC…" else if (ping >= 0) "● Connected · $ping ms" else "● Connected"
        val col = when {
            !ok -> 0xFFFF5252.toInt()
            ping > 120 -> 0xFFFFAB40.toInt()
            ping > 60 -> 0xFFFFD740.toInt()
            else -> 0xFF3DDC84.toInt()
        }
        val size = height * 0.036f
        if (mode == "touchpad") {
            p.style = Paint.Style.FILL; p.shader = null; p.color = col
            p.textSize = size; p.textAlign = Paint.Align.LEFT
            c.drawText(label, width * 0.03f, height * 0.09f, p)
        } else {
            text(c, label, width / 2f, height * 0.955f, size, col)
        }
    }

    private fun drawRacingUi(c: Canvas, ac: Int) {
        drawWheel(c, ac)
        for (bt in racingBtns) {
            when (bt.kind) {
                2 -> drawPill(c, bt, faceColors[bt.name] ?: ac)
                5 -> drawPedal(c, bt)
            }
        }
    }

    private fun drawWheel(c: Canvas, ac: Int) {
        val cx = wheelCx; val cy = wheelCy; val r = wheelR
        // steering gauge above the wheel
        val gw = width * 0.30f; val gh = 8f * density; val gy = height * 0.20f
        val mid = width / 2f
        fill(0xFF161D38.toInt())
        c.drawRoundRect(RectF(mid - gw / 2f, gy, mid + gw / 2f, gy + gh), gh, gh, p)
        val to = mid + steer * gw / 2f
        fill(ac)
        c.drawRoundRect(RectF(min(mid, to), gy, max(mid, to) + 1f, gy + gh), gh, gh, p)
        fill(Color.WHITE)
        c.drawRect(mid - 1.5f * density, gy - 3f * density, mid + 1.5f * density, gy + gh + 3f * density, p)

        // wheel (turns with the steering value)
        c.save()
        c.rotate(steer * 120f, cx, cy)
        stroke(withAlpha(ac, 40), 26f * density); c.drawCircle(cx, cy, r, p)
        stroke(0xFF2A3354.toInt(), 16f * density); c.drawCircle(cx, cy, r, p)
        stroke(withAlpha(ac, 230), 3f * density)
        c.drawCircle(cx, cy, r + 8f * density, p)
        c.drawCircle(cx, cy, r - 8f * density, p)
        stroke(0xFF2A3354.toInt(), 14f * density)
        c.drawLine(cx, cy, cx - r, cy, p)
        c.drawLine(cx, cy, cx + r, cy, p)
        c.drawLine(cx, cy, cx, cy + r, p)
        fill(0xFF1A2244.toInt()); c.drawCircle(cx, cy, r * 0.24f, p)
        stroke(withAlpha(ac, 220), 3f * density); c.drawCircle(cx, cy, r * 0.24f, p)
        fill(ac)
        c.drawRoundRect(RectF(cx - 5f * density, cy - r - 12f * density, cx + 5f * density, cy - r + 12f * density),
            4f * density, 4f * density, p)
        c.restore()
        text(c, "${wheelDeg.toInt()}°", cx, cy, r * 0.2f, Color.WHITE)
    }

    private fun drawPedal(c: Canvas, bt: Btn) {
        val r = bt.rect; val rad = 22f * density
        val col = if (bt.name == "RT") 0xFF3DDC84.toInt() else 0xFFFF5252.toInt()
        val frac = if (bt.name == "LT") ltFrac else rtFrac
        fill(0xFF121830.toInt())
        c.drawRoundRect(r, rad, rad, p)
        if (bt.down) {
            c.save()
            val clip = Path()
            clip.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, withAlpha(col, 80), withAlpha(col, 235), Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.top, r.right, r.top + r.height() * max(frac, 0.08f), p)
            p.shader = null
            c.restore()
        }
        stroke(withAlpha(Color.WHITE, 22), 2f * density)
        for (i in 1..9) {
            val y = r.top + r.height() * i / 10f
            c.drawLine(r.left + 14f * density, y, r.right - 14f * density, y, p)
        }
        stroke(withAlpha(col, if (bt.down) 255 else 170), 3f * density)
        c.drawRoundRect(r, rad, rad, p)
        text(c, bt.label, r.centerX(), r.top + r.height() * 0.07f, r.width() * 0.24f, Color.WHITE)
        text(c, "▼", r.centerX(), r.top + r.height() * 0.14f, r.width() * 0.22f, withAlpha(col, 210))
        if (bt.down && pedalAnalog && !racingKb) {
            text(c, "${((0.35f + 0.65f * frac) * 100).toInt()}%", r.centerX(), r.bottom - r.height() * 0.06f, r.width() * 0.26f, Color.WHITE)
        }
    }

    private fun drawTouchpad(c: Canvas, ac: Int) {
        val r = tpRect; val rad = 28f * density
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, 0xFF1A2244.toInt(), 0xFF0C1126.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        stroke(withAlpha(ac, 170), 3f * density)
        c.drawRoundRect(r, rad, rad, p)
        stroke(withAlpha(Color.WHITE, 14), 1f * density)
        for (i in 1..5) { val x = r.left + r.width() * i / 6f; c.drawLine(x, r.top + rad * 0.5f, x, r.bottom - rad * 0.5f, p) }
        for (i in 1..3) { val y = r.top + r.height() * i / 4f; c.drawLine(r.left + rad * 0.5f, y, r.right - rad * 0.5f, y, p) }
        text(c, "TOUCHPAD", r.centerX(), r.centerY() - 14f * density, 22f * density, withAlpha(ac, 110))
        text(c, "tap = click  ·  2 fingers = scroll / right-click", r.centerX(), r.centerY() + 14f * density, 13f * density, withAlpha(Color.WHITE, 100))
        for (id in 0 until 32) {
            if (!tpIds[id]) continue
            fill(withAlpha(ac, 70)); c.drawCircle(tpLastX[id], tpLastY[id], 34f * density, p)
            stroke(withAlpha(ac, 220), 3f * density); c.drawCircle(tpLastX[id], tpLastY[id], 34f * density, p)
        }
        for (bt in tpBtns) drawPill(c, bt, ac)
    }
}
