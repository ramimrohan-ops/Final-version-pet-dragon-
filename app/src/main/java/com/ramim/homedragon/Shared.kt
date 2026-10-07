package com.ramim.homedragon

import android.content.Context
import android.graphics.Color
import android.graphics.RectF

/** Icon positions found by the accessibility service, shared with the overlay (same process). */
object IconRegistry {
    @Volatile var icons: List<RectF> = emptyList()      // screen pixels
    @Volatile var onHome: Boolean = true                // launcher is the foreground app
    @Volatile var serviceActive: Boolean = false        // accessibility service connected
    @Volatile var launcherPkg: String? = null
    var listener: (() -> Unit)? = null                  // always called on the main thread
    var swipeListener: (() -> Unit)? = null             // launcher page is scrolling sideways (main thread)
}

/** Flame colours: up to 6, from the hot core to the cooled tip. The default is the original blue flame. */
object FlameColors {
    const val MAX = 6
    val DEFAULT = intArrayOf(Color.rgb(215, 240, 255), Color.rgb(120, 225, 255), Color.rgb(55, 130, 255), Color.rgb(78, 60, 230), Color.rgb(42, 24, 150))
    private val DEFAULT_POS = intArrayOf(0, 18, 45, 75, 100)

    fun same(a: IntArray, b: IntArray) = a.size == b.size && a.indices.all { (a[it] and 0xFFFFFF) == (b[it] and 0xFFFFFF) }
    fun isDefault(c: IntArray) = same(c, DEFAULT)

    /** Position of colour i of n along the flame, 0..100 (the five default blues keep their original spacing). */
    fun position(n: Int, i: Int): Int = if (n == 5) DEFAULT_POS[i] else if (n <= 1) 0 else i * 100 / (n - 1)

    /** Colour at t (0 = hot core ... 100 = cooled tip). */
    fun at(cols: IntArray, t: Float): Int {
        val n = cols.size
        if (n == 1) return cols[0] or (0xFF shl 24)
        var j = 0
        while (j < n - 2 && t > position(n, j + 1)) j++
        val a = position(n, j).toFloat(); val b = position(n, j + 1).toFloat()
        val u = ((t - a) / (b - a)).coerceIn(0f, 1f)
        val c0 = cols[j]; val c1 = cols[j + 1]
        return Color.rgb(
            (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * u).toInt(),
            (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * u).toInt(),
            (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * u).toInt()
        )
    }

    /** Move a colour towards white (f = 0..1). */
    fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dragon", Context.MODE_PRIVATE)

    /** Every slider moves in steps of 10%: older saved values snap to the nearest step. */
    private fun snap(v: Int, lo: Int, hi: Int) = (((v + 5) / 10) * 10).coerceIn(lo, hi)

    fun scalePct(c: Context) = snap(sp(c).getInt("scale", 100), 50, 150)          // dragon size, 50..150
    fun speedPct(c: Context) = snap(sp(c).getInt("speed", 100), 50, 150)          // dragon speed, 50..150
    fun qualityPct(c: Context) = snap(sp(c).getInt("quality", 100), 10, 100)      // frame rate, 10..100
    fun particlePct(c: Context) = snap(sp(c).getInt("particles", 100), 10, 100)   // fire, smoke and sparks, 10..100
    fun cols(c: Context) = sp(c).getInt("cols", 4)
    fun rows(c: Context) = sp(c).getInt("rows", 6)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)

    fun setScalePct(c: Context, v: Int) = sp(c).edit().putInt("scale", v).apply()
    fun setSpeedPct(c: Context, v: Int) = sp(c).edit().putInt("speed", v).apply()
    fun setQualityPct(c: Context, v: Int) = sp(c).edit().putInt("quality", v).apply()
    fun setParticlePct(c: Context, v: Int) = sp(c).edit().putInt("particles", v).apply()
    fun setCols(c: Context, v: Int) = sp(c).edit().putInt("cols", v).apply()
    fun setRows(c: Context, v: Int) = sp(c).edit().putInt("rows", v).apply()
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Saved flame colours (hex list), the original blue ramp when nothing was saved. */
    fun flameColors(c: Context): IntArray {
        val raw = sp(c).getString("flame", null) ?: return FlameColors.DEFAULT.copyOf()
        val out = ArrayList<Int>()
        for (part in raw.split(',')) {
            try { out.add(Color.parseColor("#" + part.trim())) } catch (_: Throwable) {}
        }
        return if (out.isEmpty() || out.size > FlameColors.MAX) FlameColors.DEFAULT.copyOf() else out.toIntArray()
    }
    fun setFlameColors(c: Context, cols: IntArray) {
        val e = sp(c).edit()
        if (FlameColors.isDefault(cols)) e.remove("flame") else e.putString("flame", cols.joinToString(",") { String.format("%06X", it and 0xFFFFFF) })
        e.apply()
    }
}
