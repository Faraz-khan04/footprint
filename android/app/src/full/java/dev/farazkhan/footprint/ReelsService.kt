package dev.farazkhan.footprint

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Leaves YouTube Shorts, Instagram Reels and Snapchat Spotlight by pressing Back for you.
 *
 * Only in the "full" edition. Android only sends it events from the apps listed in
 * res/xml/reels_service.xml, and it only reads view ids, which tab is selected, and that tab's
 * short label. Nothing is stored except a count of how often a feed was closed, and nothing
 * leaves the phone (the app has no internet permission).
 */
class ReelsService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { runCatching { inspect() } }

    private var enabled: Set<String> = emptySet()
    /** Nothing happens until the user agreed to the in-app disclosure (ReelsDisclosure). */
    private var consent = false
    private var whenMode = 0
    private var rules = Rules.RuleSet()
    private var loadedAt = 0L

    private var lastBack = 0L
    /** Backs in a row that didn't get out: stop for a minute rather than trap anyone in a loop. */
    private var stuck = 0
    private var pausedUntil = 0L

    /**
     * Android's floating accessibility button switches this service on and off but never shows which
     * it is, so say it plainly every time it changes.
     */
    override fun onServiceConnected() {
        reload()
        toast("Reels and Shorts blocking: ON")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { Toast.makeText(applicationContext, "Reels and Shorts blocking: OFF", Toast.LENGTH_SHORT).show() }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in ReelsMatch.PACKAGES) return
        // Content events arrive in bursts while a feed scrolls; look once the burst settles.
        handler.removeCallbacks(check)
        handler.postDelayed(check, 200)
    }

    override fun onInterrupt() { handler.removeCallbacks(check) }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun reload() {
        val cfg = Config(this)
        RuleStore.applyDue(cfg, System.currentTimeMillis())
        enabled = RuleStore.reelsEnabled(cfg)
        consent = RuleStore.reelsConsent(cfg)
        whenMode = RuleStore.reelsWhen(cfg)
        rules = runCatching { RuleStore.load(this, cfg) }.getOrDefault(Rules.RuleSet())
        loadedAt = SystemClock.uptimeMillis()
    }

    /** "Always", or only while a blocked time or a locked study session is running. */
    private fun activeNow(): Boolean {
        if (whenMode == 0) return true
        val cfg = Config(this)
        if (Focus.active(cfg) && cfg.focusLock) return true
        val now = System.currentTimeMillis()
        return rules.enabled && Rules.activeSchedule(rules.schedules, RuleStore.dayOfWeek(now), RuleStore.minuteOfDay(now)) != null
    }

    private fun inspect() {
        val t = SystemClock.uptimeMillis()
        if (t - loadedAt > 5_000L) reload()
        if (!consent) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in ReelsMatch.PACKAGES) return
        val nodes = collect(root)
        ReelsDiag.record(pkg, ReelsMatch.shortIds(nodes))
        if (enabled.isEmpty() || t < pausedUntil || !activeNow()) return
        val target = ReelsMatch.match(pkg, nodes, enabled) ?: run { stuck = 0; return }
        if (t - lastBack < 1_200L) return
        if (stuck >= 3) {
            pausedUntil = t + 60_000L; stuck = 0
            toast("Couldn't close ${target.name}. Trying again in a minute.")
            return
        }
        stuck++
        lastBack = t
        performGlobalAction(GLOBAL_ACTION_BACK)
        runCatching {
            EventStore.get(this).point("reels_blocked", System.currentTimeMillis(), pkg,
                ReelsMatch.TARGETS.indexOf(target).toDouble())
        }
        if (stuck == 1) toast("${target.name} blocked by Footprint")
        handler.postDelayed(check, 900)                  // still there? (a tab, not a page) try once more
    }

    /** A bounded walk of what's on screen. Labels are read only for selected elements (tabs). */
    private fun collect(root: AccessibilityNodeInfo): List<ReelsMatch.Node> {
        val dm = resources.displayMetrics
        val screen = (dm.widthPixels.toFloat() * dm.heightPixels).coerceAtLeast(1f)
        val out = ArrayList<ReelsMatch.Node>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val r = Rect()
        while (queue.isNotEmpty() && out.size < 700) {
            val n = queue.removeFirst()
            n.getBoundsInScreen(r)
            val selected = n.isSelected
            val label = if (selected) (n.contentDescription ?: n.text)?.toString()?.take(40) else null
            out.add(ReelsMatch.Node(n.viewIdResourceName, label, selected, n.isVisibleToUser,
                (r.width().toFloat() * r.height()) / screen))
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun toast(s: String) = runCatching { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
