package dev.farazkhan.footprint

/**
 * The pure part of app rules: no Android types, so all of it runs in JVM tests
 * (android/tools/test/RulesTest.kt).
 *
 * A "visit" is one stretch in an app. Coming back within [VISIT_GAP_MS] continues the same visit,
 * so glancing at a notification and returning doesn't count as a new open or restart a break timer.
 */
object Rules {

    const val VISIT_GAP_MS = 60_000L

    // ---------------------------------------------------------------- what the user set
    /** A blocked-time window. [from]/[to] are minutes after midnight; [days] are 1=Mon..7=Sun. */
    data class Schedule(
        val id: String,
        val name: String,
        val from: Int,
        val to: Int,
        val days: Set<Int>,
        val on: Boolean = true
    )

    data class RuleSet(
        val enabled: Boolean = true,
        val limits: Map<String, Int> = emptyMap(),      // minutes a day
        val opens: Map<String, Int> = emptyMap(),       // opens a day
        val caps: Map<String, Int> = emptyMap(),        // minutes in one visit before a break
        val intent: Set<String> = emptySet(),           // ask why before opening
        val distract: Set<String> = emptySet(),         // blocked during schedules and study sessions
        val schedules: List<Schedule> = emptyList(),
        val breakMin: Int = 10,
        /** 0 same, 1 half as much again, 2 double, 3 no daily limits at the weekend. */
        val weekendMode: Int = 0,
        /** 0 Saturday and Sunday, 1 Sunday only. */
        val weekendDays: Int = 0,
        /** "a few more minutes" per app per day; -1 means no cap. */
        val snoozesPerDay: Int = -1
    ) {
        /** True when there is anything for the watcher to do. */
        fun any(): Boolean = enabled && (limits.isNotEmpty() || opens.isNotEmpty() || caps.isNotEmpty() ||
            intent.isNotEmpty() || (distract.isNotEmpty() && schedules.any { it.on }))

        /** Packages with at least one per-app rule. */
        fun watched(): Set<String> = limits.keys + opens.keys + caps.keys + intent
    }

    // ---------------------------------------------------------------- calendar
    fun isWeekend(dow: Int, weekendDays: Int): Boolean = if (weekendDays == 1) dow == 7 else dow >= 6

    /** Today's daily limit in minutes (0 = none), after the weekend rule. */
    fun effectiveLimit(r: RuleSet, pkg: String, dow: Int): Int {
        val base = r.limits[pkg] ?: return 0
        if (!isWeekend(dow, r.weekendDays)) return base
        return when (r.weekendMode) {
            1 -> base + base / 2
            2 -> base * 2
            3 -> 0
            else -> base
        }
    }

    /**
     * The schedule in force at [minute] on [dow], or null. A window that crosses midnight
     * (23:30–06:30) belongs to the day it starts on, so Monday's night runs into Tuesday morning.
     */
    fun activeSchedule(list: List<Schedule>, dow: Int, minute: Int): Schedule? {
        val yesterday = if (dow == 1) 7 else dow - 1
        for (s in list) {
            if (!s.on || s.from == s.to) continue
            if (s.from < s.to) {
                if (dow in s.days && minute >= s.from && minute < s.to) return s
            } else {
                if (dow in s.days && minute >= s.from) return s           // tonight's part
                if (yesterday in s.days && minute < s.to) return s        // this morning's part
            }
        }
        return null
    }

    /** Minutes until [s] ends, counted from [minute]. */
    fun minutesLeft(s: Schedule, minute: Int): Int {
        val left = s.to - minute
        return if (left > 0) left else left + 1440
    }

    // ---------------------------------------------------------------- tracking the day
    /** Usage for one app today. */
    class AppDay {
        var usedMs = 0L
        var visits = 0
        var visitStart = 0L
        var visitUsedMs = 0L
        var lastLeft = Long.MIN_VALUE
    }

    /**
     * Builds today's usage from the event stream, one event at a time, so the watcher only ever
     * reads what's new. [self] is Footprint itself: its own screens are invisible here, so the
     * limit page appearing over an app doesn't split that visit in two.
     */
    class Tracker(val self: String, var day: Long) {
        val apps = HashMap<String, AppDay>()
        var fg: String? = null
            private set
        private var fgSince = 0L

