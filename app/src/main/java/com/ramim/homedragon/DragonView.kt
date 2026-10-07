package com.ramim.homedragon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = (2.0 * PI).toFloat()
private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
private fun clampF(v: Float, a: Float, b: Float) = max(a, min(b, v))
private fun lerp3(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun deg(r: Float) = r * 57.29578f

/**
 * Behaviour and effects. Icons are the only ground: the dragon perches on an icon, then either jumps
 * to a neighbouring icon, flies a free random path through the screen and lands on an icon, or
 * hovers and breathes blue fire at an icon (which gets scorched and slowly cools down).
 *
 * Frames come from Choreographer. pause() removes the callback and keeps everything in memory.
 */
class DragonView(context: Context) : View(context) {

    private enum class Mode { IDLE, CROUCH, FLY, FIRE, WALK, SLEEP, SCRATCH }

    private class Icon(val r: RectF, val row: Int) {
        var burn = 0f; var heat = 0f; var ember = 0f; var sm = 0f; var lk = 0f; var em = 0f
        var big = false          // widget or large folder: bigger ground the dragon can crawl on
        val scorch = Array(4) { floatArrayOf(rnd(-.28f, .28f), rnd(-.28f, .28f), rnd(.28f, .5f), rnd(0f, TAU)) }
        val cx: Float get() = r.centerX()
        val cy: Float get() = r.centerY()
    }

    private class FlightPath(val x: FloatArray, val y: FloatArray, val cum: FloatArray) {
        val len: Float get() = cum[cum.size - 1]
    }

    // ---------- icons ----------
    private var icons: List<Icon> = emptyList()
    private var size = 120f
    private var sc = 2f
    private var ds = 1.2f
    private var offX = 0
    private var offY = 0
    private var usingRegistry = false
    private var placed = false

    // ---------- dragon ----------
    private val model = DragonModel()
    private val hl = FloatArray(2)
    private val st = DragonModel.State()
    private var mode = Mode.IDLE
    private var air = false
    private var iconIdx = 0
    private var faceT = 1f
    private var t = 0f
    private var dur = 1f
    private var path: FlightPath? = null
    private var then: (() -> Unit)? = null
    private var timer = 1.5f
    private var blinkT = 0f
    private var nextBlink = 2f
    private var look = 0f
    private var lookT = 0f
    private var nextLook = 2f
    private var target = 0
    private var pendKind: String? = null
    private var pendIdx = -1
    private var acc = 0f
    private var walkTo = 0f
    private var fireDur = 1.7f
    private var sleepPhase = 0          // 0 lying down, 1 asleep, 2 getting up
    private var sleepDur = 4f           // seconds asleep (never more than 6)
    private var sleepAfterWalk = false  // a few steps first, then lie down
    private var scratchDur = 2.5f
    private val spot = FloatArray(2)    // sleeping place: [facing, x]
    private var vx = 0f
    private var vy = 0f
    private var headGoal = 0.3f
    private var time = 0f
    private val mp = FloatArray(2)
    private val pp = FloatArray(2)

    // ---------- particles (parallel arrays) ----------
    private val maxF = 200
    private val fX = FloatArray(maxF); private val fY = FloatArray(maxF)
    private val fVx = FloatArray(maxF); private val fVy = FloatArray(maxF)
    private val fPx = FloatArray(maxF); private val fPy = FloatArray(maxF)
    private val fAge = FloatArray(maxF); private val fLife = FloatArray(maxF)
    private val fSize = FloatArray(maxF); private val fSeed = FloatArray(maxF); private val fKind = IntArray(maxF)
    private var nf = 0

    private val maxS = 60
    private val sX = FloatArray(maxS); private val sY = FloatArray(maxS)
    private val sVx = FloatArray(maxS); private val sVy = FloatArray(maxS)
    private val sAge = FloatArray(maxS); private val sLife = FloatArray(maxS); private val sSize = FloatArray(maxS)
    private val sRot = FloatArray(maxS); private val sRv = FloatArray(maxS); private val sLit = FloatArray(maxS)
    private val sSeed = FloatArray(maxS); private val sVar = IntArray(maxS)
    private var ns = 0

    private val maxP = 90
    private val kX = FloatArray(maxP); private val kY = FloatArray(maxP)
    private val kVx = FloatArray(maxP); private val kVy = FloatArray(maxP)
    private val kAge = FloatArray(maxP); private val kLife = FloatArray(maxP); private val kG = FloatArray(maxP)
    private var nk = 0

    // ---------- paints and sprites ----------
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; blendMode = BlendMode.PLUS }
    private val dest = RectF()
    private val clip = Path()

    private fun glow(r: Int, g: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            32f, 32f, 32f,
            intArrayOf(Color.argb(255, r, g, b), Color.argb(199, r, g, b), Color.argb(71, r, g, b), Color.argb(0, r, g, b)),
            floatArrayOf(0f, 0.25f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(bmp).drawRect(0f, 0f, 64f, 64f, p)
        return bmp
    }

    // ---- value-noise textures (built once, so drawing stays as cheap as before) ----
    private fun hashf(x: Int, y: Int, sd: Int): Float {
        var h = x * 374761393 + y * 668265263 + sd * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun vnoise(x: Float, y: Float, sd: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = x - xi; val yf = y - yi
        val u = xf * xf * (3f - 2f * xf); val v = yf * yf * (3f - 2f * yf)
        val a = hashf(xi, yi, sd); val b = hashf(xi + 1, yi, sd)
        val c = hashf(xi, yi + 1, sd); val d = hashf(xi + 1, yi + 1, sd)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    private fun fbm(x0: Float, y0: Float, sd: Int): Float {
        var x = x0; var y = y0; var t = 0f; var amp = 0.5f; var n = 0f
        for (o in 0 until 4) { t += vnoise(x, y, sd + o) * amp; n += amp; amp *= 0.5f; x = x * 2.03f + 17.1f; y = y * 2.03f + 9.7f }
        return t / n
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = clampF((x - a) / (b - a), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Wispy flame blob: soft falloff broken up by warped noise, hot core brightened. */
    private fun flameSprite(r: Int, g: Int, b: Int, sd: Int): Bitmap {
        val n = 96
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f) / n * 2f - 1f; val dy = (y + 0.5f) / n * 2f - 1f
            val wx = dx + (fbm(x / 18f, y / 18f, sd) - 0.5f) * 0.55f
            val wy = dy + (fbm(x / 18f + 31f, y / 18f + 7f, sd + 9) - 0.5f) * 0.55f
            val d = sqrt(wx * wx + wy * wy)
            val base = smooth(1f, 0f, d).pow(1.6f)
            val nn = fbm(x / 11f + sd, y / 11f, sd + 3)
            val a = clampF(base * (0.82f + 0.9f * (nn - 0.5f)), 0f, 1f)
            val hot = smooth(0.55f, 0f, d) * smooth(0.3f, 0.7f, nn)
            val cr = min(255f, r + (255 - r) * 0.35f * hot).toInt()
            val cg = min(255f, g + (255 - g) * 0.35f * hot).toInt()
            val cb = min(255f, b + (255 - b) * 0.35f * hot).toInt()
            px[y * n + x] = Color.argb((a * 255f).toInt(), cr, cg, cb)
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }

    /** Billowy smoke puff with a lit top-left edge and a darker underside. */
    private fun puff(r: Int, g: Int, b: Int, al: Float, sd: Int): Bitmap {
        val n = 128
        val dens = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f) / n * 2f - 1f; val dy = (y + 0.5f) / n * 2f - 1f
            val wx = dx + (fbm(x / 26f, y / 26f, sd) - 0.5f) * 0.7f
            val wy = dy + (fbm(x / 26f + 5f, y / 26f + 3f, sd + 7) - 0.5f) * 0.7f
            val d = sqrt(wx * wx + wy * wy)
            val v = smooth(1f, 0.2f, d) * (0.45f + 0.75f * fbm(x / 14f, y / 14f, sd + 2))
            dens[y * n + x] = 1f - kotlin.math.exp(-1.6f * v)
        }
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dv = dens[y * n + x]
            val o = dens[max(0, y - 3) * n + max(0, x - 3)]
            val lit = clampF(1f + 2.2f * (dv - o), 0.7f, 1.6f)
            px[y * n + x] = Color.argb(
                (clampF(dv * al * 1.8f, 0f, 1f) * 255f).toInt(),
                min(255, (r * lit).toInt()), min(255, (g * lit).toInt()), min(255, (b * lit).toInt())
            )
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }

    // smooth glows: scorch marks, icon halos, muzzle flash
    private var sCore = glow(238, 249, 255)
    private var sCyan = glow(95, 214, 255)
    private var sBlue = glow(48, 110, 255)
    private val sBlack = glow(0, 0, 0)
    // wispy flame particles, two noise variants per colour stage
    private var fCore = Array(2) { flameSprite(238, 249, 255, 11 + it * 5) }
    // one white flame sprite per variant, tinted along a smooth colour ramp with a colour filter
    private val fWhite = Array(2) { flameSprite(255, 255, 255, 11 + it * 5) }
    private val rampStops = arrayOf(
        intArrayOf(0, 215, 240, 255), intArrayOf(18, 120, 225, 255), intArrayOf(45, 55, 130, 255),
        intArrayOf(75, 78, 60, 230), intArrayOf(100, 42, 24, 150)
    )
    private var ramp = Array(16) { k ->
        val t = k / 15f * 100f
        var j = 0
        while (j < rampStops.size - 2 && t > rampStops[j + 1][0]) j++
        val a = rampStops[j]; val b = rampStops[j + 1]
        val u = clampF((t - a[0]) / (b[0] - a[0]).toFloat(), 0f, 1f)
        PorterDuffColorFilter(
            Color.rgb((a[1] + (b[1] - a[1]) * u).toInt(), (a[2] + (b[2] - a[2]) * u).toInt(), (a[3] + (b[3] - a[3]) * u).toInt()),
            PorterDuff.Mode.SRC_IN
        )
    }
    // soft white-hot patches (brightest in the middle, nothing at the edges): tinted by the cooling colour
    private fun heatTex(sd: Int): Array<Bitmap> {
        val n = 96
        fun layer(seed: Int): Bitmap {
            val px = IntArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val dx = (x + 0.5f) / n * 2f - 1f; val dy = (y + 0.5f) / n * 2f - 1f
                val centre = smooth(1.05f, 0.05f, max(abs(dx), abs(dy)) * 0.55f + sqrt(dx * dx + dy * dy) * 0.6f)
                val wx = x + (fbm(x / 22f, y / 22f, seed + 4) - 0.5f) * 16f
                val wy = y + (fbm(x / 22f + 9f, y / 22f, seed + 5) - 0.5f) * 16f
                val v = fbm(wx / 12f, wy / 12f, seed + 1)
                val al = clampF(centre * (0.35f + 1.1f * smooth(0.3f, 0.8f, v)), 0f, 1f)
                px[y * n + x] = Color.argb((al * 255f).toInt(), 255, 255, 255)
            }
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            b.setPixels(px, 0, n, 0, 0, n, n)
            return b
        }
        return arrayOf(layer(sd), layer(sd + 40))
    }
    private val burnTexs = Array(3) { heatTex(5 + it * 7) }
    private val smkDark = Array(3) { puff(34, 36, 44, 0.34f, 21 + it * 4) }
    private val smkMid = Array(3) { puff(112, 118, 130, 0.3f, 21 + it * 4) }
    private var smkBlue = Array(2) { puff(70, 120, 200, 0.34f, 21 + it * 4) }

    // ---------- flame colours (picked in the app; the default is the original blue) ----------
    private var flameApplied: IntArray = FlameColors.DEFAULT.copyOf()

    /** Rebuild the colour ramp and tinted glows when the colours chosen in the app changed. */
    fun reloadFlame() {
        val cols = Prefs.flameColors(context)
        if (FlameColors.same(cols, flameApplied)) return
        flameApplied = cols
        ramp = Array(16) { k -> PorterDuffColorFilter(FlameColors.at(cols, k / 15f * 100f), PorterDuff.Mode.SRC_IN) }
        if (FlameColors.isDefault(cols)) {
            sCore = glow(238, 249, 255); sCyan = glow(95, 214, 255); sBlue = glow(48, 110, 255)
            fCore = Array(2) { flameSprite(238, 249, 255, 11 + it * 5) }
            smkBlue = Array(2) { puff(70, 120, 200, 0.34f, 21 + it * 4) }
        } else {
            val core = FlameColors.lighten(FlameColors.at(cols, 0f), 0.75f)
            val near = FlameColors.at(cols, 18f); val mid = FlameColors.at(cols, 50f)
            sCore = glow(Color.red(core), Color.green(core), Color.blue(core))
            sCyan = glow(Color.red(near), Color.green(near), Color.blue(near))
            sBlue = glow(Color.red(mid), Color.green(mid), Color.blue(mid))
            fCore = Array(2) { flameSprite(Color.red(core), Color.green(core), Color.blue(core), 11 + it * 5) }
            val sm = FlameColors.lighten(mid, 0.1f)
            smkBlue = Array(2) { puff(Color.red(sm) * 3 / 4, Color.green(sm) * 3 / 4, Color.blue(sm) * 3 / 4, 0.34f, 21 + it * 4) }
        }
        invalidate()
    }

    init { reloadFlame() }

    // ---------- page swipe fade ----------
    private var fade = 1f
    private var fadeGoal = 1f
    private var swipeState = 0       // 0 idle, 1 swiping, 2 settled and waiting for the new page's icons
    private var swipeLast = 0L
    private var settleAt = 0L

    private var hiddenByApp = false
    private var hiddenNotified = false
    /** Called once the dragon has faded out because an app (not the home screen) is in front. */
    var onFullyHidden: (() -> Unit)? = null

    /** false while another app is in front: fade out, then the service stops the frame loop. */
    fun setShown(shown: Boolean) {
        if (shown == !hiddenByApp) return
        hiddenByApp = !shown
        hiddenNotified = false
        if (shown) {
            // back on the home screen: wait for the fresh icon layout, then fade in (see applyIcons)
            swipeState = 2; settleAt = SystemClock.uptimeMillis(); fadeGoal = 0f
        }
    }

    /** The launcher is scrolling sideways: fade out fast. */
    fun onSwipe() {
        swipeLast = SystemClock.uptimeMillis()
        if (swipeState != 1) { swipeState = 1; fadeGoal = 0f }
    }

    // ---------- frame loop ----------
    private var running = false
    private var lastNs = 0L
    private val prevBox = Rect()
    private val curBox = Rect()

    /** true when the last callback was posted for the very next vsync (so the spacing of the next two callbacks is one vsync) */
    private var chain = false
    private var dueNs = 0L          // when the next frame is due (nanoseconds, vsync clock)

    private val kick = Runnable {
        if (running) { chain = false; Choreographer.getInstance().postFrameCallback(frame) }
    }

    /** Wait for the next wanted frame. Long waits sleep on a timer, so the CPU is not woken on every vsync. */
    private fun scheduleNext(remaining: Float) {
        if (remaining > 2.5f * vsyncDt) {
            chain = false
            postDelayed(kick, ((remaining - 0.5f * vsyncDt) * 1000f).toLong())
        } else {
            chain = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private val frame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // measure the real vsync spacing (only from callbacks that were posted for consecutive vsyncs)
            if (chain && lastVsyncNs != 0L) {
                val d = (frameTimeNanos - lastVsyncNs) / 1e9f
                if (d in 0.0035f..0.04f) vsyncDt += (d - vsyncDt) * 0.05f
            }
            lastVsyncNs = frameTimeNanos
            if (lastNs != 0L && frameTimeNanos < dueNs - 300_000L) {
                scheduleNext((dueNs - frameTimeNanos) / 1e9f)          // not due yet: nothing drawn
                return
            }
            val dt = if (lastNs == 0L) 0f else min(0.15f, (frameTimeNanos - lastNs) / 1e9f)
            lastNs = frameTimeNanos
            if (dt > 0f) {
                step(dt)
                invalidateDirty()
            }
            // completely sitting (sitting animation finished, no fire, glow or smoke) for 2 frames -> drop to 30 fps at any Quality;
            // any activity brings the full rate back at once
            if (idleNow) { if (idleFrames < 1000) idleFrames++ } else idleFrames = 0
            restCalm = idleFrames >= SIT_FRAMES
            rateNow = pickRate()
            val wantNs = (1e9f / rateNow).toLong()
            // keep the average rate exact: the next due time advances by one interval (a vsync is skipped now and then)
            dueNs = if (dueNs == 0L || frameTimeNanos - dueNs > wantNs) frameTimeNanos + wantNs else dueNs + wantNs
            scheduleNext((dueNs - frameTimeNanos) / 1e9f)
        }
    }

    fun resume() {
        if (running) return
        running = true
        lastNs = 0L
        lastVsyncNs = 0L
        dueNs = 0L; idleFrames = 0; restCalm = false
        try { display?.refreshRate?.let { if (it in 30f..165f) vsyncDt = 1f / it } } catch (e: Throwable) {}
        rateNow = pickRate()
        chain = false
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun pause() {
        running = false
        removeCallbacks(kick)
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    // ---------- layout and icons ----------
    override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
        super.onLayout(changed, l, top, r, b)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        offX = loc[0]
        offY = loc[1]
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!usingRegistry) applyIcons(fallbackRects())
    }

    fun reloadPrefs() {
        loadSettings()
        if (!usingRegistry) applyIcons(fallbackRects()) else applyScale()
    }

    /** Called with screen-pixel rectangles from the icon finder. Empty list means use the grid fallback. */
    fun setIcons(screenRects: List<RectF>) {
        if (screenRects.isEmpty()) {
            usingRegistry = false
            if (width > 0) applyIcons(fallbackRects())
            return
        }
        usingRegistry = true
        applyIcons(screenRects.map { RectF(it.left - offX, it.top - offY, it.right - offX, it.bottom - offY) })
    }

    private fun fallbackRects(): List<RectF> {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return emptyList()
        val cols = Prefs.cols(context)
        val rows = Prefs.rows(context)
        val top = h * 0.10f
        val bottom = h * 0.84f
        val cw = w / cols
        val ch = (bottom - top) / rows
        val s = min(cw * 0.62f, ch * 0.55f)
        val list = ArrayList<RectF>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val cx = (c + 0.5f) * cw
            val yy = top + r * ch + (ch - s * 1.25f) / 2f
            list.add(RectF(cx - s / 2, yy, cx + s / 2, yy + s))
        }
        for (c in 0 until cols) {
            val cx = (c + 0.5f) * cw
            list.add(RectF(cx - s / 2, h * 0.89f, cx + s / 2, h * 0.89f + s))
        }
        return list
    }

    private fun medianWidth(list: List<Float>): Float {
        val s = list.sorted()
        return s[s.size / 2]
    }

    // ---------- user settings (percent sliders in the app) ----------
    private var pq = 1f             // particle quality 0.1..1 (fire, smoke, sparks), separate from the frame-rate Quality
    private var q = 1f              // quality 0.1..1
    private var spdMul = 1f         // dragon speed 0.5..1.5
    private var capF = 200
    private var capS = 60
    private var capP = 90
    // frame pacing: the same rate in every state = screen refresh rate x Quality (100% -> 120 fps, 50% -> 60 fps, 10% -> 12 fps)
    private var rateNow = 120f
    private var idleFrames = 0
    private var restCalm = false
    private val SIT_FRAMES = 2
    private var idleNow = false
    private var vsyncDt = 1f / 60f
    private var lastVsyncNs = 0L

    private fun loadSettings() {
        q = clampF(Prefs.qualityPct(context) / 100f, 0.1f, 1f)
        pq = clampF(Prefs.particlePct(context) / 100f, 0.1f, 1f)
        spdMul = clampF(Prefs.speedPct(context) / 100f, 0.5f, 1.5f)
        capF = (70 + 130 * pq).toInt().coerceIn(70, maxF)
        capS = (20 + 40 * pq).toInt().coerceIn(20, maxS)
        capP = (30 + 60 * pq).toInt().coerceIn(30, maxP)
        rateNow = pickRate()
        reloadFlame()
    }

    /** Frames per second wanted right now: refresh rate x Quality, the same in every state. */
    private fun pickRate(): Float {
        val hz = clampF(1f / vsyncDt, 30f, 165f)
        val base = if (q >= 0.995f) 1000f else max(5f, hz * q)     // 100% = every vsync, no throttling
        return if (restCalm) min(base, 30f) else base
    }

    /** Quality or speed slider moved: nothing to re-layout. */
    fun reloadSettings() = loadSettings()

    private fun applyScale() {
        loadSettings()
        size = medianWidth(icons.map { it.r.width() })
        for (c in icons) c.big = c.r.width() > size * 1.5f
        sc = size / 58f
        ds = sc * 0.55f * (Prefs.scalePct(context) / 100f)
        st.ds = ds
    }

    private fun applyIcons(rects: List<RectF>) {
        if (rects.isEmpty()) return
        if (swipeState == 1) return          // mid-swipe layouts are half off screen: ignore them
        val settling = swipeState == 2
        val sorted = rects.sortedWith(compareBy({ it.centerY() }, { it.centerX() }))
        // The icon finder reports the same layout many times, so skip identical lists.
        if (sorted.size == icons.size && sorted.indices.all {
                abs(sorted[it].left - icons[it].r.left) < 1.5f && abs(sorted[it].top - icons[it].r.top) < 1.5f
            }
        ) {
            if (settling) { swipeState = 0; fadeGoal = 1f }   // same page again: fade back in where it was
            return
        }

        val avg = medianWidth(rects.map { it.width() })
        var row = 0
        var rowCy = sorted[0].centerY()
        val newIcons = ArrayList<Icon>()
        var matched = 0
        for (r in sorted) {
            if (r.centerY() - rowCy > avg * 0.5f) { row++; rowCy = r.centerY() }
            val ic = Icon(RectF(r), row)
            icons.firstOrNull { abs(it.cx - ic.cx) < avg * 0.3f && abs(it.cy - ic.cy) < avg * 0.3f }?.let {
                ic.burn = it.burn; ic.heat = it.heat; ic.ember = it.ember
                matched++
            }
            newIcons.add(ic)
        }
        icons = newIcons
        applyScale()
        val pageChanged = placed && icons.size >= 4 && matched < newIcons.size * 0.4f
        if (!placed) {
            placed = true
            land(Random.nextInt(icons.size), true)
        } else if (settling || pageChanged) {
            // A different home screen: the dragon appears on one of its icons with a fade-in.
            nf = 0; ns = 0; nk = 0
            land(Random.nextInt(icons.size), true)
            if (!settling) fade = 0f         // launcher sent no scroll events: still hide the jump
            swipeState = 0; fadeGoal = 1f
        } else if (mode == Mode.IDLE || mode == Mode.CROUCH || mode == Mode.SLEEP || mode == Mode.SCRATCH) {
            val ni = nearestIcon(st.x, st.y)
            val nc = ic(ni)
            land(ni, true, if (nc.big) clampF(st.x, nc.r.left + 16f, max(nc.r.left + 17f, nc.r.right - 16f)) else null)
        }
    }

    private fun nearestIcon(px: Float, py: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in icons.indices) {
            val r = icons[i].r
            val d = hypot(max(0f, max(r.left - px, px - r.right)), max(0f, max(r.top - py, py - r.bottom)))
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun ic(i: Int) = icons[i.coerceIn(0, icons.size - 1)]
    private fun sitX(i: Int) = ic(i).cx
    /** Where on an icon the dragon lands: the middle of a normal icon, anywhere along a big one. */
    private fun landX(i: Int): Float {
        val c = ic(i)
        if (!c.big) return c.cx
        val lo = max(c.r.left + 16f, 100f * ds * 0.9f); val hi = min(c.r.right - 16f, width - 100f * ds * 0.9f)
        return if (hi > lo) rnd(lo, hi) else c.cx
    }
    private fun sitY(i: Int) = ic(i).r.top + size * 0.06f

    // ---------- flight paths (Catmull-Rom through waypoints, then distance lookup) ----------
    private fun buildPath(px: List<Float>, py: List<Float>): FlightPath {
        val n = px.size
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until n - 1) {
            val i0 = max(i - 1, 0); val i3 = min(i + 2, n - 1)
            for (j in 0 until 16) {
                val tt = j / 16f; val t2 = tt * tt; val t3 = t2 * tt
                xs.add(0.5f * ((2 * px[i]) + (-px[i0] + px[i + 1]) * tt + (2 * px[i0] - 5 * px[i] + 4 * px[i + 1] - px[i3]) * t2 + (-px[i0] + 3 * px[i] - 3 * px[i + 1] + px[i3]) * t3))
                ys.add(0.5f * ((2 * py[i]) + (-py[i0] + py[i + 1]) * tt + (2 * py[i0] - 5 * py[i] + 4 * py[i + 1] - py[i3]) * t2 + (-py[i0] + 3 * py[i] - 3 * py[i + 1] + py[i3]) * t3))
            }
        }
        xs.add(px[n - 1]); ys.add(py[n - 1])
        val cum = FloatArray(xs.size)
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return FlightPath(xs.toFloatArray(), ys.toFloatArray(), cum)
    }

    private fun pathAt(p: FlightPath, s0: Float, out: FloatArray) {
        val s = clampF(s0, 0f, p.len)
        var i = 1
        while (i < p.cum.size - 1 && p.cum[i] < s) i++
        val a = p.cum[i - 1]; val b = p.cum[i]; val u = if (b > a) (s - a) / (b - a) else 0f
        out[0] = p.x[i - 1] + (p.x[i] - p.x[i - 1]) * u
        out[1] = p.y[i - 1] + (p.y[i] - p.y[i - 1]) * u
    }

    private fun waypoint(prevX: Float, prevY: Float): FloatArray {
        val mx = 158f * ds
        val top = height * 0.12f + 150f * ds
        val bot = height * 0.86f
        var best = floatArrayOf(width / 2f, height / 2f)
        var bd = -1f
        for (k in 0 until 8) {
            val p = floatArrayOf(rnd(mx, max(mx + 1f, width - mx)), rnd(top, max(top + 1f, bot)))
            val d = hypot(p[0] - prevX, p[1] - prevY)
            if (d > height * 0.28f) return p
            if (d > bd) { bd = d; best = p }
        }
        return best
    }

    // ---------- behaviour ----------
    private fun land(i: Int, silent: Boolean, x: Float? = null) {
        iconIdx = i.coerceIn(0, icons.size - 1)
        st.x = x ?: (if (silent && ic(iconIdx).big) landX(iconIdx) else sitX(iconIdx)); st.y = sitY(iconIdx)
        air = false; mode = Mode.IDLE; then = null; path = null
        st.rest = 0f; st.scratch = 0f; sleepAfterWalk = false
        timer = rnd(1.4f, 3.6f)
        // face the screen edge on outer columns so the tail stays on screen
        val cx = if (ic(iconIdx).big) st.x else ic(iconIdx).cx
        val tailM = 160f * ds
        faceT = if (ic(iconIdx).big) {
            if (cx < tailM) -1f else if (cx > width - tailM) 1f else (if (Random.nextBoolean()) 1f else -1f)
        } else if (cx < width * 0.3f) -1f else if (cx > width * 0.7f) 1f else (if (Random.nextBoolean()) 1f else -1f)
        if (silent) st.face = faceT else st.crouch = 0.9f
    }

    private fun launch(ex: Float, ey: Float, after: () -> Unit, hop: Boolean, wander: Boolean) {
        val sx = st.x; val sy = st.y
        val ceil = min(height * 0.12f + 105f * ds, min(sy, ey))
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        px.add(sx); py.add(sy)
        if (hop) {
            px.add((sx + ex) / 2f); py.add(max(ceil, min(sy, ey) - size * 1.05f))
        } else if (wander) {
            var qx = sx; var qy = sy
            val cnt = 1 + Random.nextInt(3)
            for (i in 0 until cnt) { val w = waypoint(qx, qy); px.add(w[0]); py.add(w[1]); qx = w[0]; qy = w[1] }
        } else {
            px.add((sx + ex) / 2f + rnd(-size, size)); py.add(max(ceil, min(sy, ey) - size * rnd(0.8f, 1.8f)))
        }
        px.add(ex); py.add(ey)
        val p = buildPath(px, py)
        path = p
        val speed = (if (hop) 265f else rnd(200f, 272f)) * sc
        dur = max(if (hop) 0.7f else 1.15f, p.len / speed)
        t = 0f; mode = Mode.FLY; air = true; then = after; st.wingPh = -(PI / 2).toFloat()
    }

    private fun takeOff(ex: Float, ey: Float, after: () -> Unit, hop: Boolean, wander: Boolean) {
        if (air) launch(ex, ey, after, hop, wander)
        else { mode = Mode.CROUCH; t = 0f; dur = 0.22f; then = { launch(ex, ey, after, hop, wander) } }
    }

    private fun neighbors(i: Int): List<Int> {
        val c = ic(i)
        fun gap(o: RectF) = hypot(max(0f, max(o.left - c.r.right, c.r.left - o.right)), max(0f, max(o.top - c.r.bottom, c.r.top - o.bottom)))
        return icons.indices.filter { it != i && gap(icons[it].r) < size * 1.7f }
    }

    private fun flyToIcon(j: Int, hop: Boolean) { val lx = landX(j); takeOff(lx, sitY(j), { land(j, false, lx) }, hop, !hop) }

    private fun startFire(j: Int, after: () -> Unit) {
        mode = Mode.FIRE; target = j; t = 0f; acc = 0f; fireDur = 1.7f
        faceT = if (ic(j).cx >= st.x) 1f else -1f
        then = after
    }

    private fun randIcon(except: Int): Int {
        if (icons.size < 2) return 0
        var j: Int
        do { j = Random.nextInt(icons.size) } while (j == except)
        return j
    }

    private fun afterAirFire(j: Int): () -> Unit = { flyToIcon(if (Random.nextFloat() < 0.55f) j else randIcon(j), false) }

    private fun fireAt(j: Int) {
        val c = ic(j)
        val near = !air && max(0f, max(c.r.left - st.x, st.x - c.r.right)) < size * 3.3f && c.cy - st.y > -size * 1.3f && c.cy - st.y < size * 3.2f
        if (near) {
            startFire(j) { mode = Mode.IDLE; timer = rnd(1.4f, 3f) }
        } else {
            val side = if (c.cx > width / 2f) -1f else 1f
            val f = -side
            val tailM = 160f * ds; val headM = 100f * ds
            val lo = if (f > 0) tailM else headM; val hi = width - (if (f > 0) headM else tailM)
            val hx = clampF(c.cx + side * size * 2.5f, lo, max(lo + 1f, hi))
            val hy = clampF(c.cy - size * 1.15f, height * 0.12f + 150f * ds, height * 0.86f)
            takeOff(hx, hy, { startFire(j, afterAirFire(j)) }, false, true)
        }
    }

    private fun startWalk(): Boolean {
        val c = ic(iconIdx)
        val lo = max(c.r.left + 16f, 100f * ds * 0.9f); val hi = min(c.r.right - 16f, width - 100f * ds * 0.9f)
        if (!(hi - lo > 80f * ds)) return false
        var tx = st.x; var tries = 0
        while (abs(tx - st.x) < 60f * ds && tries++ < 8) tx = rnd(lo, hi)
        walkTo = clampF(tx, lo, hi); faceT = if (walkTo > st.x) 1f else -1f; mode = Mode.WALK
        return true
    }

    /**
     * Where a lying dragon fits on the current large surface: head and body on it, only the tail may hang over the edge
     * (and the whole dragon stays on screen). Fills spot = [facing, x] and returns false when it does not fit or is too far to reach in a few steps.
     */
    private fun sleepSpot(): Boolean {
        val c = ic(iconIdx)
        if (!c.big || air) return false
        val head = 70f * ds + 4f; val tailEdge = 110f * ds; val tailScreen = 146f * ds + 4f
        val faces = floatArrayOf(faceT, -faceT)
        for (f in faces) {
            val lo: Float; val hi: Float
            if (f > 0f) { lo = max(c.r.left + tailEdge, tailScreen); hi = min(c.r.right - head, width - head) }
            else { lo = max(c.r.left + head, head); hi = min(c.r.right - tailEdge, width - tailScreen) }
            if (lo > hi) continue
            val x = clampF(st.x, lo, hi)
            if (abs(x - st.x) > 26f * sc * 3.4f) continue            // too far to walk there in about three seconds
            spot[0] = f; spot[1] = x
            return true
        }
        return false
    }

    /** Lie down on the spot found by [sleepSpot]; the dragon must already stand there. */
    private fun beginSleep(): Boolean {
        if (!sleepSpot() || abs(spot[1] - st.x) > 2f) return false
        faceT = spot[0]; mode = Mode.SLEEP; sleepPhase = 0; t = 0f; sleepDur = rnd(3f, 6f)
        return true
    }

    private fun startSleep() {
        if (!sleepSpot()) { doAction("fly", -1); return }
        val c = ic(iconIdx)
        var tx = spot[1]
        // sometimes a few extra steps before lying down (only when there is room)
        if (abs(tx - st.x) < 20f * ds && Random.nextFloat() < 0.5f) {
            val lo = max(c.r.left + 16f, 100f * ds * 0.9f); val hi = min(c.r.right - 16f, width - 100f * ds * 0.9f)
            val cand = clampF(st.x + rnd(-110f, 110f) * ds, lo, hi)
            val keepFace = faceT; val keepX = st.x
            st.x = cand; val ok = sleepSpot() && abs(spot[1] - cand) < 2f; st.x = keepX; faceT = keepFace
            if (ok) tx = cand
        }
        sleepSpot()
        if (abs(tx - st.x) < 2f) { if (!beginSleep()) doAction("fly", -1); return }
        walkTo = tx; faceT = if (tx > st.x) 1f else -1f; sleepAfterWalk = true; mode = Mode.WALK
    }

    private fun startScratch() {
        mode = Mode.SCRATCH; t = 0f; scratchDur = rnd(2.4f, 3.8f); st.scratchPh = 0f
    }

    private fun doAction(kind: String, idx: Int) {
        when (kind) {
            "sleep" -> startSleep()
            "scratch" -> startScratch()
            "walk" -> { if (!startWalk()) timer = rnd(1f, 2f) }
            "jump" -> {
                val nb = neighbors(iconIdx)
                if (nb.isEmpty()) return doAction("fly", -1)
                flyToIcon(if (idx >= 0) idx else nb.random(), true)
            }
            "fly" -> flyToIcon(if (idx >= 0) idx else randIcon(iconIdx), false)
            else -> fireAt(if (idx >= 0) idx else randIcon(iconIdx))
        }
    }

    private fun chooseNext() {
        val k = pendKind
        if (k != null) { pendKind = null; doAction(k, pendIdx); return }
        // each time it is bored it picks a mood at random; moods that do not fit here are left out
        val kinds = ArrayList<String>(); val w = ArrayList<Float>()
        if (ic(iconIdx).big) { kinds.add("walk"); w.add(0.26f) }
        if (sleepSpot()) { kinds.add("sleep"); w.add(0.2f) }
        kinds.add("scratch"); w.add(0.15f)
        kinds.add("jump"); w.add(0.13f)
        kinds.add("fly"); w.add(0.13f)
        kinds.add("fire"); w.add(0.22f)
        var r = Random.nextFloat() * w.sum()
        for (i in kinds.indices) { r -= w[i]; if (r <= 0f || i == kinds.size - 1) { doAction(kinds[i], -1); return } }
    }

    // ---------- effects ----------
    private fun emitFlame(mx: Float, my: Float, tx: Float, ty: Float) {
        if (nf >= capF) return
        val kind = if (Random.nextFloat() < 0.35f) 0 else 1
        val life = if (kind == 0) rnd(0.3f, 0.46f) else rnd(0.45f, 0.8f)
        // the particle should reach the icon centre near the end of its life: solve the launch speed
        // against the air drag (0.4 per second) and compensate the upward drift
        val arrive = life * rnd(0.74f, 0.86f)
        val lift = 0.5f * (90f + (if (kind == 1) 100f else 20f)) * sc * arrive * arrive
        val dx = tx - mx; val dy = ty + lift - my; val d = max(1f, hypot(dx, dy)); val ux = dx / d; val uy = dy / d
        val sp = clampF(d * 0.916f / (1f - 0.4f.pow(arrive)), 260f * sc, 1500f * sc)
        val a = (Random.nextFloat() - 0.5f) * (if (kind == 0) 0.05f else 0.16f)
        val cs = cos(a); val sn = sin(a)
        fX[nf] = mx; fY[nf] = my
        fVx[nf] = (ux * cs - uy * sn) * sp; fVy[nf] = (ux * sn + uy * cs) * sp
        fPx[nf] = -uy; fPy[nf] = ux
        fAge[nf] = 0f; fLife[nf] = life
        fSize[nf] = (if (kind == 0) rnd(8f, 12f) else rnd(13f, 22f)) * ds * 1.35f
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = kind
        nf++
    }

    private fun addSplash(x: Float, y: Float) {
        if (nf >= capF) return
        val a = rnd(0f, TAU); val sp = rnd(70f, 210f) * sc
        fX[nf] = x; fY[nf] = y
        fVx[nf] = cos(a) * sp; fVy[nf] = sin(a) * sp * 0.8f - rnd(0f, 50f) * sc
        fPx[nf] = 0f; fPy[nf] = 1f
        fAge[nf] = 0f; fLife[nf] = rnd(0.22f, 0.4f); fSize[nf] = rnd(7f, 12f) * ds * 1.3f
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = 2
        nf++
    }

    /** Small flame tongue rising off an icon that was just set alight. */
    private fun addLick(x: Float, y: Float, small: Boolean) {
        if (nf >= capF - 20) return
        fX[nf] = x; fY[nf] = y
        fVx[nf] = rnd(-14f, 14f) * sc; fVy[nf] = -rnd(70f, 150f) * sc * (if (small) 0.6f else 1f)
        fPx[nf] = 1f; fPy[nf] = 0f
        fAge[nf] = 0f; fLife[nf] = rnd(0.35f, 0.6f); fSize[nf] = rnd(7f, 11f) * ds * 1.3f * (if (small) 0.7f else 1f)
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = 3
        nf++
    }

    private fun addSmoke(x: Float, y: Float, lit: Float) {
        if (ns >= capS) return
        sX[ns] = x; sY[ns] = y
        sVx[ns] = rnd(-10f, 10f) * sc; sVy[ns] = -rnd(28f, 56f) * sc
        sAge[ns] = 0f; sLife[ns] = rnd(1.3f, 2.4f); sSize[ns] = rnd(10f, 17f) * sc
        sRot[ns] = rnd(0f, TAU); sRv[ns] = rnd(-0.7f, 0.7f); sLit[ns] = lit
        sSeed[ns] = rnd(0f, TAU); sVar[ns] = Random.nextInt(3)
        ns++
    }

    private fun addSpark(x: Float, y: Float, ang: Float, spd: Float) {
        if (nk >= capP) return
        kX[nk] = x; kY[nk] = y; kVx[nk] = cos(ang) * spd; kVy[nk] = sin(ang) * spd
        kAge[nk] = 0f; kLife[nk] = rnd(0.3f, 0.75f); kG[nk] = 1f
        nk++
    }

    /** Slow glowing ash that floats up off a burnt icon (negative kG marks a floater). */
    private fun addEmber(x: Float, y: Float) {
        if (nk >= capP - 10) return
        kX[nk] = x; kY[nk] = y; kVx[nk] = rnd(-14f, 14f) * sc; kVy[nk] = -rnd(22f, 60f) * sc
        kAge[nk] = 0f; kLife[nk] = rnd(1.2f, 2.8f); kG[nk] = -rnd(0.02f, 0.1f)
        nk++
    }

    // ---------- per-frame update ----------
    private fun step(rdt: Float) {
        val now = SystemClock.uptimeMillis()
        if (swipeState == 1 && now - swipeLast > 120L) { swipeState = 2; settleAt = now }
        else if (swipeState == 2 && now - settleAt > 450L) { swipeState = 0; fadeGoal = 1f }
        fade += clampF((if (hiddenByApp) 0f else fadeGoal) - fade, -rdt / 0.09f, rdt / 0.17f)
        if (hiddenByApp && fade <= 0.01f && !hiddenNotified) { hiddenNotified = true; post { onFullyHidden?.invoke() } }
        if (icons.isEmpty()) return
        time += rdt
        for (c in icons) {
            c.heat = max(0f, c.heat - rdt * 1.4f)
            c.ember = max(0f, c.ember - rdt * 0.1f)
            if (c.heat < 0.05f) c.burn = max(0f, c.burn - rdt * 0.03f)
            if (c.heat > 0.35f) {
                c.lk += rdt * 12f * c.heat
                while (c.lk >= 1f) { c.lk -= 1f; addLick(c.cx + rnd(-.35f, .35f) * c.r.width(), c.cy + rnd(-.2f, .3f) * c.r.height(), false) }
            }
            else if (c.ember > 0.45f && c.burn > 0.3f) {
                // small blue pilot flames keep flickering while the icon is still white hot
                c.lk += rdt * (c.ember - 0.4f) * 8f
                while (c.lk >= 1f) { c.lk -= 1f; addLick(c.cx + rnd(-.3f, .3f) * c.r.width(), c.cy + rnd(-.1f, .3f) * c.r.height(), true) }
            }
            if (c.ember > 0.12f && c.burn > 0.15f) {
                // glowing ash drifting up off the charred icon
                c.em += rdt * 5f * c.ember * pq
                while (c.em >= 1f) { c.em -= 1f; addEmber(c.r.left + rnd(.1f, .9f) * c.r.width(), c.r.top + rnd(.2f, .9f) * c.r.height()) }
            }
            if (c.burn > 0.2f) {
                c.sm += rdt * (0.4f + 3.2f * c.heat + 1.4f * c.ember) * c.burn * (0.3f + 0.7f * pq)
                while (c.sm >= 1f) { c.sm -= 1f; addSmoke(c.r.left + rnd(.2f, .8f) * c.r.width(), c.r.top + rnd(.05f, .4f) * c.r.height(), c.heat) }
            }
        }
        val dt = rdt * spdMul           // the dragon's own clock: slower or faster than real time
        nextBlink -= dt
        if (nextBlink < 0f) { blinkT = 0.14f; nextBlink = rnd(2f, 5f) }
        if (blinkT > 0f) blinkT -= dt
        st.eye = if (blinkT > 0f) 0.1f else if (st.scratch > 0.3f) 0.45f else 1f
        nextLook -= dt
        if (nextLook < 0f) { lookT = rnd(-0.14f, 0.12f); nextLook = rnd(1.2f, 3.2f) }
        look += (lookT - look) * min(1f, dt * 3f)

        st.sp += ((if (air) 1f else 0f) - st.sp) * min(1f, dt * 7f)
        st.walk += ((if (mode == Mode.WALK) 1f else 0f) - st.walk) * min(1f, dt * 6f)
        val flapHz = if (air) 2.2f + 0.7f * sin(time * 0.8f) + (if (mode == Mode.FIRE) 0.5f else 0f) else 0.5f
        st.wingPh += dt * TAU * flapHz
        st.crouch = if (mode == Mode.CROUCH) clampF(t / dur * 1.15f, 0f, 1f) else max(0f, st.crouch - dt * (if (air) 6f else 4.5f))

        val ox = st.x; val oy = st.y
        var firing = false
        when (mode) {
            Mode.IDLE -> { timer -= dt; if (timer <= 0f) chooseNext() }
            Mode.WALK -> {
                val spd = 26f * sc * st.walk * st.walk
                val dx = walkTo - st.x; val stp = min(abs(dx), spd * dt)
                st.x += (if (dx > 0f) stp else -stp); st.gait += stp / ds / 46.7f
                if (abs(walkTo - st.x) < 0.5f) {
                    mode = Mode.IDLE; timer = rnd(1.4f, 3.2f)
                    if (sleepAfterWalk) { sleepAfterWalk = false; if (!beginSleep()) timer = rnd(0.4f, 1f) }
                }
            }
            Mode.SLEEP -> {
                t += dt
                when (sleepPhase) {
                    0 -> if (abs(st.face - faceT) < 0.15f) {
                        st.rest = min(1f, st.rest + dt / 1.3f)
                        if (st.rest >= 1f) { sleepPhase = 1; t = 0f }
                    }
                    1 -> if (t >= sleepDur) { sleepPhase = 2; t = 0f }
                    else -> {
                        st.rest = max(0f, st.rest - dt / 1.2f)
                        if (st.rest <= 0f) { mode = Mode.IDLE; timer = rnd(0.6f, 1.6f) }
                    }
                }
            }
            Mode.SCRATCH -> {
                t += dt
                val raise = smooth(0f, 0.45f, min(t, scratchDur - t))
                st.scratch += (raise - st.scratch) * min(1f, dt * 12f)
                st.scratchPh += dt * TAU * 3f * smooth(0.3f, 0.8f, raise)
                if (t >= scratchDur) { mode = Mode.IDLE; timer = rnd(1f, 2.4f) }
            }
            Mode.CROUCH -> {
                t += dt
                if (t >= dur) { val cb = then; then = null; cb?.invoke() }
            }
            Mode.FLY -> {
                t += dt
                val e = min(1f, t / dur); val s = e * e * (3f - 2f * e)
                path?.let { pathAt(it, s * it.len, pp); st.x = pp[0]; st.y = pp[1] }
                if (t >= dur) { val cb = then; then = null; cb?.invoke() }
            }
            Mode.FIRE -> {
                firing = true
                t += dt
                val c = ic(target)
                st.mouth = clampF(min(t / 0.25f, (fireDur - t) / 0.25f), 0f, 1f)
                model.headLocal(st.sp, st.walk, hl); val hx = hl[0]; val hy = hl[1]
                val ang = atan2(c.cy - (st.y + (hy + st.bob) * ds), max(8f, abs(c.cx - (st.x + st.face * hx * ds))))
                headGoal = clampF(ang, -0.6f, 1.0f)
                if (t > 0.3f && t < fireDur - 0.25f) {
                    model.mouthPos(st, mp)
                    acc += dt * 110f * (0.45f + 0.55f * pq)
                    while (acc >= 1f) { acc -= 1f; emitFlame(mp[0], mp[1], c.cx, c.cy) }
                    if (Random.nextFloat() < dt * 14f) addSpark(mp[0], mp[1], atan2(c.cy - mp[1], c.cx - mp[0]) + rnd(-0.5f, 0.5f), rnd(180f, 420f) * sc)
                    c.burn = min(1f, c.burn + dt * 0.62f * (size / max(size, c.r.width() * 0.5f))); c.heat = min(1f, c.heat + dt * 4f); c.ember = 1f
                }
                if (t >= fireDur) { st.mouth = 0f; val cb = then; then = null; cb?.invoke() }
            }
        }
        if (!firing) st.mouth = max(0f, st.mouth - dt * 6f)
        if (mode != Mode.SCRATCH) st.scratch = max(0f, st.scratch - dt * 4f)
        val hg = if (firing) headGoal
        else if (st.scratch > 0.02f) lerp3(model.restHead(st.sp, st.walk) + look * (1f - st.sp), 0.45f + 0.05f * sin(st.scratchPh * 0.5f), st.scratch)
        else model.restHead(st.sp, st.walk) + look * (1f - st.sp)
        st.head += (hg - st.head) * min(1f, dt * 12f)

        val cvx = (st.x - ox) / dt; val cvy = (st.y - oy) / dt
        vx += (cvx - vx) * min(1f, dt * 10f); vy += (cvy - vy) * min(1f, dt * 10f)
        if (mode == Mode.FLY && abs(vx) > 30f * sc) faceT = if (vx > 0f) 1f else -1f
        st.face += (faceT - st.face) * min(1f, dt * 11f)
        val wp = if (mode == Mode.FLY) clampF(atan2(vy, max(90f * sc, abs(vx))) * 0.6f, -0.55f, 0.55f) else 0f
        st.pitch += (wp - st.pitch) * min(1f, dt * 7f)
        st.bob = if (air) sin(st.wingPh) * 3f - 4f * st.sp else sin(time * 2.3f) * 0.5f + sin(st.gait * TAU * 2f) * 1.3f * st.walk
        st.time = time; st.ds = ds
        // "completely sitting": the landing / crouch / wing-fold animation has finished too
        val settled = st.sp < 0.02f && st.walk < 0.02f && st.crouch < 0.02f && st.mouth < 0.02f &&
            abs(st.pitch) < 0.01f && abs(st.face - faceT) < 0.02f
        val asleep = mode == Mode.SLEEP && sleepPhase == 1 && st.rest >= 0.999f
        idleNow = settled && (mode == Mode.IDLE || mode == Mode.SCRATCH || asleep) && nf == 0 && ns == 0 && nk == 0 && fade >= 0.99f && fadeGoal >= 0.99f &&
            !hiddenByApp && icons.none { it.heat > 0.02f || it.ember > 0.02f }

        // flame particles
        val tgt = if (mode == Mode.FIRE) ic(target) else null
        var i = nf - 1
        while (i >= 0) {
            fAge[i] += rdt
            if (tgt != null && fKind[i] < 2) {
                // the stream lands in the middle of the icon: pool, splash and spray from there
                val ddx = fX[i] - tgt.cx; val ddy = fY[i] - tgt.cy
                val rr = min(size * 0.34f, min(tgt.r.width(), tgt.r.height()) * 0.3f)
                if (ddx * ddx + ddy * ddy < rr * rr) {
                    fAge[i] += rdt * 3.2f
                    val dmp = max(0f, 1f - rdt * 9f); fVx[i] *= dmp; fVy[i] *= dmp
                    if (Random.nextFloat() < 0.2f) {
                        addSplash(fX[i], fY[i])
                        if (Random.nextFloat() < 0.4f) addSpark(fX[i], fY[i], rnd(0f, TAU), rnd(120f, 360f) * sc)
                    }
                }
            }
            if (fAge[i] >= fLife[i]) {
                if (fKind[i] == 1 && Random.nextFloat() < 0.2f) addSmoke(fX[i], fY[i], 1f)
                nf--
                fX[i] = fX[nf]; fY[i] = fY[nf]; fVx[i] = fVx[nf]; fVy[i] = fVy[nf]; fPx[i] = fPx[nf]; fPy[i] = fPy[nf]
                fAge[i] = fAge[nf]; fLife[i] = fLife[nf]; fSize[i] = fSize[nf]; fSeed[i] = fSeed[nf]; fKind[i] = fKind[nf]
            } else {
                val tt = fAge[i] / fLife[i]
                val wob = sin(fAge[i] * 26f + fSeed[i]) * 300f * sc * tt * (if (fKind[i] == 1) 1f else 0.4f)
                fVx[i] += fPx[i] * wob * rdt; fVy[i] += fPy[i] * wob * rdt - (90f + (if (fKind[i] == 1) 200f * tt else 40f * tt)) * sc * rdt
                fX[i] += fVx[i] * rdt; fY[i] += fVy[i] * rdt
                val k = 0.4f.pow(rdt); fVx[i] *= k; fVy[i] *= k
            }
            i--
        }
        i = nk - 1
        while (i >= 0) {
            kAge[i] += rdt
            if (kAge[i] >= kLife[i]) {
                nk--
                kX[i] = kX[nk]; kY[i] = kY[nk]; kVx[i] = kVx[nk]; kVy[i] = kVy[nk]; kAge[i] = kAge[nk]; kLife[i] = kLife[nk]; kG[i] = kG[nk]
            } else {
                kVy[i] += 520f * sc * kG[i] * rdt
                if (kG[i] < 0f) {                      // floating ash: drag plus a lazy sideways drift
                    val dr = max(0f, 1f - rdt * 1.1f)
                    kVx[i] = kVx[i] * dr + sin(kAge[i] * 3.1f + kY[i] * 0.05f) * 40f * sc * rdt
                    kVy[i] *= dr
                }
                kX[i] += kVx[i] * rdt; kY[i] += kVy[i] * rdt
            }
            i--
        }
        i = ns - 1
        while (i >= 0) {
            sAge[i] += rdt
            if (sAge[i] >= sLife[i]) {
                ns--
                sX[i] = sX[ns]; sY[i] = sY[ns]; sVx[i] = sVx[ns]; sVy[i] = sVy[ns]; sAge[i] = sAge[ns]; sLife[i] = sLife[ns]
                sSize[i] = sSize[ns]; sRot[i] = sRot[ns]; sRv[i] = sRv[ns]; sLit[i] = sLit[ns]; sSeed[i] = sSeed[ns]; sVar[i] = sVar[ns]
            } else {
                val tt = sAge[i] / sLife[i]
                sVx[i] += (sin(sAge[i] * 2.3f + sSeed[i]) * 38f + sin(time * 0.7f + sY[i] * 0.012f) * 30f) * sc * rdt * (0.4f + tt)
                sVy[i] += (-(34f * sc) * (1f - tt * 0.5f) - sVy[i]) * min(1f, rdt * 1.2f)
                sX[i] += sVx[i] * rdt; sY[i] += sVy[i] * rdt; sRot[i] += sRv[i] * rdt
            }
            i--
        }
    }

    /** Redraw only the area that changed. */
    private fun invalidateDirty() {
        val pad = (175f * ds).toInt()
        curBox.set((st.x - pad).toInt(), (st.y - pad).toInt(), (st.x + pad).toInt(), (st.y + pad / 3).toInt())
        for (i in 0 until nf) { val s = (fSize[i] * 3.5f).toInt(); curBox.union((fX[i] - s).toInt(), (fY[i] - s).toInt(), (fX[i] + s).toInt(), (fY[i] + s).toInt()) }
        for (i in 0 until ns) { val s = (sSize[i] * 4.4f).toInt(); curBox.union((sX[i] - s).toInt(), (sY[i] - s).toInt(), (sX[i] + s).toInt(), (sY[i] + s).toInt()) }
        for (i in 0 until nk) curBox.union((kX[i] - 20).toInt(), (kY[i] - 20).toInt(), (kX[i] + 20).toInt(), (kY[i] + 20).toInt())
        for (c in icons) {
            if (c.burn > 0.01f || c.heat > 0.02f) {
                val g = (size * 0.7f).toInt()
                curBox.union(c.r.left.toInt() - g, c.r.top.toInt() - g, c.r.right.toInt() + g, c.r.bottom.toInt() + g)
            }
        }
        val inv = Rect(curBox)
        inv.union(prevBox)
        prevBox.set(curBox)
        invalidate(inv)
    }

    // ---------- drawing ----------
    override fun onDraw(c: Canvas) {
        if (icons.isEmpty() || fade <= 0.01f) return
        val layer = fade < 0.995f
        val saved = if (layer) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (fade * 255f).toInt()) else 0
        for (ic in icons) if (ic.burn > 0.01f || ic.heat > 0.02f) drawBurn(c, ic)
        drawSmoke(c)
        model.draw(c, st)
        drawFlame(c)
        if (layer) c.restoreToCount(saved)
    }

    private fun sprite(c: Canvas, b: Bitmap, cx: Float, cy: Float, half: Float, paint: Paint) {
        dest.set(cx - half, cy - half, cx + half, cy + half)
        c.drawBitmap(b, null, dest, paint)
    }

    private fun drawBurn(c: Canvas, ic: Icon) {
        val r = ic.r
        // how hot the icon still is: 1 right after the fire, easing to 0 over about 10 s
        val gl = ic.ember * min(1f, ic.burn * 2f)
        // soft bloom around the icon: strong while the fire hits, a gentle breathing afterglow after
        val bloom = max(ic.heat * 0.8f, gl * 0.26f)
        if (bloom > 0.02f && pq > 0.25f) {
            plusPaint.alpha = (bloom * (0.9f + 0.1f * sin(time * 9f + ic.cx)) * 255f).toInt()
            dest.set(r.left - size * 0.55f, r.top - size * 0.55f, r.right + size * 0.55f, r.bottom + size * 0.55f)
            c.drawBitmap(sBlue, null, dest, plusPaint)
        }
        if (gl <= 0.02f && ic.heat <= 0.02f) return
        val tex = burnTexs[(ic.scorch[0][3] * 0.47f).toInt().coerceIn(0, 2)]
        val flip = if (ic.scorch[1][3] > PI) -1f else 1f
        c.save()
        clip.reset()
        clip.addRoundRect(r, size * 0.24f, size * 0.24f, Path.Direction.CW)
        c.clipPath(clip)
        // white-hot at first, then cyan, then deep blue as it cools; only ever adds light, never darkens
        val g1 = gl * gl * (3f - 2f * gl)
        if (gl > 0.02f) {
            val k = 0.5f + 0.5f * sin(time * 2.6f + ic.cx * 0.01f)
            plusPaint.colorFilter = ramp[((1f - g1) * 11f).toInt().coerceIn(0, 11)]
            c.save()
            c.scale(flip, 1f, ic.cx, ic.cy)
            dest.set(r.left - r.width() * 0.04f, r.top - r.height() * 0.04f, r.right + r.width() * 0.04f, r.bottom + r.height() * 0.04f)
            if (pq > 0.5f) {
                plusPaint.alpha = (g1 * (0.3f + 0.4f * k) * 255f).toInt()
                c.drawBitmap(tex[0], null, dest, plusPaint)
                plusPaint.alpha = (g1 * (0.7f - 0.4f * k) * 255f).toInt()
                c.drawBitmap(tex[1], null, dest, plusPaint)
            } else {
                plusPaint.alpha = (g1 * 0.5f * 255f).toInt()
                c.drawBitmap(tex[0], null, dest, plusPaint)
            }
            c.restore()
            plusPaint.colorFilter = null
            // warm heart of the icon
            val fl = 0.85f + 0.15f * sin(time * 17f + ic.cy * 0.03f)
            plusPaint.alpha = (g1 * 0.5f * fl * 255f).toInt()
            sprite(c, sCyan, ic.cx, ic.cy, size * 0.62f, plusPaint)
        }
        if (ic.heat > 0.02f) {
            // impact: bright blue-white spot where the stream lands
            plusPaint.alpha = (ic.heat * 0.9f * 255f).toInt()
            sprite(c, sCore, ic.cx, ic.cy, size * 0.5f * (0.7f + 0.3f * ic.heat), plusPaint)
        }
        c.restore()
    }

    private fun drawSmoke(c: Canvas) {
        for (i in 0 until ns) {
            val tt = sAge[i] / sLife[i]
            val s = sSize[i] * (1f + tt * 3.2f)
            val env = min(1f, tt * 7f) * (1f - tt).pow(1.5f)
            c.save(); c.translate(sX[i], sY[i]); c.rotate(deg(sRot[i]))
            spritePaint.alpha = (env * 0.3f * (1f - tt * 0.7f) * 255f).toInt()
            dest.set(-s, -s, s, s); c.drawBitmap(smkDark[sVar[i]], null, dest, spritePaint)
            if (tt > 0.2f) {
                spritePaint.alpha = (env * 0.6f * tt * 255f).toInt()
                c.drawBitmap(smkMid[sVar[i]], null, dest, spritePaint)
            }
            val lit = sLit[i] * max(0f, 1f - sAge[i] / 0.45f)
            if (lit > 0.15f && pq > 0.4f) {
                plusPaint.alpha = (env * lit * 0.55f * 255f).toInt()
                c.drawBitmap(smkBlue[sVar[i] % 2], null, dest, plusPaint)
            }
            c.restore()
        }
    }

    private fun drawFlame(c: Canvas) {
        for (i in 0 until nf) {
            val tt = fAge[i] / fLife[i]
            val kind = fKind[i]
            // grow quickly, then thin out at the end
            val s = fSize[i] * (0.5f + tt * (if (kind == 0) 1.1f else 1.7f)) * (1f - 0.4f * tt * tt)
            val spd = hypot(fVx[i], fVy[i])
            val stretch = 1f + min(1.6f, spd / (520f * sc)) * (if (kind == 2) 0.2f else 1f)
            val v = if (fSeed[i] > PI) 1 else 0
            val fl = 0.86f + 0.14f * sin(fAge[i] * 40f + fSeed[i])
            c.save(); c.translate(fX[i], fY[i]); c.rotate(deg(atan2(fVy[i], fVx[i]))); c.scale(stretch, 1f)
            if (kind == 1 && pq > 0.35f && (i and 1) == 0 && tt < 0.6f) {
                plusPaint.alpha = ((1f - tt) * 0.4f * 255f).toInt()
                sprite(c, sBlue, 0f, 0f, s * 1.7f, plusPaint)
            }
            // body: white sprite tinted along the colour ramp (white-blue, cyan, blue, indigo, dark violet)
            plusPaint.colorFilter = ramp[(tt * 15f).toInt().coerceIn(0, 15)]
            plusPaint.alpha = (min(1f, (1f - tt) * 1.1f) * 0.9f * fl * 255f).toInt()
            sprite(c, fWhite[v], 0f, 0f, s, plusPaint)
            plusPaint.colorFilter = null
            // hot core while the particle is young
            if (tt < 0.38f) {
                plusPaint.alpha = ((1f - tt / 0.38f) * 0.85f * fl * 255f).toInt()
                sprite(c, fCore[v], 0f, 0f, s * 0.55f, plusPaint)
            }
            c.restore()
        }
        sparkPaint.strokeWidth = max(1f, 1.6f * sc)
        for (i in 0 until nk) {
            val a = 1f - kAge[i] / kLife[i]
            if (kG[i] < 0f) {
                // floating ash: a small flickering glow
                val fl = 0.55f + 0.45f * sin(kAge[i] * 14f + kX[i])
                plusPaint.alpha = (a * fl * 0.9f * 255f).toInt()
                sprite(c, sCyan, kX[i], kY[i], 3.2f * sc * (0.6f + 0.4f * a), plusPaint)
            } else {
                sparkPaint.color = Color.argb((a * 255f).toInt(), 190, 230, 255)
                c.drawLine(kX[i], kY[i], kX[i] - kVx[i] * 0.04f, kY[i] - kVy[i] * 0.04f, sparkPaint)
                plusPaint.alpha = (a * 0.8f * 255f).toInt()
                sprite(c, sCore, kX[i], kY[i], 2.6f * sc, plusPaint)
            }
        }
        if (mode == Mode.FIRE && st.mouth > 0.05f) {
            model.mouthPos(st, mp)
            val fl = 0.85f + 0.15f * sin(time * 40f)
            val s = size * 1.1f * st.mouth * fl
            plusPaint.alpha = (0.7f * st.mouth * 255f).toInt(); sprite(c, sCyan, mp[0], mp[1], s, plusPaint)
            plusPaint.alpha = (0.9f * st.mouth * 255f).toInt(); sprite(c, sCore, mp[0], mp[1], s * 0.4f, plusPaint)
        }
    }
}
