package com.ramim.homedragon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Flame colour picker for the settings screen: a live ramp bar, up to six colour swatches (hot core first, cooled tip last),
 * a saturation/brightness square with a hue bar, and ready-made palettes. The first palette, Blue, is the default flame.
 *
 * onChange(colours, final): done = false while a finger is still dragging (update the preview only),
 * true when a change is complete (save it and apply it to the running dragon).
 */
class FlamePicker(private val ctx: Context, private val onChange: (IntArray, Boolean) -> Unit) {

    private companion object {
        val FG = Color.parseColor("#EAF2FF")
        val MUTED = Color.parseColor("#8A9BBD")
        val STROKE = Color.parseColor("#223152")
        val PANEL = Color.parseColor("#0E162E")
        val ORANGE = Color.parseColor("#FF9A3C")
        val RED = Color.parseColor("#F0634F")
        val TEAL = Color.parseColor("#2DD4BF")

        val PRESETS: List<Pair<String, IntArray>> = listOf(
            "Blue" to FlameColors.DEFAULT,
            "Fire" to intArrayOf(0xFFF2C2.opaque(), 0xFFC53D.opaque(), 0xFF7A1A.opaque(), 0xE03A12.opaque(), 0x7A1A0C.opaque()),
            "Green" to intArrayOf(0xE6FFE0.opaque(), 0x8BF28B.opaque(), 0x2EDB6B.opaque(), 0x12A05A.opaque(), 0x0A5A3C.opaque()),
            "Purple" to intArrayOf(0xF3E3FF.opaque(), 0xC79BFF.opaque(), 0x9B5CFF.opaque(), 0x6A2FE0.opaque(), 0x35157A.opaque()),
            "Ice" to intArrayOf(0xFFFFFF.opaque(), 0xCFF3FF.opaque(), 0x8EDBFF.opaque(), 0x5BA8FF.opaque(), 0x3566D8.opaque()),
            "Gold" to intArrayOf(0xFFFBE0.opaque(), 0xFFE27A.opaque(), 0xFFC21A.opaque(), 0xE08A00.opaque(), 0x8A4A00.opaque()),
            "Rainbow" to intArrayOf(0xFF5A5A.opaque(), 0xFFA94D.opaque(), 0xFFE066.opaque(), 0x69DB7C.opaque(), 0x4DABF7.opaque(), 0xB197FC.opaque())
        )
    }

