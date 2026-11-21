package dev.farazkhan.footprint

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Daily time limits for individual apps.
 *
 * Usage is read straight from Android rather than from EventStore, because the collector only
 * runs every 15 minutes and a limit has to be accurate to the minute.
 */
object Limits {

    /** How long the lock stays away after "a few more minutes". */
    const val SNOOZE_MIN = 5

    // ---------------------------------------------------------------- stored limits
    /** { "com.example": 45 } — minutes per day. A missing app has no limit. */
    fun all(cfg: Config): Map<String, Int> {
        val out = HashMap<String, Int>()
        runCatching {
            val o = JSONObject(cfg.limits)
            for (k in o.keys()) { val m = o.optInt(k); if (m > 0) out[k] = m }
        }
        return out
    }

    fun minutesFor(cfg: Config, pkg: String): Int = all(cfg)[pkg] ?: 0

    fun set(cfg: Config, pkg: String, minutes: Int) {
        val o = runCatching { JSONObject(cfg.limits) }.getOrDefault(JSONObject())
        if (minutes > 0) o.put(pkg, minutes) else o.remove(pkg)
        cfg.limits = o.toString()
    }

    fun any(cfg: Config): Boolean = all(cfg).isNotEmpty()

    // ---------------------------------------------------------------- snooze
    /** { "com.example": epochMs } — the lock stays away until then. */
    private fun snoozes(cfg: Config): JSONObject =
        runCatching { JSONObject(cfg.limitSnoozes) }.getOrDefault(JSONObject())

    fun snoozedUntil(cfg: Config, pkg: String): Long = snoozes(cfg).optLong(pkg, 0L)

    fun snooze(cfg: Config, pkg: String, now: Long, minutes: Int = SNOOZE_MIN) {
        cfg.limitSnoozes = snoozes(cfg).put(pkg, now + minutes * 60_000L).toString()
    }

    fun clearSnooze(cfg: Config, pkg: String) {
        cfg.limitSnoozes = snoozes(cfg).apply { remove(pkg) }.toString()
    }

    // ---------------------------------------------------------------- live usage
    fun startOfToday(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // What the accumulator cares about, free of Android types so it can be tested on its own.
    const val OPENED = 1      // this app came to the foreground
    const val LEFT = 2        // this app went away, or the screen went off
    const val OTHER = 3       // a different app came to the foreground

    /** One usage event, reduced to what the accumulator needs. */
    data class Ev(val kind: Int, val at: Long)

    /**
     * Foreground milliseconds for one app, including a session still open at [now].
     * Pure, so it can be tested: see android/tools/test/LimitsTest.kt.
     */
    fun foldUsage(events: List<Ev>, now: Long): Long {
        var total = 0L
        var since: Long? = null                        // null, not 0: a timestamp of 0 is still a time
        for (e in events) {
            when (e.kind) {
                OPENED -> if (since == null) since = e.at
                LEFT, OTHER -> since?.let { total += e.at - it; since = null }
            }
        }
        since?.let { total += now - it }               // still open right now
        return if (total < 0L) 0L else total
    }

    // ---------------------------------------------------------------- running totals
    // The watcher asks every few seconds. Re-reading the whole day each time gets slower as the
    // day goes on, so totals are kept in memory and only new events are read.
    private class Running(var day: Long, var upTo: Long, var total: Long, var open: Long?)
    private val running = HashMap<String, Running>()

    /** Same answer as [usedTodayMs], but only reads events since the last call. */
    @Synchronized
    fun usedTodayFast(context: Context, pkg: String, now: Long = System.currentTimeMillis()): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L
        val day = startOfToday(now)
        val r = running[pkg]?.takeIf { it.day == day } ?: Running(day, day, 0L, null).also { running[pkg] = it }
        if (now <= r.upTo) return r.total + (r.open?.let { now - it } ?: 0L)
        runCatching {
            val events = usm.queryEvents(r.upTo, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        if (e.packageName == pkg) { if (r.open == null) r.open = e.timeStamp }
                        else r.open?.let { r.total += e.timeStamp - it; r.open = null }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        if (e.packageName == pkg) r.open?.let { r.total += e.timeStamp - it; r.open = null }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                        r.open?.let { r.total += e.timeStamp - it; r.open = null }
                }
            }
            r.upTo = now
        }.onFailure { return usedTodayMs(context, pkg, now) }   // fall back to the slow, certain path
        return r.total + (r.open?.let { now - it } ?: 0L)
    }

    /** Drop the running totals, e.g. when a limit changes. */
    @Synchronized
    fun resetRunning() = running.clear()

    /**
     * Foreground milliseconds for one app since midnight, including the session in progress.
     * Reads the same usage events the collector reads, so it needs no extra permission.
     */
    fun usedTodayMs(context: Context, pkg: String, now: Long = System.currentTimeMillis()): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L
        val list = ArrayList<Ev>()
        runCatching {
            val events = usm.queryEvents(startOfToday(now), now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val kind = when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> if (e.packageName == pkg) OPENED else OTHER
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        if (e.packageName == pkg) LEFT else 0
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> LEFT
                    else -> 0
                }
                if (kind != 0) list.add(Ev(kind, e.timeStamp))
            }
        }
        return foldUsage(list, now)
    }

    /** The app currently in the foreground, or null. Looks back a short way to stay cheap. */
    fun foregroundApp(context: Context, now: Long = System.currentTimeMillis()): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        var pkg: String? = null
        runCatching {
            val events = usm.queryEvents(now - 60_000L, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
            }
        }
        return pkg
    }

    /** True when this app is over its limit right now and not snoozed. */
    fun shouldLock(context: Context, cfg: Config, pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!cfg.limitsEnabled) return false
        val limit = minutesFor(cfg, pkg)
        if (limit <= 0) return false
        if (now < snoozedUntil(cfg, pkg)) return false
        return usedTodayFast(context, pkg, now) >= limit * 60_000L
    }
}