        fun of(pkg: String): AppDay = apps.getOrPut(pkg) { AppDay() }

        fun resumed(pkg: String, at: Long) {
            if (pkg == self || pkg == fg) return
            close(at)
            fg = pkg; fgSince = at
            val a = of(pkg)
            if (a.visits == 0 || at - a.lastLeft >= VISIT_GAP_MS) {
                a.visits++; a.visitStart = at; a.visitUsedMs = 0L
            }
        }

        fun left(pkg: String, at: Long) { if (pkg == fg) close(at) }

        fun screenOff(at: Long) = close(at)

        private fun close(at: Long) {
            val p = fg ?: return
            val a = of(p)
            val d = (at - fgSince).coerceAtLeast(0L)
            a.usedMs += d; a.visitUsedMs += d; a.lastLeft = at
            fg = null
        }

        fun usedMs(pkg: String, now: Long): Long =
            (apps[pkg]?.usedMs ?: 0L) + if (fg == pkg) (now - fgSince).coerceAtLeast(0L) else 0L

        fun visitMs(pkg: String, now: Long): Long =
            (apps[pkg]?.visitUsedMs ?: 0L) + if (fg == pkg) (now - fgSince).coerceAtLeast(0L) else 0L

        fun visits(pkg: String): Int = apps[pkg]?.visits ?: 0

        fun visitStart(pkg: String): Long = apps[pkg]?.visitStart ?: 0L
    }

    // ---------------------------------------------------------------- deciding
    enum class Action { NONE, FOCUS, SCHEDULE, LIMIT, BREAK, START_BREAK, OPENS, INTENT }

    /** Everything a decision depends on, for the app in the foreground right now. */
    data class Now(
        val pkg: String,
        val at: Long,
        val dow: Int,
        val minute: Int,
        val usedMs: Long,
        val visitMs: Long,
        val visits: Int,
        /** This visit already had its open checked (count or "why?"), so don't ask again. */
        val visitHandled: Boolean,
        val snoozedUntil: Long,
        val breakUntil: Long,
        val focusActive: Boolean
    )

    /**
     * What to show over the app, strongest reason first. A study session can't be snoozed; everything
     * else steps aside for "a few more minutes".
     */
    fun decide(r: RuleSet, n: Now): Action {
        if (n.focusActive && n.pkg in r.distract) return Action.FOCUS
        if (!r.enabled) return Action.NONE
        if (n.at < n.snoozedUntil) return Action.NONE
        if (n.pkg in r.distract && activeSchedule(r.schedules, n.dow, n.minute) != null) return Action.SCHEDULE
        val limit = effectiveLimit(r, n.pkg, n.dow)
        if (limit > 0 && n.usedMs >= limit * 60_000L) return Action.LIMIT
        if (n.at < n.breakUntil) return Action.BREAK
        val cap = r.caps[n.pkg] ?: 0
        if (cap > 0 && n.visitMs >= cap * 60_000L) return Action.START_BREAK
        if (!n.visitHandled) {
            val max = r.opens[n.pkg] ?: 0
            if (max > 0 && n.visits > max) return Action.OPENS
            if (n.pkg in r.intent) return Action.INTENT
        }
        return Action.NONE
    }

    // ---------------------------------------------------------------- looser rules wait a day
    /**
     * True when [to] is looser than [from] for a rule where a bigger number means more phone time
     * and 0 means no rule at all (daily minutes, opens, minutes before a break, extra-time count).
     */
    fun isLooser(from: Int, to: Int, unlimitedIs: Int = 0): Boolean {
        if (from == to) return false
        if (to == unlimitedIs) return true                // removing the rule
        if (from == unlimitedIs) return false             // adding one is always stricter
        return to > from
    }

    /** Midnight after [dayStart], given the length of that day (DST-safe when the caller passes it). */
    fun tomorrow(dayStart: Long, dayLenMs: Long = 86_400_000L): Long = dayStart + dayLenMs
}
