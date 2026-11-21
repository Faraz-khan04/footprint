package dev.farazkhan.footprint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.io.OutputStreamWriter

/**
 * Settings, as a home screen of seven sections, each on its own page:
 * permissions, nudges and reviews, app rules, blocked times, Reels and Shorts (full edition),
 * goals, and data and privacy. The data disclosure Google Play requires comes first until accepted.
 *
 * Built in code (no XML layouts) from SettingsUi, in the dashboard's style. Every page is rebuilt
 * after a change, so what's shown is always what's stored, including changes waiting for midnight.
 */
class SettingsActivity : Activity() {

    companion object {
        /** Set when the app opens Setup on its own because it isn't ready yet. */
        const val EXTRA_ONBOARDING = "onboarding"
    }

    /** Each page gets a two-line title in the dashboard's style: solid, then outlined. */
    private enum class Page(val number: String, val title: String, val solid: String, val outline: String) {
        HOME("", "Settings", "Make it", "yours."),
        PERMISSIONS("01", "Permissions", "What it", "can see."),
        NUDGES("02", "Reminders", "When it", "speaks up."),
        RULES("03", "App limits", "Know your", "limits."),
        BLOCKED("04", "Blocked times", "Hours", "off."),
        REELS("05", "Reels and Shorts", "No more", "reels."),
        GOALS("06", "Goals", "Your", "goals."),
        DATA("07", "Data and privacy", "Your data,", "your call.")
    }

    private val onboarding get() = intent?.getBooleanExtra(EXTRA_ONBOARDING, false) == true

