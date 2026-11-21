package dev.farazkhan.footprint

import android.content.Context

/** App state and collector bookkeeping, in private SharedPreferences (excluded from backups). */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("footprint", Context.MODE_PRIVATE)

    /** The user read the data disclosure and tapped "Agree". Required before asking for usage access. */
    var disclosureAccepted: Boolean
        get() = prefs.getBoolean("disclosure_ok", false)
        set(v) = prefs.edit().putBoolean("disclosure_ok", v).apply()

    // ---- usage collector state
    /** Usage events up to this time (epoch ms) have been processed. */
    var usageCursor: Long
        get() = prefs.getLong("usage_cursor", 0L)
        set(v) = prefs.edit().putLong("usage_cursor", v).apply()

    /** App still in the foreground when the last run ended, so the next run continues it. */
    var carryPackage: String?
        get() = prefs.getString("carry_pkg", null)
        set(v) = prefs.edit().putString("carry_pkg", v).apply()

    var carrySince: Long
        get() = prefs.getLong("carry_since", 0L)
        set(v) = prefs.edit().putLong("carry_since", v).apply()

    /** Whether the phone had been unlocked since the screen came on, when the last run ended (AlarmGate). */
    var carryUnlocked: Boolean
        get() = prefs.getBoolean("carry_unlocked", true)
        set(v) = prefs.edit().putBoolean("carry_unlocked", v).apply()

    // ---- Google Play services step recording (RecordedSteps)
    /** When we subscribed to Play services' step recording; 0 = not yet. Data exists only from here on. */
    var recordingSince: Long
        get() = prefs.getLong("rec_since", 0L)
        set(v) = prefs.edit().putLong("rec_since", v).apply()
    /** Recorded steps have been copied up to this time. */
    var recordingSyncedTo: Long
        get() = prefs.getLong("rec_synced", 0L)
        set(v) = prefs.edit().putLong("rec_synced", v).apply()
    /** "ok", or what went wrong last time, for Settings. Empty = never tried. */
    var recordingStatus: String
        get() = prefs.getString("rec_status", "") ?: ""
        set(v) = prefs.edit().putString("rec_status", v).apply()

    // ---- step collector state
    /** Last raw step-counter reading (steps since boot); -1 means no baseline yet. */
    var lastStepTotal: Float
        get() = prefs.getFloat("step_total", -1f)
        set(v) = prefs.edit().putFloat("step_total", v).apply()

    var lastStepAt: Long
        get() = prefs.getLong("step_at", 0L)
        set(v) = prefs.edit().putLong("step_at", v).apply()

    /** Nudge schedule worked out by the dashboard: [{id, hour, minute, title, text}] */
    var nudges: String
        get() = prefs.getString("nudges", "[]") ?: "[]"
        set(v) = prefs.edit().putString("nudges", v).apply()

    /** Which nudge fired on which day, so each fires at most once a day. */
    var nudgesFired: String
        get() = prefs.getString("nudges_fired", "{}") ?: "{}"
        set(v) = prefs.edit().putString("nudges_fired", v).apply()

    var lastCollectAt: Long
        get() = prefs.getLong("collect_at", 0L)
        set(v) = prefs.edit().putLong("collect_at", v).apply()

    // ---- settings the user controls
    /** Send nudges before risky moments and review reminders. */
    var nudgesEnabled: Boolean
        get() = prefs.getBoolean("nudges_on", true)
        set(v) = prefs.edit().putBoolean("nudges_on", v).apply()

    /** Minutes before a risky window the warning arrives. */
    var nudgeLeadMin: Int
        get() = prefs.getInt("nudge_lead", 5)
        set(v) = prefs.edit().putInt("nudge_lead", v).apply()

    /** Hour of the daily "a few sessions to review" reminder; -1 turns it off. */
    var reviewHour: Int
        get() = prefs.getInt("review_hour", 21)
        set(v) = prefs.edit().putInt("review_hour", v).apply()

    /** No nudges between these minutes-of-day (e.g. while you sleep). */
    var quietFrom: Int
        get() = prefs.getInt("quiet_from", 30)          // 00:30
        set(v) = prefs.edit().putInt("quiet_from", v).apply()

    var quietTo: Int
        get() = prefs.getInt("quiet_to", 7 * 60)        // 07:00
        set(v) = prefs.edit().putInt("quiet_to", v).apply()

    /** Shortest session the app will ask "worth it?" about, in minutes. 0 = never ask. */
    var askMinMinutes: Int
        get() = prefs.getInt("ask_min", 5)
        set(v) = prefs.edit().putInt("ask_min", v).apply()

    var collectSteps: Boolean
        get() = prefs.getBoolean("collect_steps", true)
        set(v) = prefs.edit().putBoolean("collect_steps", v).apply()

    var collectNotifications: Boolean
        get() = prefs.getBoolean("collect_notifs", true)
        set(v) = prefs.edit().putBoolean("collect_notifs", v).apply()

    /** How long events are kept, in days. */
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 90)
        set(v) = prefs.edit().putInt("retention_days", v).apply()

    var stepsGoal: Int
        get() = prefs.getInt("steps_goal", 8000)
        set(v) = prefs.edit().putInt("steps_goal", v).apply()

    /** Daily screen-time target in minutes; 0 = no target. */
    var screenGoalMin: Int
        get() = prefs.getInt("screen_goal", 0)
        set(v) = prefs.edit().putInt("screen_goal", v).apply()

    /** Quiet stretches shorter than this aren't treated as sleep. */
    var minSleepHours: Int
        get() = prefs.getInt("min_sleep_h", 3)
        set(v) = prefs.edit().putInt("min_sleep_h", v).apply()

    /** Which page each widget is showing: {"widgetId": page}. */
    fun widgetPage(id: Int): Int =
        runCatching { org.json.JSONObject(prefs.getString("widget_pages", "{}") ?: "{}").optInt(id.toString()) }
            .getOrDefault(0)

    fun setWidgetPage(id: Int, page: Int) {
        val o = runCatching { org.json.JSONObject(prefs.getString("widget_pages", "{}") ?: "{}") }
            .getOrDefault(org.json.JSONObject())
        o.put(id.toString(), page)
        prefs.edit().putString("widget_pages", o.toString()).apply()
    }

    /** Daily app limits worked out in Settings: {"pkg": minutes}. */
    var limits: String
        get() = prefs.getString("limits", "{}") ?: "{}"
        set(v) = prefs.edit().putString("limits", v).apply()

    /** Per-app "a few more minutes" expiry times: {"pkg": epochMs}. */
    var limitSnoozes: String
        get() = prefs.getString("limit_snoozes", "{}") ?: "{}"
        set(v) = prefs.edit().putString("limit_snoozes", v).apply()

    /** Master switch for locking apps once they pass their daily limit. */
    var limitsEnabled: Boolean
        get() = prefs.getBoolean("limits_on", true)
        set(v) = prefs.edit().putBoolean("limits_on", v).apply()

    // ---- app rules beyond daily limits (see RuleStore); each is JSON
    /** Raw access for RuleStore, which owns the format of every "rule_*" key. */
    fun ruleString(key: String, default: String): String = prefs.getString(key, default) ?: default
    fun setRuleString(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value).apply()
    }
    fun hasRule(key: String): Boolean = prefs.contains(key)

    // ---- study session in progress (see Focus)
    /** Start of the running study session, or 0 when none is running. */
    var focusStart: Long
        get() = prefs.getLong("focus_start", 0L)
        set(v) = prefs.edit().putLong("focus_start", v).apply()

    /** Planned end; 0 means a stopwatch that runs until stopped. */
    var focusEnd: Long
        get() = prefs.getLong("focus_end", 0L)
        set(v) = prefs.edit().putLong("focus_end", v).apply()

    var focusSubject: String
        get() = prefs.getString("focus_subject", "") ?: ""
        set(v) = prefs.edit().putString("focus_subject", v).apply()

    /** Show the study screen over distracting apps while the session runs. */
    var focusLock: Boolean
        get() = prefs.getBoolean("focus_lock", true)
        set(v) = prefs.edit().putBoolean("focus_lock", v).apply()

    /** Planned length in minutes, kept so the dashboard can offer the same again. */
    var focusPlanMin: Int
        get() = prefs.getInt("focus_plan", 25)
        set(v) = prefs.edit().putInt("focus_plan", v).apply()

    // ---- weekly review (see Weekly)
    /** Day of the weekly review, 1 = Monday .. 7 = Sunday; -1 turns it off. */
    var weeklyDay: Int
        get() = prefs.getInt("weekly_day", 7)
        set(v) = prefs.edit().putInt("weekly_day", v).apply()

    /** Minutes after midnight the review notification arrives. */
    var weeklyMinute: Int
        get() = prefs.getInt("weekly_minute", 19 * 60)
        set(v) = prefs.edit().putInt("weekly_minute", v).apply()

    /** The scheduled time of the last review sent, so each one is sent once. */
    var weeklyFiredFor: Long
        get() = prefs.getLong("weekly_fired", 0L)
        set(v) = prefs.edit().putLong("weekly_fired", v).apply()

    // ---- notifications (1.3.1)
    /** Warnings the user marked "Not useful" on the notification. Never sent again. */
    var nudgesMuted: Set<String>
        get() = prefs.getStringSet("nudges_muted", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("nudges_muted", v).apply()

    /** "Remind me in 10 min": warning id -> absolute time, as JSON. */
    var nudgeSnoozes: String
        get() = prefs.getString("nudge_snoozes", "{}") ?: "{}"
        set(v) = prefs.edit().putString("nudge_snoozes", v).apply()

    /** Morning summary of yesterday, minute of day; -1 = off. */
    var morningMinute: Int
        get() = prefs.getInt("morning_min", 8 * 60 + 30)
        set(v) = prefs.edit().putInt("morning_min", v).apply()

    /** Local date (yyyyMMdd) the morning summary was last sent. */
    var morningSentDay: String
        get() = prefs.getString("morning_day", "") ?: ""
        set(v) = prefs.edit().putString("morning_day", v).apply()

    /** Apps Footprint ignores completely: never recorded, never shown. */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet("hidden_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("hidden_apps", v).apply()

    fun hide(pkg: String, hidden: Boolean) {
        hiddenApps = if (hidden) hiddenApps + pkg else hiddenApps - pkg
    }

    /** Everything the dashboard needs to respect these settings. */
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("nudgesEnabled", nudgesEnabled)
        .put("nudgeLeadMin", nudgeLeadMin)
        .put("reviewHour", reviewHour)
        .put("quietFrom", quietFrom)
        .put("quietTo", quietTo)
        .put("askMinMinutes", askMinMinutes)
        .put("stepsGoal", stepsGoal)
        .put("screenGoalMin", screenGoalMin)
        .put("minSleepHours", minSleepHours)
        .put("retentionDays", retentionDays)

    /** Forget everything collected so far (used by "Delete everything"). Keeps the disclosure answer. */
    fun resetCollection() {
        // keep the user's choices, drop only what was collected
        prefs.edit()
            .remove("usage_cursor").remove("carry_pkg").remove("carry_since").remove("carry_unlocked").putLong("rec_synced", System.currentTimeMillis())
            .remove("step_total").remove("step_at").remove("collect_at")
            .remove("nudges").remove("nudges_fired").remove("nudge_snoozes").remove("morning_day")
            .apply()
    }
}