    private val dm = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dm).toInt()

    private var cols: IntArray = Prefs.flameColors(ctx)
    private var sel = 0
    private val hsv = FloatArray(3)

    val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val ramp = RampBar(ctx)
    private val swatchRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val square = SvSquare(ctx)
    private val hueBar = HueBar(ctx)
    private val hex = text("", 14f, FG, true)
    private val which = text("", 11f, MUTED, false)
    private val remove = text("Remove", 12f, RED, true)
    private val chips = ArrayList<View>()

    init {
        // ramp bar with its two end labels
        view.addView(ramp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(12) })
        val ends = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(text("hot core", 10f, MUTED, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ends.addView(text("cooled tip", 10f, MUTED, false))
        view.addView(ends, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })

        view.addView(swatchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })

        // editor panel for the selected colour
        view.addView(square, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(12) })
        view.addView(hueBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply { topMargin = dp(12) })

        val info = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        names.addView(hex); names.addView(which)
        info.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        remove.setPadding(dp(10), dp(6), 0, dp(6))
        remove.isClickable = true
        remove.setOnClickListener {
            if (cols.size > 1) {
                val l = cols.toMutableList(); l.removeAt(sel); cols = l.toIntArray()
                sel = min(sel, cols.size - 1); load(); onChange(cols, true)
            }
        }
        info.addView(remove)
        view.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // palettes
        view.addView(text("Palettes", 11f, MUTED, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        var row: LinearLayout? = null
        for ((i, pr) in PRESETS.withIndex()) {
            if (i % 4 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })
            }
            val chip = Chip(ctx, pr.first, pr.second)
            chip.setOnClickListener { cols = pr.second.copyOf(); sel = 0; load(); onChange(cols, true) }
            chips.add(chip)
            row!!.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i % 4 != 0) leftMargin = dp(8) })
        }
        // keep the last row's chips the same width as the others
        val last = PRESETS.size % 4
        if (last != 0) for (k in last until 4) row!!.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })

        square.onPick = { s, v -> cols[sel] = Color.HSVToColor(floatArrayOf(square.hue, s, v)); hsv[1] = s; hsv[2] = v; refreshLight(); onChange(cols, false) }
        square.onDone = { onChange(cols, true) }
        hueBar.onPick = { h ->
            square.hue = h; hsv[0] = h
            cols[sel] = Color.HSVToColor(floatArrayOf(h, square.sat, square.value)); square.invalidate(); refreshLight(); onChange(cols, false)
        }
        hueBar.onDone = { onChange(cols, true) }
        load()
    }

    /** Back to the original blue flame. */
    fun reset() { cols = FlameColors.DEFAULT.copyOf(); sel = 0; load(); onChange(cols, true) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Select colour i and move the editor to it. */
    private fun load() {
        Color.colorToHSV(cols[sel], hsv)
        square.hue = hsv[0]; square.sat = hsv[1]; square.value = hsv[2]
        hueBar.hue = hsv[0]
        square.invalidate(); hueBar.invalidate()
        buildSwatches()
        refreshLight()
    }

    /** Cheap refresh while dragging: ramp, hex text and the selected swatch only. */
    private fun refreshLight() {
        ramp.set(cols)
        hex.text = String.format("#%06X", cols[sel] and 0xFFFFFF)
        which.text = "colour ${sel + 1} of ${cols.size}  ·  up to ${FlameColors.MAX}"
        remove.visibility = if (cols.size > 1) View.VISIBLE else View.INVISIBLE
        for (ch in chips) ch.invalidate()
        for (i in 0 until swatchRow.childCount) {
            val v = swatchRow.getChildAt(i)
            if (v is Swatch && v.index in cols.indices) { v.color = cols[v.index]; v.chosen = v.index == sel; v.invalidate() }
        }
    }

    private fun buildSwatches() {
        swatchRow.removeAllViews()
        for (i in 0 until FlameColors.MAX + 1) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i < cols.size) {
                val sw = Swatch(ctx, i, cols[i], i == sel, false)
                sw.setOnClickListener { sel = i; load() }
                swatchRow.addView(sw, lp)
            } else if (i == cols.size && cols.size < FlameColors.MAX) {
                val plus = Swatch(ctx, -1, Color.TRANSPARENT, false, true)
                plus.setOnClickListener {
                    val c0 = cols[sel]; Color.colorToHSV(c0, hsv)
                    val nc = Color.HSVToColor(floatArrayOf((hsv[0] + 28f) % 360f, hsv[1], (hsv[2] * 0.8f).coerceAtLeast(0.25f)))
                    val l = cols.toMutableList(); l.add(sel + 1, nc); cols = l.toIntArray()
                    sel += 1; load(); onChange(cols, true)
                }
                swatchRow.addView(plus, lp)
            } else {
                swatchRow.addView(View(ctx), lp)
            }
        }
    }

    // ------------------------------------------------------------------ small custom views

    private inner class RampBar(c: Context) : View(c) {
        private var cs: IntArray = intArrayOf(Color.WHITE)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        fun set(v: IntArray) { cs = v.copyOf(); invalidate() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            if (cs.size == 1) { p.shader = null; p.color = cs[0] }
            else {
                val pos = FloatArray(cs.size) { FlameColors.position(cs.size, it) / 100f }
                p.shader = LinearGradient(0f, 0f, w, 0f, cs, pos, Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(rect, h / 2f, h / 2f, p)
            p.shader = null
        }
    }

    private inner class Swatch(c: Context, val index: Int, var color: Int, var chosen: Boolean, private val plus: Boolean) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = min(width, height) / 2f - dp(3)
            if (plus) {
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(1).toFloat() * 1.4f; p.color = MUTED
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(3).toFloat()), 0f)
                c.drawCircle(cx, cy, r, p); p.pathEffect = null
                p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = dp(2).toFloat()
                c.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, p); c.drawLine(cx, cy - r * 0.4f, cx, cy + r * 0.4f, p)
                return
            }
            p.style = Paint.Style.FILL; p.color = color
            c.drawCircle(cx, cy, r - (if (chosen) dp(3) else 0), p)
            p.style = Paint.Style.STROKE
            if (chosen) { p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE; c.drawCircle(cx, cy, r, p) }
            else { p.strokeWidth = dp(1).toFloat(); p.color = Color.argb(90, 255, 255, 255); c.drawCircle(cx, cy, r, p) }
        }
    }

    private inner class Chip(c: Context, private val name: String, private val colors: IntArray) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL; p.color = PANEL; c.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1).toFloat(); p.color = if (FlameColors.same(cols, colors)) TEAL else STROKE
            c.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), p)
            val pos = FloatArray(colors.size) { FlameColors.position(colors.size, it) / 100f }
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(dp(8).toFloat(), 0f, w - dp(8), 0f, colors, pos, Shader.TileMode.CLAMP)
            rect.set(dp(8).toFloat(), dp(9).toFloat(), w - dp(8), dp(19).toFloat())
            c.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(), p)
            p.shader = null
            p.color = MUTED; p.textSize = dp(11).toFloat(); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(name, w / 2f, h - dp(10).toFloat(), p)
        }
    }

    private inner class SvSquare(c: Context) : View(c) {
        var hue = 0f; var sat = 1f; var value = 1f
        var onPick: ((Float, Float) -> Unit)? = null
        var onDone: (() -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = dp(14).toFloat()
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            c.restore()
            val cx = sat * w; val cy = (1f - value) * h
            p.style = Paint.Style.FILL; p.color = Color.HSVToColor(floatArrayOf(hue, sat, value))
            c.drawCircle(cx, cy, dp(11).toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = Color.WHITE
            c.drawCircle(cx, cy, dp(11).toFloat(), p)
            p.strokeWidth = dp(1).toFloat(); p.color = Color.argb(110, 0, 0, 0)
            c.drawCircle(cx, cy, dp(13).toFloat(), p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    sat = (e.x / width).coerceIn(0f, 1f); value = 1f - (e.y / height).coerceIn(0f, 1f)
                    invalidate(); onPick?.invoke(sat, value)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); onDone?.invoke() }
            }
            return true
        }
    }

    private inner class HueBar(c: Context) : View(c) {
        var hue = 0f
        var onPick: ((Float) -> Unit)? = null
        var onDone: (() -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, w, 0f, stops, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, h / 2f, h / 2f, p)
            p.shader = null
            val cx = (hue / 360f) * (w - h) + h / 2f
            p.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f)); c.drawCircle(cx, h / 2f, h / 2f - dp(2), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = Color.WHITE
            c.drawCircle(cx, h / 2f, h / 2f - dp(2), p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val h = height.toFloat()
                    hue = ((e.x - h / 2f) / (width - h)).coerceIn(0f, 1f) * 360f
                    if (hue >= 359.9f) hue = 359.9f
                    invalidate(); onPick?.invoke(hue)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); onDone?.invoke() }
            }
            return true
        }
    }
}

private fun Int.opaque(): Int = this or (0xFF shl 24)