    private lateinit var cfg: Config
    private lateinit var ui: SettingsUi
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private var page = Page.HOME
    private var pendingExport: String? = null
    private var showAllApps = false
    private var showAllDistract = false
    private var showHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        ui = SettingsUi(this)
        body = ui.column().apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(48)) }
        scroll = ScrollView(this).apply {
            background = ui.gridBackground()
            fitsSystemWindows = true
            isFillViewport = true
            addView(body)
        }
        setContentView(scroll)
        savedInstanceState?.getString("page")?.let { p -> Page.values().firstOrNull { it.name == p }?.let { page = it } }
        render(keepScroll = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
    }

    override fun onResume() {
        LockService.sync(this)
        super.onResume()
        // First-run setup ends by itself once the essentials are done: the disclosure and usage access.
        // Steps, notifications and battery stay optional; the dashboard reminds you about them.
        if (onboarding && cfg.disclosureAccepted && UsageCollector.hasAccess(this)) {
            CollectWorker.schedule(this)
            CollectWorker.runNow(this)
            toast("You're set up. Opening your dashboard.")
            backToDashboard()
            return
        }
        render()                                          // e.g. back from granting a permission
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    @Deprecated("Fine for a plain Activity; predictive back isn't used here")
    override fun onBackPressed() {
        if (page != Page.HOME) open(Page.HOME) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- navigation
    private fun open(p: Page) {
        page = p
        showAllApps = false; showAllDistract = false; showHidden = false
        render(keepScroll = false)
    }

    /** Rebuild the current page. Keeps the scroll position unless moving to another page. */
    private fun render(keepScroll: Boolean = true) {
        val y = scroll.scrollY
        body.removeAllViews()
        RuleStore.applyDue(cfg, System.currentTimeMillis())
        when (page) {
            Page.HOME -> home()
            Page.PERMISSIONS -> permissionsPage()
            Page.NUDGES -> nudgesPage()
            Page.RULES -> rulesPage()
            Page.BLOCKED -> blockedPage()
            Page.REELS -> reelsPage()
            Page.GOALS -> goalsPage()
            Page.DATA -> dataPage()
        }
        scroll.post { scroll.scrollTo(0, if (keepScroll) y else 0) }
    }

    private fun add(v: View, top: Int = 0) = body.addView(v, ui.lp(top = top))

    /** Back link, then the dashboard's section style: spaced caps, a solid line and an outlined one. */
    private fun pageHeader(p: Page, intro: String) {
        add(ui.text("‹  SETTINGS", 13f, ui.rust, ui.monoBold, 0.14f).apply {
            setPadding(0, ui.dp(4), ui.dp(20), ui.dp(14))
            isClickable = true; setOnClickListener { open(Page.HOME) }
        }.let { v -> ui.row().apply { addView(v) } })
        add(ui.kicker("${p.number} / ${p.title}"), 14)
        add(ui.bigTitle(p.solid, p.outline, 50f), 14)
        add(ui.text(intro, 15f, ui.mute, ui.mono), 16)
    }

    // ---------------------------------------------------------------- state helpers
    private fun usageOk() = UsageCollector.hasAccess(this)
    private fun stepsOk() = !StepCollector.hasSensor(this) || StepCollector.hasPermission(this)
    private fun notifOk() = Nudges.canPost(this)
    /**
     * Android has three levels. "Restricted" (background usage switched off) really breaks things: no
     * step readings, no reminders. "Optimized" (switch on, the default) works, just later than every
     * 15 minutes. "Unrestricted" is best. Only Restricted counts as something that needs you.
     */
    private fun batteryRestricted() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        runCatching { getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted }.getOrDefault(false)
    private fun batteryUnrestricted() = runCatching { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(true)
    private fun batteryOk() = !batteryRestricted()
    /** Where the Unrestricted choice is: Android 14+ hides it behind the words next to the switch. */
    private fun unrestrictedHow() = if (Build.VERSION.SDK_INT >= 34)
        "Tap the words \u201cAllow background usage\u201d (not the switch), then choose Unrestricted."
    else "Choose Unrestricted (on some phones: Don\u2019t optimise)."

    private fun overlayOk() = LockService.canDrawOver(this)

    private fun clock(m: Int) = if (m < 0) "Off" else "%02d:%02d".format(m / 60, m % 60)
    private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private fun fmtMin(m: Int): String = when {
        m <= 0 -> "Off"
        m % 60 == 0 -> "${m / 60}h"
        m > 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }

    private fun label(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** "  →  1h from tomorrow" when a looser value is waiting, else "". */
    private fun waiting(key: String, show: (Int) -> String): String {
        val v = RuleStore.pendingValue(cfg, key) ?: return ""
        val i = (v as? Number)?.toInt() ?: v.toString().toIntOrNull() ?: return ""
        return " · Changes to ${show(i)} tomorrow"
    }

    private fun waitingBool(key: String, onText: String, offText: String): String {
        val v = RuleStore.pendingValue(cfg, key) ?: return ""
        return " · Turns ${if (v == true || v.toString() == "true") onText else offText} tomorrow"
    }

    private fun noteColor(n: String) = if (n.contains("tomorrow")) ui.rust else ui.mute

    /** Collection settings change what gets read and how sleep is worked out, so redo the current pass. */
    private fun changed() { CollectWorker.runNow(this); render() }

    /** A rule changed: say so if it waits for midnight, restart the watcher, redraw. */
    private fun after(change: RuleStore.Change, what: String = "That change") {
        if (change == RuleStore.Change.TOMORROW) toast("Saved. $what makes things easier, so it starts tomorrow.")
        LockService.sync(this)
        body.post { render() }
    }

    private fun recentApps(days: Int) = runCatching {
        EventStore.get(this).recentApps(System.currentTimeMillis() - days * 86_400_000L)
    }.getOrDefault(emptyList())

    // ================================================================ HOME
    private fun home() {
        add(ui.text("foot\nprint", 18f, ui.paper, ui.heavy).apply { setLineSpacing(0f, 0.85f) })
        add(ui.kicker("Settings, all on this phone"), 30)
        add(ui.bigTitle(Page.HOME.solid, Page.HOME.outline, 56f), 14)
        add(ui.text("Nothing here ever leaves your phone.", 15f, ui.mute, ui.mono), 16)

        if (!cfg.disclosureAccepted) add(disclosureCard(), 22)
        else add(healthCard(), 22)

        val rules = runCatching { RuleStore.load(this, cfg) }.getOrDefault(Rules.RuleSet())
        val withRules = rules.watched().size
        val schedules = RuleStore.schedules(cfg).count { it.on }
        val missing = listOf(usageOk(), stepsOk(), notifOk(), batteryOk()).count { !it }

        add(ui.groupLabel("Sections"), 6)
        val cards = ArrayList<View>()
        cards += ui.sectionCard("01", "Permissions",
            if (missing == 0) "Everything is allowed." else "$missing thing${if (missing > 1) "s" else ""} still to allow.",
            alert = missing > 0 && cfg.disclosureAccepted) { open(Page.PERMISSIONS) }
        cards += ui.sectionCard("02", "Reminders",
            (if (cfg.nudgesEnabled) "Warnings on" else "Warnings off") +
                " · daily check-in ${if (cfg.reviewHour < 0) "off" else "%02d:00".format(cfg.reviewHour)}" +
                " · weekly summary ${if (cfg.weeklyDay < 0) "off" else dayNames[cfg.weeklyDay - 1]}",
            alert = false) { open(Page.NUDGES) }
        cards += ui.sectionCard("03", "App limits",
            when {
                !cfg.limitsEnabled -> "Paused. Your limits are saved."
                withRules == 0 -> "Put time limits on apps. None yet."
                else -> "$withRules app${if (withRules > 1) "s" else ""} with limits" + (if (RuleStore.delayOn(cfg)) " · cheat-proof on" else "")
            },
            alert = withRules > 0 && !overlayOk()) { open(Page.RULES) }
        cards += ui.sectionCard("04", "Blocked times",
            (if (schedules == 0) "No blocked times" else "$schedules blocked time${if (schedules > 1) "s" else ""}") +
                " · ${rules.distract.size} distracting app${if (rules.distract.size == 1) "" else "s"}",
            alert = false) { open(Page.BLOCKED) }
        if (BuildConfig.REELS) {
            val on = reelsServiceOn()
            val feeds = ReelsMatch.TARGETS.filter { it.key in RuleStore.reelsEnabled(cfg) }.joinToString(", ") { it.name.substringBefore(' ') }
            cards += ui.sectionCard("05", "Reels and Shorts",
                if (on) "On for ${feeds.ifEmpty { "nothing yet" }}" else "Off. Can close Reels and Shorts for you.",
                alert = false) { open(Page.REELS) }
        }
        cards += ui.sectionCard(if (BuildConfig.REELS) "06" else "05", "Goals",
            "%,d steps a day · ".format(cfg.stepsGoal) +
                (if (cfg.screenGoalMin > 0) "${fmtMin(cfg.screenGoalMin)} screen time goal" else "no screen time goal"),
            alert = false) { open(Page.GOALS) }
        cards += ui.sectionCard(if (BuildConfig.REELS) "07" else "06", "Data and privacy",
            "Keeps ${if (cfg.retentionDays >= 365) "1 year" else "${cfg.retentionDays} days"} of history" +
                (if (cfg.hiddenApps.isNotEmpty()) " · ${cfg.hiddenApps.size} hidden" else "") + " · export or delete",
            alert = false) { open(Page.DATA) }
        cards.forEachIndexed { i, c -> add(c, if (i == 0) 0 else 14) }

        add(ui.button("Back to dashboard") { backToDashboard() }, 28)
        add(ui.text("Footprint ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()}" +
            (if (BuildConfig.REELS) "" else " · store edition") + " · works fully offline", 12f, ui.mute, ui.mono).apply {
            gravity = Gravity.CENTER
        }, 18)
        // A small, quiet mark under the version: a personal tribute, not a feature. Decorative only.
        add(ui.row().apply {
            gravity = Gravity.CENTER
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.tribute_mark)
                alpha = 0.9f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        }, 12)
    }

    /** Shown until accepted: what's collected, before any permission is asked for. */
    private fun disclosureCard(): View = ui.column(18).apply {
        background = ui.shadowed(ui.card, ui.rust, ui.rust)
        addView(ui.text("WHAT FOOTPRINT COLLECTS", 12f, ui.rust, ui.monoBold, 0.12f))
        addView(ui.text(
            "To show your screen time, sleep and habits, Footprint collects on this phone:\n\n" +
                "•  which apps you use and for how long, and when you unlock the phone\n" +
                "•  when your screen turns on and off, and which apps send you notifications\n" +
                "•  your step count from the phone's step sensor\n" +
                "•  if you use them: your answers when an app asks why you're opening it, what you do on the " +
                "limit screens, and the study sessions you time\n\n" +
                "This stays on your phone, in the app's private storage. The app has no internet permission, " +
                "so it cannot send your data anywhere, and nothing is shared with anyone. " +
                "You can export or delete all of it at any time.", 14f, ui.paper, ui.sans), ui.lp(top = 12))
        addView(ui.button("Agree and continue") { accept() }, ui.lp(top = 18))
    }

    /** What still needs doing, with the fix one tap away, and whether collection is running. */
    private fun healthCard(): View {
        val items = ArrayList<View>()
        if (!usageOk()) items += ui.statusRow(false, "Allow screen time access", "Footprint can't show anything without it", "Allow") { openUsageAccess() }
        if (!batteryOk()) items += ui.statusRow(false, "Turn background use back on", "It's switched off, so reminders and the widget stop whenever Footprint is closed. Turn on \u201cAllow background usage\u201d.", "Fix") { askBattery() }
        if (!notifOk()) items += ui.statusRow(false, "Turn on notifications", "So reminders can reach you", "Allow") { askNotifications() }
        if (!stepsOk()) items += ui.statusRow(false, "Turn on step counting", "Uses your phone's step sensor", "Allow") { askSteps() }
        val events = runCatching { EventStore.get(this).count() }.getOrDefault(0L)
        val last = if (cfg.lastCollectAt == 0L) "not yet" else android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(cfg.lastCollectAt))
        val head = ui.column().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            addView(ui.text(if (items.isEmpty()) "All set" else "${items.size} thing${if (items.size > 1) "s" else ""} need${if (items.size > 1) "" else "s"} you",
                22f, if (items.isEmpty()) ui.green else ui.rust, ui.heavy))
            addView(ui.text("Last updated $last", 12f, ui.mute, ui.mono), ui.lp(top = 6))
        }
        return ui.group(head, *items.toTypedArray())
    }

    /** Where steps come from, so a tester can tell whether Play services' recording works. */
    private fun stepsSource(): String {
        val st = cfg.recordingStatus
        val since = cfg.recordingSince
        val fmt = android.text.format.DateFormat.getTimeFormat(this)
        return when {
            st == "ok" -> "On. Recorded hour by hour by Google Play services since " +
                java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(since)) +
                ", last read " + fmt.format(java.util.Date(cfg.recordingSyncedTo))
            st.isEmpty() -> "On. Hourly recording starts the next time Footprint refreshes"
            else -> "On, phone sensor only (updates when you open Footprint). Hourly recording failed: $st"
        }
    }

    // ================================================================ 01 PERMISSIONS
    private fun permissionsPage() {
        pageHeader(Page.PERMISSIONS, "Only screen time access is needed. The rest switch on extra features.")
        add(ui.groupLabel("Needed"))
        add(ui.group(
            ui.statusRow(usageOk(), "Screen time access", if (usageOk()) "On" else "Find Footprint in the list and turn it on", "Allow") { openUsageAccess() },
            when {
                batteryRestricted() -> ui.statusRow(false, "Run in the background",
                    "Switched off. Turn on \u201cAllow background usage\u201d, or reminders and the widget stop when Footprint is closed.", "Fix") { askBattery() }
                batteryUnrestricted() -> ui.statusRow(true, "Run in the background", "Unrestricted.")
                else -> ui.statusRow(true, "Run in the background",
                    "On (Optimized). Fine for most phones. If reminders arrive late: ${unrestrictedHow()}", "Improve") { askBattery() }
            }
        ))
        add(ui.groupLabel("Optional"))
        add(ui.group(
            ui.statusRow(stepsOk(), "Steps",
                if (!StepCollector.hasSensor(this)) "This phone has no step sensor" else if (stepsOk()) stepsSource() else "Counts your steps", "Allow") { askSteps() },
            ui.statusRow(notifOk(), "Notifications", if (notifOk()) "On" else "So reminders can reach you", "Allow") { askNotifications() },
            ui.statusRow(overlayOk(), "Show over other apps", if (overlayOk()) "On. Only used to show the limit screen" else "Needed for app limits and study sessions", "Allow") { openOverlay() }
        ))
        val events = runCatching { EventStore.get(this).count() }.getOrDefault(0L)
        val last = if (cfg.lastCollectAt == 0L) "not yet" else android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(cfg.lastCollectAt))
        add(ui.groupLabel("Status"))
        add(ui.group(ui.column().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            addView(ui.text("%,d".format(events), 30f, ui.paper, ui.heavy))
            addView(ui.note(if (!cfg.disclosureAccepted) "Nothing is being collected yet." else "things recorded, all on this phone. Last updated $last."))
        }))
    }

    // ================================================================ 02 NUDGES
    private fun nudgesPage() {
        pageHeader(Page.NUDGES, "When Footprint sends you a notification.")
        add(ui.groupLabel("Warnings"))
        add(ui.group(
            ui.switchRow("Warn me before I usually waste time", "Based on the times you said weren't worth it", cfg.nudgesEnabled) { cfg.nudgesEnabled = it; changed() },
            if (cfg.nudgesEnabled) ui.segmentRow("How early", null,
                listOf("2 min" to 2, "5 min" to 5, "10 min" to 10, "15 min" to 15), cfg.nudgeLeadMin) { cfg.nudgeLeadMin = it; changed() } else null
        ))
        add(ui.groupLabel("Do not disturb"))
        add(ui.group(
            ui.valueRow("From", "No warnings between these times", clock(cfg.quietFrom), { pickTime(cfg.quietFrom) { cfg.quietFrom = it; changed() } }),
            ui.valueRow("Until", null, clock(cfg.quietTo), { pickTime(cfg.quietTo) { cfg.quietTo = it; changed() } })
        ))
        add(ui.groupLabel("Check-ins"))
        val reviewOn = cfg.reviewHour >= 0
        add(ui.group(
            ui.switchRow("Daily check-in", "A reminder to rate a few of today's sessions", reviewOn) {
                cfg.reviewHour = if (it) 21 else -1; changed()
            },
            if (reviewOn) ui.valueRow("Check-in time", null, "%02d:00".format(cfg.reviewHour), {
                pickTime(cfg.reviewHour * 60) { m -> cfg.reviewHour = m / 60; changed() }
            }) else null,
            ui.segmentRow("Weekly summary", "How this week went compared with last week",
                listOf("Off" to -1, "Fri" to 5, "Sat" to 6, "Sun" to 7), cfg.weeklyDay) {
                cfg.weeklyDay = it
                cfg.weeklyFiredFor = WeekMath.lastOccurrence(it, cfg.weeklyMinute, System.currentTimeMillis())
                changed()
            },
            if (cfg.weeklyDay > 0) ui.valueRow("Summary time", null, clock(cfg.weeklyMinute), {
                pickTime(cfg.weeklyMinute) { m ->
                    cfg.weeklyMinute = m
                    cfg.weeklyFiredFor = WeekMath.lastOccurrence(cfg.weeklyDay, m, System.currentTimeMillis())
                    changed()
                }
            }) else null,
            ui.segmentRow("Morning summary", "Yesterday in one quiet notification: time, unlocks, steps",
                listOf("Off" to -1, "7:30" to 450, "8:30" to 510, "9:30" to 570), cfg.morningMinute) {
                val now = System.currentTimeMillis()
                cfg.morningMinute = it
                // a time already past today starts tomorrow, instead of firing the moment you pick it
                if (it >= 0 && (now - Limits.startOfToday(now)) / 60_000L > it) cfg.morningSentDay = Notify.dayKey(now)
                changed()
            }
        ))
        val muted = cfg.nudgesMuted
        add(ui.groupLabel("Notifications"))
        add(ui.group(
            ui.valueRow("See how they look", "Sends a sample warning and yesterday's summary", "Send", {
                if (!notifOk()) { askNotifications(); return@valueRow }
                Thread {
                    runCatching { Nudges.preview(this) }
                    runCatching { Morning.post(this, cfg, Limits.startOfToday(System.currentTimeMillis())) }
                }.start()
                toast("Sent. Pull down the notification shade and expand them.")
            }),
            if (muted.isNotEmpty()) ui.valueRow("Muted warnings", "${muted.size} you marked \u201cNot useful\u201d", "Unmute", {
                cfg.nudgesMuted = emptySet(); changed()
            }) else null
        ))
        if (!notifOk()) add(ui.group(ui.statusRow(false, "Notifications are off", "Turn them on so these can reach you", "Allow") { askNotifications() }), 14)
    }

    private fun pickTime(m: Int, set: (Int) -> Unit) {
        val start = if (m < 0) 21 * 60 else m
        TimePickerDialog(this, { _, h, min -> set(h * 60 + min) }, start / 60, start % 60, true).show()
    }

    // ================================================================ 03 APP RULES
    private fun rulesPage() {
        pageHeader(Page.RULES, "Pick an app and set a limit. When you reach it, Footprint covers the app and asks you to stop.")
        if (!overlayOk()) add(ui.group(ui.statusRow(false, "Allow \"show over other apps\"", "Needed so Footprint can cover an app when you hit a limit", "Allow") { openOverlay() }), 18)

        add(ui.group(ui.switchRow("App limits on",
            "Turn off to pause all limits without losing them" + waitingBool("enabled", "on", "off"), cfg.limitsEnabled,
            noteColor(waitingBool("enabled", "on", "off"))) { after(RuleStore.setEnabled(cfg, it), "Pausing limits") }), 18)

        // ---- apps
        add(ui.groupLabel("Your apps"))
        val apps = recentApps(14).filter { it.first !in cfg.hiddenApps && it.first != packageName }
        if (apps.isEmpty()) {
            add(ui.group(ui.column().apply {
                setPadding(0, ui.dp(14), 0, ui.dp(14))
                addView(ui.note("Your apps show up here after you've used your phone for a bit."))
            }))
        } else {
            // apps with rules first, then by use
            val sorted = apps.sortedByDescending { if (hasRule(it.first)) 1 else 0 }
            val shown = if (showAllApps) sorted.take(30) else sorted.take(6)
            val rows = shown.map { (pkg, name, mins) ->
                val chips = ruleChips(pkg)
                val waits = listOf("limit", "opens", "cap").any { RuleStore.pendingValue(cfg, "$it:$pkg") != null }
                ui.navRow(name, (if (chips.isEmpty()) "No limit · " else "") + "you use it ~${mins / 14}m a day" +
                    (if (waits) " · a change starts tomorrow" else ""),
                    icon = ui.appIcon(pkg), below = if (chips.isEmpty()) null else ui.chipsRow(chips)) { appDialog(pkg, name) }
            }.toMutableList<View>()
            if (sorted.size > 6) rows += ui.text(if (showAllApps) "Show fewer" else "Show all ${minOf(sorted.size, 30)} apps", 14f, ui.rust, ui.monoBold).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16))
                isClickable = true; setOnClickListener { showAllApps = !showAllApps; render() }
            }
            add(ui.group(*rows.toTypedArray()))
        }

        // ---- how strict
        add(ui.groupLabel("When you hit a limit"))
        val strict = arrayListOf<View?>(
            ui.segmentRow("\"${Limits.SNOOZE_MIN} more minutes\" button",
                "How many times a day you can use it, per app" + waiting("snoozes") { if (it < 0) "unlimited" else "$it" },
                listOf("Unlimited" to -1, "3" to 3, "1" to 1, "Never" to 0), RuleStore.rawSnoozes(cfg),
                noteColor(waiting("snoozes") { "" })) { after(RuleStore.setSnoozes(cfg, it), "More extra time") },
            ui.segmentRow("Break length", "How long an app stays closed during a break" + waiting("break") { fmtMin(it) },
                listOf("5m" to 5, "10m" to 10, "15m" to 15, "30m" to 30), RuleStore.rawBreak(cfg),
                noteColor(waiting("break") { "" })) { after(RuleStore.setBreak(cfg, it), "A shorter break") },
            if (StepCollector.hasSensor(this)) ui.segmentRow("Earn time by walking",
                "Walk this many steps to unlock ${RuleStore.WALK_MIN} more minutes" +
                    (if (!StepCollector.hasPermission(this)) ". Turn on step counting first" else "") + waiting("walk") { if (it == 0) "off" else "$it steps" },
                listOf("Off" to 0, "300" to 300, "600" to 600, "1000" to 1000), RuleStore.walkSteps(cfg),
                noteColor(waiting("walk") { "" })) { after(RuleStore.setWalk(cfg, it), "An easier walk") } else null,
            ui.switchRow("Make it hard to cheat",
                "If you loosen a limit, it only starts tomorrow. Stricter limits work straight away." + waitingBool("delay", "on", "off"),
                RuleStore.delayOn(cfg), noteColor(waitingBool("delay", "", ""))) { after(RuleStore.setDelay(cfg, it), "Turning this off") }
        )
        add(ui.group(*strict.toTypedArray()))

        // ---- weekends
        add(ui.groupLabel("Weekends"))
        add(ui.group(
            ui.segmentRow("Weekend limits", "Give yourself more time on weekends" +
                waiting("wmode") { listOf("same", "+50%", "double", "off")[it.coerceIn(0, 3)] },
                listOf("Same" to 0, "+50%" to 1, "Double" to 2, "Off" to 3), RuleStore.rawWeekendMode(cfg),
                noteColor(waiting("wmode") { "" })) { after(RuleStore.setWeekendMode(cfg, it), "A looser weekend") },
            ui.segmentRow("My weekend is", waiting("wdays") { if (it == 1) "Sunday" else "Sat and Sun" }.trim().ifEmpty { null },
                listOf("Sat + Sun" to 0, "Sunday" to 1), RuleStore.rawWeekendDays(cfg), ui.rust) {
                after(RuleStore.setWeekendDays(cfg, it), "A longer weekend")
            }
        ))

        // ---- do this instead
        add(ui.groupLabel("Ideas instead of scrolling"))
        val ideas = RuleStore.doInstead(cfg)
        val ideaRows = ideas.mapIndexed { i, idea ->
            ui.row().apply {
                setPadding(0, ui.dp(12), 0, ui.dp(12))
                addView(ui.text("→", 16f, ui.rust, ui.monoBold), android.widget.LinearLayout.LayoutParams(ui.dp(26), -2))
                addView(ui.title(idea), ui.weight())
                addView(ui.text("✕", 16f, ui.mute, ui.sans).apply {
                    setPadding(ui.dp(14), ui.dp(4), ui.dp(4), ui.dp(4))
                    contentDescription = "Remove $idea"
                    isClickable = true; setOnClickListener { RuleStore.setDoInstead(cfg, ideas.filterIndexed { j, _ -> j != i }); render() }
                })
            }
        }.toMutableList<View>()
        val input = EditText(this).apply {
            hint = "Add an idea, e.g. ten push-ups"
            setHintTextColor(ui.mute); setTextColor(ui.paper); textSize = 15f
            isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(60))
            background = null; setPadding(0, ui.dp(12), 0, ui.dp(12))
        }
        ideaRows += ui.row().apply {
            addView(input, ui.weight())
            addView(ui.pill("Add", filled = true) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { RuleStore.setDoInstead(cfg, ideas + t); render() }
            })
        }
        add(ui.group(*ideaRows.toTypedArray()))
        add(ui.note("One of these shows up when you hit a limit."), 8)
    }

    private fun hasRule(pkg: String) = Limits.minutesFor(cfg, pkg) > 0 || RuleStore.rawOpens(cfg, pkg) > 0 ||
        RuleStore.rawCap(cfg, pkg) > 0 || RuleStore.rawIntent(cfg, pkg)

    private fun ruleChips(pkg: String): List<String> {
        val out = ArrayList<String>()
        Limits.minutesFor(cfg, pkg).takeIf { it > 0 }?.let { out += "${fmtMin(it)}/day" }
        RuleStore.rawOpens(cfg, pkg).takeIf { it > 0 }?.let { out += "$it opens" }
        RuleStore.rawCap(cfg, pkg).takeIf { it > 0 }?.let { out += "break ${fmtMin(it)}" }
        if (RuleStore.rawIntent(cfg, pkg)) out += "asks why"
        return out
    }

    /** Everything for one app, in a dialog. */
    private fun appDialog(pkg: String, name: String) {
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        fun fill() {
            content.removeAllViews()
            content.addView(ui.segmentRow("Daily limit", waiting("limit:$pkg") { fmtMin(it) }.trim().ifEmpty { null },
                listOf("Off" to 0, "15m" to 15, "30m" to 30, "45m" to 45, "1h" to 60, "2h" to 120), Limits.minutesFor(cfg, pkg), ui.rust) {
                after(RuleStore.setLimit(this, pkg, it), "A higher limit"); content.post { fill() }
            })
            content.addView(ui.divider())
            content.addView(ui.segmentRow("Times you can open it a day", "After that, Footprint stops you each time" + waiting("opens:$pkg") { if (it == 0) "off" else "$it" },
                listOf("Off" to 0, "5" to 5, "10" to 10, "20" to 20, "30" to 30), RuleStore.rawOpens(cfg, pkg),
                noteColor(waiting("opens:$pkg") { "" })) {
                after(RuleStore.setOpens(this, pkg, it), "More opens"); content.post { fill() }
            })
            content.addView(ui.divider())
            content.addView(ui.segmentRow("Take a break after", "Using it this long in one go closes it for ${RuleStore.rawBreak(cfg)} minutes" + waiting("cap:$pkg") { fmtMin(it) },
                listOf("Off" to 0, "10m" to 10, "15m" to 15, "20m" to 20, "30m" to 30), RuleStore.rawCap(cfg, pkg),
                noteColor(waiting("cap:$pkg") { "" })) {
                after(RuleStore.setCap(this, pkg, it), "A longer stretch"); content.post { fill() }
            })
            content.addView(ui.divider())
            content.addView(ui.switchRow("Ask me why I'm opening it", "A quick question each time. Answers show in the Why tab", RuleStore.rawIntent(cfg, pkg)) {
                RuleStore.setIntent(cfg, pkg, it); after(RuleStore.Change.NOW); content.post { fill() }
            })
            if (!overlayOk()) content.addView(ui.note("Allow \"show over other apps\" first, or limits can't appear.", ui.rust))
        }
        fill()
        val head = ui.row().apply {
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(4))
            ui.appIcon(pkg)?.let { addView(ui.iconView(it), android.widget.LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { rightMargin = ui.dp(14) }) }
            addView(ui.headline(name, 24f, ui.paper), ui.weight())
        }
        val wrapper = ui.column().apply { setBackgroundColor(ui.card); addView(head); addView(content) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(ScrollView(this).apply { addView(wrapper) })
            .setPositiveButton("Done", null)
            .setOnDismissListener { render() }
            .show()
    }

    // ================================================================ 04 BLOCKED TIMES
    private fun blockedPage() {
        pageHeader(Page.BLOCKED, "Pick times when your distracting apps stay closed, like study hours or bedtime.")
        if (!overlayOk()) add(ui.group(ui.statusRow(false, "Allow \"show over other apps\"", "Needed so Footprint can close apps at these times", "Allow") { openOverlay() }), 18)

        add(ui.groupLabel("Blocked times"))
        val schedules = RuleStore.schedules(cfg)
        if (schedules.isEmpty()) add(ui.group(ui.column().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14)); addView(ui.note("None yet. Add one below, then change the times and days."))
        }))
        schedules.forEachIndexed { i, sc -> add(scheduleCard(sc), if (i == 0) 0 else 12) }
        if (schedules.size < 6) add(ui.row().apply {
            addView(ui.button("+ Study hours", "ghost") {
                RuleStore.addSchedule(cfg, Rules.Schedule(newId(), "Study hours", 16 * 60, 19 * 60, setOf(1, 2, 3, 4, 5, 6)))
                after(RuleStore.Change.NOW)
            }, ui.weight())
            addView(ui.button("+ Night", "ghost") {
                RuleStore.addSchedule(cfg, Rules.Schedule(newId(), "Night", 23 * 60 + 30, 6 * 60 + 30, (1..7).toSet()))
                after(RuleStore.Change.NOW)
            }, ui.weight(left = 10))
        }, 12)

        add(ui.groupLabel("Distracting apps"))
        add(ui.note("Ticked apps are closed during blocked times and study sessions."), 0)
        val distract = RuleStore.distract(this, cfg)
        val apps = recentApps(14).filter { it.first !in cfg.hiddenApps && it.first != packageName }
        val listed = apps.map { it.first to it.second }.toMutableList()
        for (pkg in distract) if (listed.none { it.first == pkg }) listed += pkg to label(pkg)
        val ordered = listed.sortedByDescending { if (it.first in distract) 1 else 0 }
        val shown = if (showAllDistract) ordered.take(40) else ordered.take(8)
        val rows = shown.map { (pkg, name) ->
            val waits = RuleStore.pendingValue(cfg, "distract:$pkg") != null
            ui.appToggle(pkg, name, pkg in distract, if (waits) "Comes off tomorrow" else null) {
                after(RuleStore.setDistract(this, pkg, it), "Taking an app off the list")
            }
        }.toMutableList<View>()
        if (ordered.size > 8) rows += ui.text(if (showAllDistract) "Show fewer" else "Show all ${minOf(ordered.size, 40)} apps", 14f, ui.rust, ui.monoBold).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16))
            isClickable = true; setOnClickListener { showAllDistract = !showAllDistract; render() }
        }
        add(ui.group(*rows.toTypedArray()), 10)
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36)
    private val dayLetters = listOf("M", "T", "W", "T", "F", "S", "S")

    private fun scheduleCard(sc: Rules.Schedule): View {
        val card = ui.column().apply {
            background = ui.shadowed(ui.card, if (sc.on) ui.rust else ui.line, ui.line)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(20), ui.dp(18))
        }
        val (parked, next) = RuleStore.pendingSchedule(cfg, sc.id)
        card.addView(ui.row().apply {
            addView(ui.column().apply {
                addView(ui.headline(sc.name, 22f, if (sc.on) ui.paper else ui.mute))
                addView(ui.text("tap to rename", 11f, ui.mute, ui.mono))
                isClickable = true; setOnClickListener { renameSchedule(sc) }
            }, ui.weight())
            addView(Switch(this@SettingsActivity).apply {
                isChecked = sc.on; ui.tint(this)
                setOnCheckedChangeListener { _, v -> after(RuleStore.updateSchedule(cfg, sc.copy(on = v)), "Turning a blocked time off") }
            })
        })
        card.addView(ui.row().apply {
            addView(ui.pill(clock(sc.from), filled = true) {
                pickTime(sc.from) { after(RuleStore.updateSchedule(cfg, sc.copy(from = it)), "Changing a blocked time") }
            }, ui.weight())
            addView(ui.text("→", 18f, ui.mute, ui.monoBold).apply { gravity = Gravity.CENTER }, android.widget.LinearLayout.LayoutParams(ui.dp(40), -2))
            addView(ui.pill(clock(sc.to), filled = true) {
                pickTime(sc.to) { after(RuleStore.updateSchedule(cfg, sc.copy(to = it)), "Changing a blocked time") }
            }, ui.weight())
        }, ui.lp(top = 14))
        card.addView(ui.row().apply {
            for (d in 1..7) {
                val on = d in sc.days
                addView(ui.text(dayLetters[d - 1], 13f, if (on) ui.ink else ui.mute, ui.monoBold).apply {
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (on) ui.paper else Color.TRANSPARENT); setStroke(ui.dp(1), if (on) ui.paper else ui.line)
                    }
                    contentDescription = "${dayNames[d - 1]}${if (on) ", on" else ""}"
                    isClickable = true
                    setOnClickListener {
                        val nd = if (on) sc.days - d else sc.days + d
                        if (nd.isEmpty()) { toast("Keep at least one day, or switch it off"); return@setOnClickListener }
                        after(RuleStore.updateSchedule(cfg, sc.copy(days = nd)), "Changing a blocked time")
                    }
                }, android.widget.LinearLayout.LayoutParams(0, ui.dp(38), 1f).apply { if (d > 1) leftMargin = ui.dp(6) })
            }
        }, ui.lp(top = 14))
        val foot = ui.row()
        foot.addView(ui.text(when {
            parked && next == null -> "Deleted from tomorrow"
            parked && next != null -> "Changes tomorrow: ${if (next.on) clock(next.from) + "–" + clock(next.to) else "off"}"
            sc.to < sc.from -> "Ends the next morning"
            else -> ""
        }, 12f, if (parked) ui.rust else ui.mute, ui.mono), ui.weight())
        foot.addView(ui.text("Delete", 13f, ui.rust, ui.monoBold).apply {
            setPadding(ui.dp(12), ui.dp(8), 0, ui.dp(4))
            isClickable = true; setOnClickListener { after(RuleStore.deleteSchedule(cfg, sc.id), "Deleting a blocked time") }
        })
        card.addView(foot, ui.lp(top = 12))
        return card
    }

    private fun renameSchedule(sc: Rules.Schedule) {
        val input = EditText(this).apply {
            setText(sc.name); setTextColor(ui.paper); isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(30))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Name")
            .setView(ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, ui.lp()) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                RuleStore.renameSchedule(cfg, sc.id, input.text.toString().trim().ifEmpty { sc.name })
                render()
            }
            .show()
    }

    // ================================================================ 05 REELS AND SHORTS
    private val reelsService get() = "$packageName/$packageName.ReelsService"

    private fun reelsServiceOn(): Boolean = runCatching {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        on.split(':').any { it.equals(reelsService, ignoreCase = true) || it.equals("$packageName/.ReelsService", ignoreCase = true) }
    }.getOrDefault(false)

    private fun reelsPage() {
        pageHeader(Page.REELS, "Footprint closes Reels and Shorts as soon as they open. Normal videos, posts and messages still work.")
        val on = reelsServiceOn()
        add(ui.group(ui.statusRow(on, if (on) "Blocking is on" else "Blocking is off",
            if (on) "The floating footprint button on the side of your screen turns it on and off" else "Turn on \"Footprint: Reels and Shorts\" in your phone's Accessibility settings",
            "Turn on") { if (RuleStore.reelsConsent(cfg)) openAccessibility() else reelsDisclosure() }), 18)
        if (on) add(ui.row().apply { addView(ui.pill("Accessibility settings") { openAccessibility() }) }, 10)

        add(ui.groupLabel("What to close"))
        val icons = mapOf("yt" to "com.google.android.youtube", "ig" to "com.instagram.android", "snap" to "com.snapchat.android")
        add(ui.group(*ReelsMatch.TARGETS.map { t ->
            val w = waitingBool("reels:${t.key}", "on", "off")
            ui.appToggle(icons[t.key] ?: "", t.name, t.key in RuleStore.reelsEnabled(cfg), w.trim().ifEmpty { null }) {
                after(RuleStore.setReels(cfg, t.key, it), "Allowing ${t.name}")
            }
        }.toTypedArray()))

        add(ui.groupLabel("When"))
        add(ui.group(ui.segmentRow("Close them", "All the time, or only during blocked times and study sessions" +
            waiting("reelswhen") { if (it == 0) "always" else "only then" },
            listOf("Always" to 0, "Only then" to 1), RuleStore.reelsWhen(cfg), noteColor(waiting("reelswhen") { "" })) {
            after(RuleStore.setReelsWhen(cfg, it), "Blocking less often")
        }))

        add(ui.groupLabel("Not working?"))
        add(ui.group(ui.navRow("What Footprint sees",
            "Open Reels or Shorts, come back here, tap this and send the list to the developer. It has no personal content.") { reelsDiagnostics() }))
    }

    private fun openAccessibility() = ReelsDisclosure.openSettings(this, "Find \"Footprint: Reels and Shorts\" and turn it on")

    /** Google Play's prominent disclosure, with a clear yes or no, before the service is turned on (ReelsDisclosure). */
    private fun reelsDisclosure() = ReelsDisclosure.show(this, reelsServiceOn())

    private fun reelsDiagnostics() {
        val snap = ReelsDiag.snapshot()
        val text = if (snap.isEmpty()) "Nothing yet. Turn blocking on, open YouTube, Instagram or Snapchat, then come back."
        else snap.entries.joinToString("\n\n") { (pkg, v) ->
            val ago = (System.currentTimeMillis() - v.first) / 1000
            "$pkg (${ago}s ago)\n" + v.second.joinToString("\n")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("What Footprint sees")
            .setMessage(text)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Footprint screen names", text))
                toast("Copied")
            }
            .show()
    }

    // ================================================================ 06 GOALS
    private fun goalsPage() {
        pageHeader(Page.GOALS, "Daily targets, and when Footprint asks \"was it worth it?\".")
        add(ui.groupLabel("Targets"))
        add(ui.group(
            ui.segmentRow("Steps a day", null,
                listOf("4k" to 4000, "6k" to 6000, "8k" to 8000, "10k" to 10000), cfg.stepsGoal) { cfg.stepsGoal = it; changed() },
            ui.segmentRow("Screen time goal", "Shown next to today's total",
                listOf("None" to 0, "2h" to 120, "3h" to 180, "4h" to 240), cfg.screenGoalMin) { cfg.screenGoalMin = it; changed() }
        ))
        add(ui.groupLabel("Was it worth it?"))
        add(ui.group(ui.segmentRow("Ask about app sessions longer than", "Shorter ones are skipped",
            listOf("Never" to 0, "3 min" to 3, "5 min" to 5, "15 min" to 15), cfg.askMinMinutes) { cfg.askMinMinutes = it; changed() }))
    }

    // ================================================================ 07 DATA AND PRIVACY
    private fun dataPage() {
        pageHeader(Page.DATA, "What Footprint records, how long it keeps it, and how to delete it.")
        add(ui.groupLabel("What Footprint records"))
        add(ui.group(
            ui.switchRow("Steps", "From the phone's step sensor", cfg.collectSteps) { cfg.collectSteps = it; changed() },
            ui.switchRow("Notifications", "Which app notified you and when. Never what it said", cfg.collectNotifications) { cfg.collectNotifications = it; changed() },
            ui.segmentRow("Count as sleep only if longer than", "Shorter breaks are treated as naps",
                listOf("2h" to 2, "3h" to 3, "4h" to 4), cfg.minSleepHours) { cfg.minSleepHours = it; changed() },
            ui.segmentRow("Keep history for", "Older data is deleted by itself",
                listOf("30 days" to 30, "90 days" to 90, "180 days" to 180, "1 year" to 365), cfg.retentionDays) { cfg.retentionDays = it; changed() }
        ))

        add(ui.groupLabel("Hidden apps"))
        val apps = recentApps(30)
        val hiddenCount = cfg.hiddenApps.size
        val rows = ArrayList<View>()
        rows += ui.navRow(if (showHidden) "Hide the list" else "Choose apps to hide",
            (if (hiddenCount == 0) "None hidden. " else "$hiddenCount hidden. ") + "Footprint ignores hidden apps and deletes their history. Good for banking or private apps.") {
            showHidden = !showHidden; render()
        }
        if (showHidden) for ((pkg, name, mins) in apps.take(30)) {
            rows += ui.appToggle(pkg, name, pkg in cfg.hiddenApps, "${mins / 60}h ${mins % 60}m in the last 30 days") { hidden ->
                cfg.hide(pkg, hidden)
                if (hidden) {
                    runCatching { EventStore.get(this).forgetApp(pkg) }
                    toast("$name hidden and its history deleted")
                }
                changed()
            }
        }
        add(ui.group(*rows.toTypedArray()))

        add(ui.groupLabel("Your data"))
        add(ui.row().apply {
            addView(ui.button("Export JSON", "paper") { export("json") }, ui.weight())
            addView(ui.button("Export CSV", "paper") { export("csv") }, ui.weight(left = 12))
        })
        add(ui.button("Delete everything", "danger") { confirmWipe() }, 16)
        add(ui.note("Deletes everything Footprint has recorded. Your settings stay."), 8)

        add(ui.groupLabel("Privacy"))
        add(ui.group(ui.column().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            addView(ui.title("Works fully offline"))
            addView(ui.note("Footprint can't use the internet, so your data never leaves this phone. No account, no ads, nothing to pay for."))
        }))
    }

    // ================================================================ actions
    private fun accept() {
        cfg.disclosureAccepted = true
        CollectWorker.schedule(this)
        render()
        toast(if (UsageCollector.hasAccess(this)) "Thanks." else "Thanks. Now allow usage access.")
        if (onboarding && UsageCollector.hasAccess(this)) backToDashboard()
    }

    private fun guard(): Boolean {
        if (!cfg.disclosureAccepted) { toast("Read and accept what Footprint collects first"); open(Page.HOME); return true }
        return false
    }

    private fun openUsageAccess() {
        if (guard()) return
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .onFailure { toast("Open Settings > Apps > Special app access > Usage access") }
    }

    private fun askSteps() {
        if (guard()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 7)
    }

    /** Opens the battery-optimisation list. Asking to be exempted directly needs a restricted permission. */
    private fun askBattery() {
        if (guard()) return
        val how = if (batteryRestricted()) "Turn on \u201cAllow background usage\u201d." else unrestrictedHow()
        // The app's own page first (App info > Battery is one tap away), the global list as a fallback.
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        }.isSuccess || runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isSuccess
        toast(if (opened) "Open Battery. $how" else "Open Settings > Apps > Footprint > Battery. $how")
    }

    private fun askNotifications() {
        if (guard()) return
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        else toast("Notifications are already allowed on this Android version")
    }

    private fun openOverlay() {
        if (guard()) return
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { toast("Open Settings > Apps > Footprint > Display over other apps") }
    }

    private fun backToDashboard() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun export(format: String) {
        pendingExport = format
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (format == "csv") "text/csv" else "application/json"
                putExtra(Intent.EXTRA_TITLE, "footprint-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.$format")
            }, 42
        )
    }

    @Deprecated("Fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val format = pendingExport ?: return
        pendingExport = null
        val uri = data?.data
        if (requestCode != 42 || resultCode != RESULT_OK || uri == null) return
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                        if (format == "csv") EventStore.get(this).exportCsv(w) else EventStore.get(this).exportJson(w)
                    }
                } ?: error("no stream")
            }.isSuccess
            runOnUiThread { toast(if (ok) "Exported" else "Export failed") }
        }.start()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Delete everything?")
            .setMessage("Every event and app icon stored by Footprint on this phone will be deleted. Your settings are kept. This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    runCatching { EventStore.get(this).wipe(); cfg.resetCollection(); RuleStore.resetCounters(cfg); FootprintWidget.refresh(this) }
                    runOnUiThread { toast("Deleted"); render() }
                }.start()
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
