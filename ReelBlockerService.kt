package com.amr.reelblocker

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Reel Blocker
 *
 * What it does, only while Instagram is on screen:
 *  1. Draws a solid block over the Reels tab in the bottom bar so it can't be tapped.
 *  2. If the Reels tab somehow becomes selected, taps Home instead.
 *  3. If the full-screen reel viewer opens and you did NOT come from a message thread,
 *     it presses Back right away.
 *  4. If the viewer opens straight from a message thread, that one reel plays normally.
 *     The moment you swipe to a different reel, it presses Back and returns you to the chat.
 *
 * Instagram is obfuscated and changes its screens often, so the view IDs below are best
 * guesses. If something stops working, set DEBUG = true, reproduce the problem, read the
 * "ReelBlocker" lines in logcat, and adjust the ID lists.
 */
class ReelBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelBlocker"
        private const val IG_PACKAGE = "com.instagram.android"

        // Set to true to log the view-ID tree of every Instagram screen (IDs and classes only, no text).
        private const val DEBUG = false

        // Bottom-bar tab IDs (without the "com.instagram.android:id/" prefix).
        private const val REELS_TAB_ID = "clips_tab"
        private const val FEED_TAB_ID = "feed_tab"

        // Any ID starting with one of these means the full-screen reel viewer is showing.
        private val VIEWER_ID_PREFIXES = listOf("clips_viewer", "clips_swipe_refresh")

        // The element holding the reel creator's name; used to notice a change of reel.
        private const val AUTHOR_ID = "clips_author_username"

        // Hints that a message thread is on screen.
        private val DM_ID_HINTS = listOf("thread_composer", "direct_thread", "row_thread")

        // How long after seeing a message thread a reel viewer still counts as "opened from the DM".
        private const val DM_GRACE_MS = 5000L

        // Ignore swipe events right after the viewer opens (opening animation).
        private const val SCROLL_GRACE_MS = 1500L
    }

    private class Scan {
        var reelsTab: AccessibilityNodeInfo? = null
        var feedTab: AccessibilityNodeInfo? = null
        var viewer = false
        var author: String? = null
        var dm = false
    }

    private var overlay: View? = null
    private var lastScanAt = 0L
    private var lastDmSeen = 0L
    private var lastViewerSeen = 0L
    private var allowedSince = 0L
    private var inAllowedMode = false
    private var firstReelKey: String? = null

    // Loop protection: never fire more than 4 actions in 4 seconds.
    private var lastActionAt = 0L
    private var burstStart = 0L
    private var actionBurst = 0
    private var pausedUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.uptimeMillis()

        if (event.packageName?.toString() != IG_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) removeOverlay()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScroll(event, now)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastScanAt < 120
        ) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG_PACKAGE) return

        if (DEBUG && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val sb = StringBuilder()
            dump(root, 0, sb)
            sb.toString().chunked(3500).forEach { Log.d(TAG, it) }
        }

        val s = Scan()
        scan(root, s, 0)

        if (s.dm) lastDmSeen = now
        if (s.dm && !s.viewer) resetViewerState()

        // 1. Cover the Reels tab so it can't be tapped.
        val reelsTab = s.reelsTab
        if (reelsTab != null) showOverlayOver(reelsTab) else removeOverlay()

        // 2. Reels tab is selected: send the user back to Home.
        if (reelsTab != null && isSelected(reelsTab)) {
            tryAct(now, "Reels blocked") {
                if (!click(s.feedTab)) performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }

        // 3 + 4. Full-screen reel viewer.
        if (s.viewer) {
            lastViewerSeen = now
            if (!inAllowedMode) {
                if (now - lastDmSeen <= DM_GRACE_MS) {
                    inAllowedMode = true
                    allowedSince = now
                    firstReelKey = null
                } else {
                    tryAct(now, "Reels blocked") { performGlobalAction(GLOBAL_ACTION_BACK) }
                    return
                }
            }
            val key = s.author
            if (!key.isNullOrBlank()) {
                val first = firstReelKey
                if (first == null) {
                    firstReelKey = key
                } else if (key != first) {
                    tryAct(now, "Only one reel at a time") { performGlobalAction(GLOBAL_ACTION_BACK) }
                }
            }
        } else if (inAllowedMode && now - lastViewerSeen > 1500) {
            resetViewerState()
        }
    }

    /** Swiping inside the viewer while in "single reel from DM" mode sends the user back. */
    private fun handleScroll(event: AccessibilityEvent, now: Long) {
        if (!inAllowedMode) return
        if (now - allowedSince < SCROLL_GRACE_MS) return
        val id = event.source?.viewIdResourceName?.substringAfter(":id/") ?: return
        if (VIEWER_ID_PREFIXES.any { id.startsWith(it) }) {
            tryAct(now, "Only one reel at a time") { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
    }

    // ---------------------------------------------------------------- tree scan

    private fun scan(node: AccessibilityNodeInfo?, s: Scan, depth: Int) {
        if (node == null || depth > 40) return

        val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
        if (id.isNotEmpty()) {
            when {
                id == REELS_TAB_ID -> s.reelsTab = node
                id == FEED_TAB_ID -> s.feedTab = node
                id == AUTHOR_ID -> {
                    s.viewer = true
                    s.author = node.text?.toString() ?: node.contentDescription?.toString()
                }
                VIEWER_ID_PREFIXES.any { id.startsWith(it) } -> s.viewer = true
                DM_ID_HINTS.any { id.contains(it) } -> s.dm = true
            }
        } else if (s.reelsTab == null) {
            // Fallback: a bottom-bar item whose label is "Reels".
            val desc = node.contentDescription?.toString()
            if (desc.equals("Reels", ignoreCase = true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val screenH = resources.displayMetrics.heightPixels
                if (r.top > screenH * 0.85) s.reelsTab = node
            }
        }

        for (i in 0 until node.childCount) scan(node.getChild(i), s, depth + 1)
    }

    private fun isSelected(node: AccessibilityNodeInfo): Boolean =
        node.isSelected || node.parent?.isSelected == true

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return false
    }

    // ---------------------------------------------------------------- actions

    private fun tryAct(now: Long, toast: String?, block: () -> Unit) {
        if (now < pausedUntil) return
        if (now - lastActionAt < 700) return
        if (now - burstStart > 4000) {
            burstStart = now
            actionBurst = 0
        }
        actionBurst++
        if (actionBurst > 4) {
            pausedUntil = now + 6000
            Log.w(TAG, "Too many actions in a row, pausing for 6s")
            return
        }
        lastActionAt = now
        block()
        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun resetViewerState() {
        inAllowedMode = false
        firstReelKey = null
    }

    // ---------------------------------------------------------------- overlay over the Reels tab

    private fun showOverlayOver(tab: AccessibilityNodeInfo) {
        val r = Rect()
        tab.getBoundsInScreen(r)
        if (r.isEmpty) {
            removeOverlay()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

        val existing = overlay
        if (existing == null) {
            val v = View(this)
            v.setBackgroundColor(color)
            v.setOnTouchListener { _, _ -> true } // swallow taps
            val lp = WindowManager.LayoutParams(
                r.width(),
                r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = r.left
            lp.y = r.top
            try {
                wm.addView(v, lp)
                overlay = v
            } catch (e: Exception) {
                Log.w(TAG, "Could not add overlay", e)
            }
        } else {
            val lp = existing.layoutParams as WindowManager.LayoutParams
            if (lp.x != r.left || lp.y != r.top || lp.width != r.width() || lp.height != r.height()) {
                lp.x = r.left
                lp.y = r.top
                lp.width = r.width()
                lp.height = r.height()
                try {
                    wm.updateViewLayout(existing, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not update overlay", e)
                }
            }
            existing.setBackgroundColor(color)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- debug

    private fun dump(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > 25) return
        val id = node.viewIdResourceName
        if (id != null) {
            sb.append("  ".repeat(depth)).append(id.substringAfter(":id/"))
                .append(" | ").append(node.className)
                .append(if (node.isSelected) " [selected]" else "")
                .append('\n')
        }
        for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1, sb)
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
