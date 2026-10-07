package com.ramim.homedragon

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Simple dark home screen for the app: a big start/stop button, four percentage sliders (quality, particles and size have preview boxes)
 * (quality, dragon size, dragon speed) that change the dragon live, a flame colour picker, and three setup rows.
 */
class MainActivity : Activity() {

    private companion object {
        val BG = Color.parseColor("#0A0F1E")
        val CARD = Color.parseColor("#121A30")
        val STROKE = Color.parseColor("#223152")
        val TRACK = Color.parseColor("#26365A")
        val FG = Color.parseColor("#EAF2FF")
        val MUTED = Color.parseColor("#8A9BBD")
        val TEAL = Color.parseColor("#2DD4BF")
        val BLUE = Color.parseColor("#3B82F6")
        val ORANGE = Color.parseColor("#FF9A3C")
        val RED = Color.parseColor("#F0634F")
        val GREEN = Color.parseColor("#3DDC97")
    }

    private lateinit var pill: TextView
    private lateinit var toggle: TextView
    private val setupRows = ArrayList<SetupRow>()
    private val sliders = ArrayList<Slider>()
    private var flamePreview: PreviewView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun gradient(a: Int, b: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, STROKE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            .apply { topMargin = dp(12); bottomMargin = dp(12) }
    }

    // ---------------------------------------------------------------- slider row

