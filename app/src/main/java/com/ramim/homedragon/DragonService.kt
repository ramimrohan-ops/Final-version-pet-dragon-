package com.ramim.homedragon

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the dragon view attached to a transparent overlay window for as long as the service lives.
 * The view and all of its bitmaps stay in RAM. When the screen is off (or the phone is locked, or
 * you are not on the home screen) only the frame loop stops, so nothing is reloaded on wake-up.
 */
class DragonService : Service() {

    companion object {
        @Volatile var instance: DragonService? = null
        @Volatile var appOpen = false         // the Home Dragon app screen is in front: the overlay dragon stays hidden
        const val ACTION_STOP = "com.ramim.homedragon.STOP"
        private const val CHANNEL = "dragon"
    }

    private lateinit var wm: WindowManager
    var view: DragonView? = null
        private set
    private var screenActive = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> screenActive = false
                // If there is no lock screen the user is already on the home screen.
                Intent.ACTION_SCREEN_ON -> screenActive = !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                Intent.ACTION_USER_PRESENT -> screenActive = true
            }
            apply()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dragon", NotificationManager.IMPORTANCE_MIN))
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, 1, buildNotification(), type)

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" first.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val v = DragonView(this)
        view = v
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        requestHighRefresh(lp)
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not draw the overlay: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        IconRegistry.listener = {
            view?.setIcons(IconRegistry.icons)
            apply()
        }
        IconRegistry.swipeListener = { view?.onSwipe() }
        v.onFullyHidden = {
            if (IconRegistry.serviceActive && !IconRegistry.onHome && screenActive) {
                v.visibility = View.GONE
                v.pause()
            }
        }
        v.setIcons(IconRegistry.icons)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)

        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        screenActive = !km.isKeyguardLocked
        apply()
    }

    /** Ask for the display's highest refresh rate at the current resolution (120 Hz on this phone). */
    private fun requestHighRefresh(lp: WindowManager.LayoutParams) {
        try {
            val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) {
                lp.preferredDisplayModeId = best.modeId
                lp.preferredRefreshRate = best.refreshRate
            }
        } catch (_: Exception) {
        }
    }

    /** One place that decides whether the frame loop runs. */
    private fun apply() {
        val v = view ?: return
        if (appOpen) {                             // the app is open: only the preview boxes show a dragon
            v.visibility = View.GONE
            v.pause()
            return
        }
        if (!screenActive) {                       // screen off or locked: stop everything at once
            v.visibility = View.GONE
            v.pause()
            return
        }
        val homeOk = !IconRegistry.serviceActive || IconRegistry.onHome
        if (homeOk) {
            v.visibility = View.VISIBLE
            v.resume()
            v.setShown(true)
        } else {
            v.setShown(false)                      // fades out, then onFullyHidden pauses the loop
        }
    }

    /** The app screen opened or closed: hide or bring back the home-screen dragon. */
    fun refreshHold() = apply()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setEnabled(this, true)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        IconRegistry.listener = null
        IconRegistry.swipeListener = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        view?.let {
            it.pause()
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        view = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DragonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Dragon is on your home screen")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
