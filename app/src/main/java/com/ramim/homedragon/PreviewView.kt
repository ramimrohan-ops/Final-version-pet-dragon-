package com.ramim.homedragon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Preview box for the settings screen.
 *
 * SIZE: the sitting dragon at the chosen size inside a box that is just bigger than the dragon at 150%,
 *       with a bar that shows the icon width at 100%.
 * QUALITY: the sitting dragon with its frame rate (30, or the Quality rate if lower) in the top-right corner.
 * FLYING: the dragon in the flying pose, flapping in place, with its frame rate (screen rate x Quality) in the top-right corner.
 * PARTICLES: the dragon's head breathing fire at a dummy icon, with the flame count of the chosen particle quality.
 *
 * It only animates while [start] has been called (the slider is being dragged); otherwise it holds the last frame.
 */
class PreviewView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val SIZE = 0
        const val QUALITY = 1
        const val PARTICLES = 2
        const val FLYING = 3
        private const val FLY_W = 256f               // flying pose: width in model units
        private const val FLY_H = 250f               // flying pose: height in model units
        private const val FLY_CX = -31.5f            // flying pose: horizontal centre
        private const val FLY_CY = -66f              // flying pose: vertical centre
        private const val CYCLE = 3.6f                 // seconds per fire cycle
        private const val MAXF = 260
        private const val MAXS = 120
        private const val MODEL_W = 200.5f             // dragon width in model units (tail tip to snout)
        private const val MODEL_H = 201f               // dragon height in model units (ground to wing tip)
        private const val MODEL_CX = -32.2f            // horizontal centre of the dragon in model units
    }

    private val dm = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    private val model = DragonModel()
    private val st = DragonModel.State()        // sitting dragon
    private val headSt = DragonModel.State()    // head only

    private var pct = 100
    private var running = false
    private var t = 1.2f
    private var cyc = 0.5f
    private var blink = 0f
    private var nextBlink = 2f
    private var mouth = 0f
    private var heat = 0f
    private var acc = 0f
    private var lastNs = 0L
    private var dueNs = 0L
    private var seed = 12345L
    private var wingPh = 0f

    // the best refresh rate this screen offers (the overlay asks for the same one)
    private val hz: Float = try {
        val d = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: d.refreshRate
        max(30f, best)
    } catch (_: Throwable) {
        60f
    }

    // flame particles
    private val fx = FloatArray(MAXF); private val fy = FloatArray(MAXF)
    private val fvx = FloatArray(MAXF); private val fvy = FloatArray(MAXF)
    private val fage = FloatArray(MAXF); private val flife = FloatArray(MAXF); private val fsz = FloatArray(MAXF)
    private val fph = FloatArray(MAXF)
    private var nf = 0
    // sparks
    private val sx = FloatArray(MAXS); private val sy = FloatArray(MAXS)
    private val svx = FloatArray(MAXS); private val svy = FloatArray(MAXS)
    private val sage = FloatArray(MAXS); private val slife = FloatArray(MAXS)
    private var ns = 0

    // geometry of the quality preview
    private val leftRect = RectF(); private val rightRect = RectF()
    private var mx = 0f; private var my = 0f          // mouth tip
    private var icx = 0f; private var icy = 0f; private var icSize = 0f
    private var geomReady = false
    private val tmp = FloatArray(2)

    private var flameCols: IntArray = FlameColors.DEFAULT.copyOf()
    private var sprites: Array<Bitmap> = Array(6) { makeSprite(ramp(it / 5f)) }

    /** Use the flame colours chosen in the app (the original blue flame when none were chosen). */
    fun reloadFlame() {
        val cols = Prefs.flameColors(context)
        if (FlameColors.same(cols, flameCols)) return
        flameCols = cols
        sprites = Array(6) { makeSprite(ramp(it / 5f)) }
        invalidate()
    }

    init { reloadFlame() }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val dst = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val textOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val clip = Path()

    // ---------------------------------------------------------------- control

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNs != 0L && frameTimeNanos < dueNs - 300_000L) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val dt = if (lastNs == 0L) 0f else min(0.1f, (frameTimeNanos - lastNs) / 1e9f)
            lastNs = frameTimeNanos
            if (dt > 0f) advance(dt)
            invalidate()
            val want = (1e9f / rate()).toLong()
            dueNs = if (dueNs == 0L || frameTimeNanos - dueNs > want) frameTimeNanos + want else dueNs + want
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Frame rate this preview draws at: the real rate the dragon would use for this Quality. */
    private fun rate(): Float = when (kind) {
        QUALITY -> min(30f, max(5f, hz * pct / 100f))      // sitting still: 30 fps at most
        FLYING -> max(5f, hz * pct / 100f)
        else -> hz
    }

    fun setValue(v: Int) {
        if (v == pct) return
        pct = v
        if (!running) { if (kind == PARTICLES) warm(); invalidate() }
    }

    fun start() {
        if (running) return
        running = true
        lastNs = 0L; dueNs = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if ((kind == QUALITY || kind == FLYING) && w > 0 && h > 0) {
            leftRect.set(0f, 0f, w.toFloat(), h.toFloat())
            geomReady = true
        }
        if (kind == PARTICLES && w > 0 && h > 0) {
            rightRect.set(0f, 0f, w.toFloat(), h.toFloat())
            val rw = rightRect.width()
            headSt.apply {
                face = 1f; sp = 0f; pitch = 0f; head = 0.2f; eye = 1f; wingPh = 0f; bob = 0f; crouch = 0f; walk = 0f; gait = 0f
                ds = rw * 0.26f / 80f
                x = rightRect.left + rw * 0.20f
                y = h * 0.50f
            }
            model.mouthLocal(headSt, tmp)
            mx = headSt.x + tmp[0] * headSt.ds
            my = headSt.y + tmp[1] * headSt.ds
            icSize = rw * 0.17f
            icx = rightRect.right - rw * 0.15f
            icy = min(h - icSize * 0.7f, max(icSize * 0.7f, my + dp(2f)))
            geomReady = true
            warm()
        }
    }

    // ---------------------------------------------------------------- simulation

    private fun rnd(): Float {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        return ((seed ushr 33).toInt() and 0xFFFFFF) / 16777216f
    }

    private fun advance(dt: Float) {
        t += dt
        nextBlink -= dt
        if (nextBlink <= 0f) { blink = 0.14f; nextBlink = 2f + rnd() * 3f }
        if (blink > 0f) blink -= dt
        if (kind == FLYING) wingPh += dt * 6.2832f * 2.2f
        if (kind == PARTICLES) step(dt)
    }

    /** Run the fire for a moment so a still picture already shows a flame in flight. */
    private fun warm() {
        if (!geomReady) return
        nf = 0; ns = 0; acc = 0f; heat = 0f; mouth = 0f; cyc = 0.45f
        for (i in 0 until 66) step(1f / 60f)
    }

    private fun step(dt: Float) {
        cyc += dt
        if (cyc >= CYCLE) cyc -= CYCLE
        val firing = cyc in 0.45f..2.1f
        val mouthGoal = if (cyc in 0.3f..2.25f) 1f else 0f
        mouth += (mouthGoal - mouth) * min(1f, dt * 12f)
        heat = if (firing && cyc > 0.7f) min(1f, heat + dt * 2.2f) else max(0f, heat - dt * 0.8f)

        val q = pct / 100f
        val capF = min(MAXF, (70 + 130 * q).toInt())
        val capS = min(MAXS, (30 + 60 * q).toInt())

        if (firing) {
            acc += capF / 0.5f * dt
            while (acc >= 1f) {
                acc -= 1f
                if (nf < capF) spawnFlame()
            }
        } else acc = 0f

        var i = 0
        while (i < nf) {
            fage[i] += dt
            if (fage[i] >= flife[i]) {
                // impact: a few sparks fly off the icon
                if (rnd() < 0.5f && ns < capS) spawnSpark(fx[i], fy[i])
                nf--
                fx[i] = fx[nf]; fy[i] = fy[nf]; fvx[i] = fvx[nf]; fvy[i] = fvy[nf]
                fage[i] = fage[nf]; flife[i] = flife[nf]; fsz[i] = fsz[nf]; fph[i] = fph[nf]
                continue
            }
            fx[i] += fvx[i] * dt
            fy[i] += fvy[i] * dt + sin(fage[i] * 22f + fph[i]) * dp(14f) * dt
            i++
        }
        i = 0
        while (i < ns) {
            sage[i] += dt
            if (sage[i] >= slife[i]) {
                ns--
                sx[i] = sx[ns]; sy[i] = sy[ns]; svx[i] = svx[ns]; svy[i] = svy[ns]; sage[i] = sage[ns]; slife[i] = slife[ns]
                continue
            }
            svy[i] += dp(240f) * dt
            sx[i] += svx[i] * dt; sy[i] += svy[i] * dt
            i++
        }
    }

    private fun spawnFlame() {
        val i = nf++
        val life = 0.38f + rnd() * 0.22f
        val tx = icx + (rnd() - 0.5f) * icSize * 0.4f
        val ty = icy + (rnd() - 0.5f) * icSize * 0.4f
        fx[i] = mx + (rnd() - 0.5f) * dp(3f); fy[i] = my + (rnd() - 0.5f) * dp(3f)
        fvx[i] = (tx - fx[i]) / life; fvy[i] = (ty - fy[i]) / life
        fage[i] = 0f; flife[i] = life; fsz[i] = dp(7f) + rnd() * dp(7f); fph[i] = rnd() * 6.28f
    }

    private fun spawnSpark(x: Float, y: Float) {
        val i = ns++
        val a = -3.14159f * (0.15f + rnd() * 0.7f)       // mostly upwards
        val sp = dp(50f + rnd() * 90f)
        sx[i] = x; sy[i] = y; svx[i] = cos(a) * sp * (if (rnd() < 0.5f) 1f else -1f); svy[i] = sin(a) * sp
        sage[i] = 0f; slife[i] = 0.4f + rnd() * 0.4f
    }

    // ---------------------------------------------------------------- drawing

    private fun ramp(a: Float): Int {
        if (!FlameColors.isDefault(flameCols)) {
            val c = FlameColors.at(flameCols, a * 100f)
            return if (a <= 0f) FlameColors.lighten(c, 0.8f) else c
        }
        // white -> cyan -> blue -> violet
        val stops = arrayOf(floatArrayOf(0f, 255f, 255f, 255f), floatArrayOf(0.25f, 120f, 232f, 255f),
            floatArrayOf(0.6f, 50f, 120f, 255f), floatArrayOf(1f, 135f, 80f, 232f))
        var k = 0
        while (k < stops.size - 2 && a > stops[k + 1][0]) k++
        val a0 = stops[k]; val a1 = stops[k + 1]
        val u = ((a - a0[0]) / (a1[0] - a0[0])).coerceIn(0f, 1f)
        return Color.rgb((a0[1] + (a1[1] - a0[1]) * u).toInt(), (a0[2] + (a1[2] - a0[2]) * u).toInt(), (a0[3] + (a1[3] - a0[3]) * u).toInt())
    }

    private fun makeSprite(col: Int): Bitmap {
        val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val clear = col and 0x00FFFFFF
        p.shader = RadialGradient(24f, 24f, 24f, intArrayOf(col or (255 shl 24), clear or (150 shl 24), clear),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(24f, 24f, 24f, p)
        return b
    }

    private fun sprite(c: Canvas, idx: Int, x: Float, y: Float, size: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        spritePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        dst.set(x - size, y - size, x + size, y + size)
        c.drawBitmap(sprites[idx.coerceIn(0, 5)], null, dst, spritePaint)
    }

    private fun setBlink() { st.eye = if (blink > 0f) 0.1f else 1f; headSt.eye = st.eye }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // box
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, dp(16f), dp(16f), Path.Direction.CW)
        c.save(); c.clipPath(clip)
        fill.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#1D2D55"), Color.parseColor("#0E162E"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        setBlink()
        when (kind) {
            SIZE -> drawSize(c, w, h)
            QUALITY -> drawFps(c, w, h, false)
            FLYING -> drawFps(c, w, h, true)
            else -> drawParticles(c, w, h)
        }
        c.restore()
        line.color = Color.parseColor("#2C3F6B"); line.strokeWidth = dp(1f)
        c.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), dp(16f), dp(16f), line)
    }

    private fun sit(s: DragonModel.State, time: Float) {
        s.face = 1f; s.sp = 0f; s.pitch = 0f; s.mouth = 0f; s.wingPh = 0f; s.bob = 0f; s.crouch = 0f; s.walk = 0f; s.gait = 0f
        s.head = model.restHead(0f, 0f) + 0.04f * sin(time * 0.6f)
        s.time = time
    }

    private fun drawSize(c: Canvas, w: Float, h: Float) {
        val base = h - dp(26f)                                   // ground line
        val ds150 = min(w * 0.94f / MODEL_W, (base - dp(8f)) / MODEL_H)
        val ds = ds150 * pct / 150f
        val ds100 = ds150 * 100f / 150f
        // bar = the dragon's width at 100%, a reference for how much the size changes
        val bw = MODEL_W * ds100
        fill.color = Color.argb(210, 220, 230, 247)
        c.drawRoundRect(w / 2f - bw / 2f, base, w / 2f + bw / 2f, base + dp(6f), dp(3f), dp(3f), fill)
        textFill.textSize = dp(10f); textFill.color = Color.parseColor("#8A9BBD")
        c.drawText("width at 100%", w / 2f, h - dp(6f), textFill)
        sit(st, t)
        st.ds = ds
        st.x = w / 2f - MODEL_CX * ds
        st.y = base
        model.draw(c, st)
    }

    private fun drawFps(c: Canvas, w: Float, h: Float, flying: Boolean) {
        if (!geomReady) return
        val full = max(5, Math.round(hz * pct / 100f))
        val shown = if (flying) full else min(30, full)

        if (flying) {
            sit(st, t)
            st.sp = 1f
            st.head = model.restHead(1f, 0f)
            st.wingPh = wingPh
            st.bob = sin(wingPh) * 3f - 4f
            val ds = min(w * 0.62f / FLY_W, (h - dp(60f)) / FLY_H)
            st.ds = ds
            st.x = w / 2f - FLY_CX * ds
            st.y = (h + dp(34f)) / 2f - FLY_CY * ds
            model.draw(c, st)
        } else {
            val base = h - dp(18f)
            val ds = min(w * 0.62f / MODEL_W, (base - dp(8f)) / MODEL_H)
            sit(st, t)
            st.ds = ds
            st.x = w / 2f - MODEL_CX * ds
            st.y = base
            fill.color = Color.argb(200, 220, 230, 247)
            c.drawRoundRect(w / 2f - MODEL_W * ds * 0.5f, base, w / 2f + MODEL_W * ds * 0.5f, base + dp(4f), dp(2f), dp(2f), fill)
            model.draw(c, st)
        }

        // caption, top-left
        textFill.textAlign = Paint.Align.LEFT
        textFill.textSize = dp(10f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
        c.drawText(if (flying) "FLYING" else "SITTING", dp(10f), dp(18f), textFill)

        // frame rate, top-right: big white number on a soft shadow
        val numSize = min(dp(46f), w * 0.24f)
        val rx = w - dp(10f)
        val ny = dp(8f) + numSize * 0.80f
        fill.shader = RadialGradient(rx - dp(26f), ny - dp(8f), dp(56f),
            intArrayOf(Color.argb(170, 4, 8, 20), Color.argb(80, 4, 8, 20), Color.argb(0, 4, 8, 20)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(rx - dp(26f), ny - dp(8f), dp(56f), fill)
        fill.shader = null
        textFill.textAlign = Paint.Align.RIGHT
        textFill.typeface = Typeface.DEFAULT_BOLD
        textFill.textSize = numSize; textFill.color = Color.WHITE
        textFill.setShadowLayer(dp(9f), 0f, dp(2f), Color.argb(235, 0, 0, 0))
        c.drawText(shown.toString(), rx, ny, textFill)
        textFill.textSize = dp(12f); textFill.color = Color.WHITE
        textFill.setShadowLayer(dp(6f), 0f, dp(1.5f), Color.argb(235, 0, 0, 0))
        c.drawText("fps", rx, ny + dp(14f), textFill)
        textFill.setShadowLayer(0f, 0f, 0f, 0)
        textFill.textAlign = Paint.Align.CENTER
    }

    private fun drawParticles(c: Canvas, w: Float, h: Float) {
        if (!geomReady) return
        c.save(); c.clipRect(rightRect)
        // dummy icon
        val half = icSize / 2f
        val bump = 1f + 0.05f * heat
        fill.color = Color.parseColor("#2B3C66")
        c.drawRoundRect(icx - half * bump, icy - half * bump, icx + half * bump, icy + half * bump, dp(9f), dp(9f), fill)
        line.color = Color.parseColor("#4A6199"); line.strokeWidth = dp(1.2f)
        c.drawRoundRect(icx - half * bump, icy - half * bump, icx + half * bump, icy + half * bump, dp(9f), dp(9f), line)
        fill.color = Color.parseColor("#6F86B8")
        c.drawCircle(icx, icy - half * 0.12f, half * 0.34f, fill)
        c.drawRoundRect(icx - half * 0.46f, icy + half * 0.30f, icx + half * 0.46f, icy + half * 0.50f, dp(3f), dp(3f), fill)
        // head
        headSt.mouth = mouth; headSt.time = t
        model.drawHeadOnly(c, headSt)
        // glow on the icon: white-hot, then cyan, then deep blue as it cools
        if (heat > 0.02f) {
            val gi = ((1f - heat) * 4f).toInt()
            sprite(c, gi, icx, icy, icSize * (1.1f + 0.5f * heat), heat)
            sprite(c, 0, icx, icy, icSize * 0.6f * heat, heat * 0.8f)
        }
        // flames
        for (i in 0 until nf) {
            val a = fage[i] / flife[i]
            val sz = fsz[i] * (0.6f + a * 0.9f)
            sprite(c, (a * 5.99f).toInt(), fx[i], fy[i], sz, (1f - a).pow(0.7f))
        }
        // mouth flare
        if (mouth > 0.05f) {
            sprite(c, 1, mx, my, dp(13f) * mouth, 0.8f * mouth)
            sprite(c, 0, mx, my, dp(6f) * mouth, 0.9f * mouth)
        }
        // sparks
        for (i in 0 until ns) {
            val a = sage[i] / slife[i]
            sprite(c, if (a < 0.4f) 0 else 1, sx[i], sy[i], dp(3.2f), 1f - a)
        }
        val capF = min(MAXF, (70 + 130 * pct / 100f).toInt())
        textFill.textSize = dp(10.5f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.textAlign = Paint.Align.LEFT
        c.drawText("flames up to $capF", rightRect.left + dp(10f), h - dp(8f), textFill)
        textFill.textAlign = Paint.Align.CENTER
        c.restore()
    }
}
