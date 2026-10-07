package com.ramim.homedragon

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.min

/**
 * Finds home screen icon positions. It only reads node bounds (and whether a node is
 * clickable and labelled). It never reads or stores text.
 */
class IconFinderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanQueued = false
    private var lastScan = 0L
    private var lastScrollX = -1
    private val settleScan = Runnable { scanQueued = false; lastScan = System.currentTimeMillis(); scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        IconRegistry.launcherPkg = packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        IconRegistry.serviceActive = true
        IconRegistry.listener?.invoke()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        IconRegistry.serviceActive = false
        IconRegistry.icons = emptyList()
        IconRegistry.listener?.invoke()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private var recentsEvt = false      // launcher reported a recents-like screen class
    private var recentsNode = false     // recents views seen in the launcher's node tree, or no icons at all
    private var recentsEvtAt = 0L
    private var missCount = 0           // consecutive settled scans that found no icons
    private var lastScrollMs = 0L
    private val recents: Boolean get() = recentsEvt || recentsNode

    private fun refreshHome() {
        homeFromWindows()?.let { setHome(it) }
    }

    /**
     * Is the home screen what the user is looking at? Looks at the top-most application window
     * (keyboards, status bar, our own overlay and picture-in-picture windows do not count).
     * Returns null when the window list is unavailable.
     */
    private fun homeFromWindows(): Boolean? {
        val launcher = IconRegistry.launcherPkg ?: return null
        try {
            for (w in windows) {                         // ordered top to bottom
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.isInPictureInPictureMode) continue
                val p = w.root?.packageName?.toString()
                if (p == packageName) continue
                if (p == null) return false             // unreadable window on top (secure app): not home
                return p == launcher && !recents
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun setHome(h: Boolean) {
        if (h != IconRegistry.onHome) {
            IconRegistry.onHome = h
            IconRegistry.listener?.invoke()
            if (h) queueScan()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val launcher = IconRegistry.launcherPkg ?: return
        val type = event.eventType

        // Window list changes carry no package name, so they are handled first.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            homeFromWindows()?.let { setHome(it) }
            return
        }
        val pkg = event.packageName?.toString() ?: return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore system overlays that do not change what the user is looking at.
            if (pkg == "com.android.systemui" || pkg == packageName) return
            if (pkg == launcher) {
                recentsEvt = event.className?.let { it.contains("recent", true) || it.contains("overview", true) } == true
                recentsEvtAt = System.currentTimeMillis()
            }
            setHome(homeFromWindows() ?: (pkg == launcher && !recents))
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg == launcher) {
            val dx = event.scrollDeltaX; val dy = event.scrollDeltaY
            val horizontal = if (dx != 0 || dy != 0) abs(dx) >= 2 && abs(dx) > abs(dy)
            else event.maxScrollX > 0 && event.scrollX != lastScrollX
            lastScrollX = event.scrollX
            lastScrollMs = System.currentTimeMillis()
            if (horizontal) {
                IconRegistry.swipeListener?.invoke()
                // rescan shortly after the last scroll event, i.e. once the page has settled
                handler.removeCallbacks(settleScan)
                handler.postDelayed(settleScan, 150)
                return
            }
        }
        if (pkg == launcher) queueScan()
    }

    private fun queueScan() {
        if (scanQueued) return
        scanQueued = true
        val wait = (300 - (System.currentTimeMillis() - lastScan)).coerceAtLeast(60)
        handler.postDelayed({
            scanQueued = false
            lastScan = System.currentTimeMillis()
            scan()
        }, wait)
    }

    private fun scan() {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (root.packageName?.toString() != IconRegistry.launcherPkg) return

        val d = resources.displayMetrics
        val minSide = 44 * d.density
        val maxSide = 140 * d.density
        val found = ArrayList<RectF>()
        val seen = HashSet<Long>()
        val b = Rect()
        var sawRecents = false

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 14) return
            val cls = n.className?.toString() ?: ""
            if (!sawRecents && n.isVisibleToUser) {
                val id = n.viewIdResourceName ?: ""
                if (id.contains("recents", true) || id.contains("overview", true) || id.contains("task_view", true) ||
                    id.contains("clear_all", true) || cls.contains("RecentsView") || cls.contains("TaskView")
                ) sawRecents = true
            }
            val isWidget = cls.contains("WidgetHostView")
            val labelled = !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty()
            val isIconNode = (n.isClickable || n.isLongClickable) && labelled
            if (n.isVisibleToUser && (isWidget || isIconNode)) {
                n.getBoundsInScreen(b)
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val onScreen = b.left >= 0 && b.right <= d.widthPixels && b.top >= 0 && b.bottom <= d.heightPixels
                if (isIconNode && w in minSide..maxSide && h in minSide..(maxSide * 1.3f) && h / w in 0.6f..1.6f && onScreen) {
                    val key = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(key)) {
                        // The node covers icon + label. Keep the icon part.
                        val iconSide = min(w * 0.78f, h * 0.72f)
                        val cx = b.exactCenterX()
                        val top = b.top + h * 0.05f
                        found.add(RectF(cx - iconSide / 2, top, cx + iconSide / 2, top + iconSide))
                    }
                } else if ((isWidget || isIconNode) && w > maxSide * 0.9f && w <= d.widthPixels * 0.99f &&
                    h >= minSide && h <= d.heightPixels * 0.45f && onScreen
                ) {
                    // Widget or large folder: bigger ground. The whole rectangle is kept.
                    val key = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(key)) {
                        found.add(RectF(b))
                        return   // do not treat things inside a widget as separate icons
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        try { walk(root, 0) } catch (e: Exception) { return }

        // No icons for two settled scans in a row also means the home screen is covered (recents etc.).
        val settled = System.currentTimeMillis() - lastScrollMs > 600
        if (found.size >= 4) missCount = 0 else if (settled) missCount++
        // a recents flag from an event is dropped once icons are plainly visible again for a while
        val evtStale = recentsEvt && found.size >= 4 && !sawRecents && System.currentTimeMillis() - recentsEvtAt > 4000
        if (evtStale) recentsEvt = false
        val nowRecents = sawRecents || missCount >= 2
        val changed = nowRecents != recentsNode || evtStale
        recentsNode = nowRecents
        if (found.size >= 4 && !sawRecents) {
            IconRegistry.icons = found
            IconRegistry.listener?.invoke()
        }
        if (changed) refreshHome()
    }
}