    private inner class Slider(
        title: String, hint: String, val min: Int, val max: Int, start: Int,
        private val save: (Int) -> Unit, private val live: () -> Unit, private val step: Int = 1,
        private val previews: List<PreviewView> = emptyList(), previewDp: Int = 150
    ) {
        val value = text("$start%", 15f, TEAL, true)
        val seek = SeekBar(this@MainActivity)
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        init {
            val head = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(title, 15f, FG, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(value)
            view.addView(head)
            view.addView(text(hint, 11f, MUTED))

            if (previews.size > 1) {
                // several boxes sit side by side
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                previews.forEachIndexed { i, p ->
                    p.setValue(start)
                    row.addView(p, LinearLayout.LayoutParams(0, dp(previewDp), 1f).apply { if (i > 0) leftMargin = dp(8) })
                }
                view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) })
            } else {
                for (p in previews) {
                    p.setValue(start)
                    view.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(previewDp))
                        .apply { topMargin = dp(10) })
                }
            }

            seek.max = (max - min) / step
            try {
                val track = GradientDrawable().apply { setColor(TRACK); cornerRadius = dp(4).toFloat(); setSize(0, dp(6)) }
                val fill = ClipDrawable(
                    gradient(TEAL, BLUE, 3).apply { setSize(0, dp(6)) }, Gravity.START, ClipDrawable.HORIZONTAL
                )
                val layers = LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(track, fill))
                layers.setId(0, android.R.id.background)
                layers.setId(1, android.R.id.progress)
                val knob = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(4), TEAL)
                    setSize(dp(24), dp(24))
                }
                seek.progressDrawable = layers
                seek.thumb = knob
                seek.setPadding(dp(12), dp(8), dp(12), dp(8))
            } catch (_: Throwable) {
                // stock slider look is fine as a fallback
            }
            seek.progress = ((start - min + step / 2) / step).coerceIn(0, (max - min) / step)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p * step + min
                    value.text = "$v%"
                    previews.forEach { it.setValue(v) }
                    if (fromUser) { save(v); live() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {
                    s.parent?.requestDisallowInterceptTouchEvent(true)
                    previews.forEach { it.start() }               // only the preview boxes move
                }
                override fun onStopTrackingTouch(s: SeekBar) {
                    previews.forEach { it.stop() }
                    live()
                }
            })
            view.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) })
        }

        fun reset() { seek.progress = (100 - min) / step; save(100); live() }

        fun release() { previews.forEach { it.stop() } }
    }

    // ---------------------------------------------------------------- setup row

    private inner class SetupRow(title: String, desc: String, private val onClick: () -> Unit) {
        val state = text("", 13f, MUTED, true)
        val view = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener { onClick() }
        }

        init {
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(title, 14f, FG, true))
            col.addView(text(desc, 11f, MUTED))
            view.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(state)
        }

        fun update(ok: Boolean) {
            state.text = if (ok) "●  On" else "Set up  ›"
            state.setTextColor(if (ok) GREEN else ORANGE)
        }
    }

    // ---------------------------------------------------------------- screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the app crashed last time, show the error instead of the normal screen (App.kt saved it).
        val saved = File(filesDir, "crash.txt")
        if (saved.exists()) {
            val trace = try { saved.readText() } catch (_: Throwable) { "unreadable crash file" }
            try { saved.delete() } catch (_: Throwable) {}
            showError("The app crashed last time:", trace)
            return
        }
        try {
            buildUi()
        } catch (t: Throwable) {
            showError("The screen could not be built:", Log.getStackTraceString(t))
        }
    }

    /** Plain, dependency-free error page so a crash can be read and screenshotted. */
    private fun showError(title: String, trace: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#0A0F1E"))
        }
        col.addView(TextView(this).apply {
            text = "Home Dragon\n$title"
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = trace.take(3500)
            textSize = 11f
            setTextColor(Color.parseColor("#FFB4A8"))
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        })
        col.addView(TextView(this).apply {
            text = "Open the app anyway"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2B6CFF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { recreate() }
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0A0F1E")); addView(col) })
    }

    @Suppress("DEPRECATION")
    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        requestHighRefresh()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // header: badge, title and status in one row
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(this).apply {
            text = "🐉"
            textSize = 22f
            gravity = Gravity.CENTER
            background = gradient(TEAL, BLUE, 14)
        }
        header.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(text("Home Dragon", 21f, FG, true))
        titles.addView(text("A live pet dragon for your home screen", 11.5f, MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pill = text("", 12f, MUTED, true).apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
        header.addView(pill)
        col.addView(header)

        toggle = text("", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { toggleService() }
        }
        col.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            .apply { topMargin = dp(14) })

        // settings
        val settings = card()
        val sHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sHead.addView(text("Dragon settings", 12f, MUTED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val reset = text("Reset all", 12f, ORANGE, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { sliders.forEach { it.reset() } }
        }
        sHead.addView(reset)
        settings.addView(sHead)
        settings.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))

        val quality = Slider(
            "Quality (frame rate)", "Steps of 10%. 100% = full refresh rate. Lower = less battery.",
            10, 100, Prefs.qualityPct(this), { Prefs.setQualityPct(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 10, previews = listOf(PreviewView(this, PreviewView.QUALITY), PreviewView(this, PreviewView.FLYING)), previewDp = 150
        )
        val particles = Slider(
            "Particles", "Fire, smoke and sparks. Separate from Quality.",
            10, 100, Prefs.particlePct(this), { Prefs.setParticlePct(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 10, previews = listOf(PreviewView(this, PreviewView.PARTICLES)), previewDp = 150
        )
        val size = Slider(
            "Dragon size", "How big the dragon is on your icons.",
            50, 150, Prefs.scalePct(this), { Prefs.setScalePct(this, it) }, { DragonService.instance?.view?.reloadPrefs() },
            step = 10, previews = listOf(PreviewView(this, PreviewView.SIZE)), previewDp = 170
        )
        val speed = Slider(
            "Dragon speed", "How fast it flies, walks and breathes fire.",
            50, 150, Prefs.speedPct(this), { Prefs.setSpeedPct(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 10
        )
        sliders.addAll(listOf(quality, particles, size, speed))
        settings.addView(quality.view)
        settings.addView(divider()); settings.addView(particles.view)
        settings.addView(divider()); settings.addView(size.view)
        settings.addView(divider()); settings.addView(speed.view)
        col.addView(settings)

        // flame colours: live preview and the colour picker (blue is the default)
        val flame = card()
        val fHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fHead.addView(text("Flame colours", 15f, FG, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        flame.addView(fHead)
        flame.addView(text("Up to 6 colours, blended from the hot core to the cooled tip. Blue is the default.", 11f, MUTED))
        val fPrev = PreviewView(this, PreviewView.PARTICLES)
        flamePreview = fPrev
        flame.addView(fPrev, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })
        val picker = FlamePicker(this) { cols, done ->
            Prefs.setFlameColors(this, cols)
            fPrev.reloadFlame()
            if (done) DragonService.instance?.view?.reloadFlame()
        }
        val fReset = text("Reset to blue", 12f, ORANGE, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { picker.reset() }
        }
        fHead.addView(fReset)
        flame.addView(picker.view)
        col.addView(flame)

        // setup rows
        val setup = card()
        setup.addView(text("Setup", 12f, MUTED, true))
        val r1 = SetupRow("Draw over other apps", "Lets the dragon appear on your home screen") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val r2 = SetupRow("Icon finder", "Lets the dragon see where your icons are") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Open Home Dragon icon finder and switch it on.", Toast.LENGTH_LONG).show()
        }
        val r3 = SetupRow("Background running", "Keeps the dragon alive when the screen is off") { openBackgroundSettings() }
        setupRows.addAll(listOf(r1, r2, r3))
        setup.addView(r1.view); setup.addView(r2.view); setup.addView(r3.view)
        col.addView(setup)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
            // Android 15 draws apps edge to edge: keep the content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, ins ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = ins.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                }
                ins
            }
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.insetsController?.setSystemBarsAppearance(
                    0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } catch (_: Throwable) {
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onPause() {
        // leaving the app: stop the previews and let the home-screen dragon come back
        try {
            sliders.forEach { it.release() }
            flamePreview?.stop()
            DragonService.appOpen = false
            DragonService.instance?.refreshHold()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    /** Ask for the display's highest refresh rate for this screen, so the previews can run at full speed. */
    @Suppress("DEPRECATION")
    private fun requestHighRefresh() {
        try {
            val d = windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        // while this screen is open the home-screen dragon stays hidden: only the preview boxes show a dragon
        DragonService.appOpen = true
        DragonService.instance?.refreshHold()
        try { flamePreview?.start() } catch (_: Throwable) {}
        try {
            if (setupRows.size == 3) refresh()
        } catch (t: Throwable) {
            showError("The status refresh failed:", Log.getStackTraceString(t))
        }
    }

    private fun a11yEnabled(): Boolean {
        val me = ComponentName(this, IconFinderService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        setupRows[0].update(overlay)
        setupRows[1].update(a11yEnabled())
        setupRows[2].update(pm.isIgnoringBatteryOptimizations(packageName))

        val running = DragonService.instance != null
        pill.text = if (running) "●  Running" else "○  Stopped"
        pill.setTextColor(if (running) GREEN else MUTED)
        pill.background = shape(if (running) Color.parseColor("#12332A") else CARD, 20, if (running) GREEN else STROKE)
        toggle.text = if (running) "Stop dragon" else "Start dragon"
        toggle.background = if (running) gradient(Color.parseColor("#E5533D"), Color.parseColor("#F08A3C"), 18)
        else gradient(TEAL, BLUE, 18)
    }

    private fun toggleService() {
        if (DragonService.instance != null) {
            startService(Intent(this, DragonService::class.java).setAction(DragonService.ACTION_STOP))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow drawing over other apps first.", Toast.LENGTH_SHORT).show()
                return
            }
            startForegroundService(Intent(this, DragonService::class.java))
        }
        window.decorView.postDelayed({ refresh() }, 500)
    }

    private fun openBackgroundSettings() {
        // Battery: ask for "No restrictions".
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        // HyperOS / MIUI: Autostart screen (not available on every build, so failure is fine).
        try {
            startActivity(Intent().setComponent(ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )))
        } catch (_: Exception) {
        }
        Toast.makeText(this, "Also lock Home Dragon in the recent apps list.", Toast.LENGTH_LONG).show()
    }
}
