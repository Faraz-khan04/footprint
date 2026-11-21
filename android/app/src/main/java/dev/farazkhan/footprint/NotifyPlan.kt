package dev.farazkhan.footprint

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * When notifications fire and what they say. Pure Kotlin, no Android types, so it is tested on the
 * JVM (android/tools/test/NotifyPlanTest.kt). Notify.kt draws them; AlarmReceiver.kt wakes them up.
 */
object NotifyPlan {

    /** A warning may still go out this long after its minute (Android can deliver an alarm late). */
    const val WINDOW_MIN = 20
    /** "Remind me in 10 min". */
    const val SNOOZE_MIN = 10
    /** The morning summary is still worth sending this long after its time; later, it waits a day. */
    const val MORNING_LATE_MIN = 90
    private const val DAY = 86_400_000L
    private const val MIN = 60_000L

    /** One scheduled warning from the dashboard: [minute] of the day. */
    data class Slot(val id: String, val minute: Int)

    fun inQuiet(minute: Int, from: Int, to: Int): Boolean =
        if (from == to) false
        else if (from < to) minute in from until to
        else minute >= from || minute < to                       // crosses midnight

    /** Is [slot] due at [nowMinute] and not yet sent today, muted, or inside quiet hours? */
    fun due(slot: Slot, nowMinute: Int, firedToday: Boolean, muted: Boolean, quietFrom: Int, quietTo: Int): Boolean =
        !firedToday && !muted && !inQuiet(slot.minute, quietFrom, quietTo) &&
            nowMinute >= slot.minute && nowMinute <= slot.minute + WINDOW_MIN

    /**
     * The next moment anything needs to fire, as an absolute time, or null when nothing is planned.
     * [dayStart] is local midnight today. [snoozes] are absolute times. [morning] is minute of day, -1 = off.
     */
    fun nextAlarm(
        now: Long, dayStart: Long, slots: List<Slot>, fired: Set<String>, muted: Set<String>,
        snoozes: Collection<Long>, quietFrom: Int, quietTo: Int, morning: Int, morningSentToday: Boolean,
    ): Long? {
        val nowMinute = ((now - dayStart) / MIN).toInt()
        val times = ArrayList<Long>()
        for (s in slots) {
            if (s.id in muted || inQuiet(s.minute, quietFrom, quietTo)) continue
            val today = dayStart + s.minute * MIN
            times += when {
                s.id !in fired && due(s, nowMinute, false, false, quietFrom, quietTo) -> now
                s.id !in fired && today > now -> today
                else -> today + DAY
            }
        }
        snoozes.filter { it > now - WINDOW_MIN * MIN }.forEach { times += maxOf(it, now) }
        if (morning >= 0) {
            val today = dayStart + morning * MIN
            times += when {
                !morningSentToday && now >= today && now <= today + MORNING_LATE_MIN * MIN -> now
                !morningSentToday && today > now -> today
                else -> today + DAY
            }
        }
        return times.minOrNull()
    }

    fun morningDue(nowMinute: Int, morning: Int, sentToday: Boolean): Boolean =
        morning >= 0 && !sentToday && nowMinute >= morning && nowMinute <= morning + MORNING_LATE_MIN

    // ---------------------------------------------------------------- wording
    fun hm(min: Int): String {
        val h = min / 60; val m = min % 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }

    fun grouped(n: Int): String = "%,d".format(n)

    /** "38m less than usual", "1h 5m more than usual", "about your usual". Null without a usual yet. */
    fun vsUsual(min: Int, usual: Int?): String? {
        if (usual == null || usual <= 0) return null
        val d = min - usual
        if (abs(d) < maxOf(10, (usual * 0.08).roundToInt())) return "about your usual"
        return "${hm(abs(d))} ${if (d < 0) "less" else "more"} than usual"
    }

    data class Summary(val title: String, val body: String)

    /** The morning summary, in one line each: what yesterday looked like. Null when nothing was recorded. */
    fun morning(screenMin: Int, usualMin: Int?, unlocks: Int, steps: Int?, top: Pair<String, Int>?): Summary? {
        if (screenMin <= 0 && unlocks <= 0) return null
        val title = "Yesterday: ${hm(screenMin)} on your phone"
        val parts = ArrayList<String>()
        vsUsual(screenMin, usualMin)?.let { parts += it.replaceFirstChar { c -> c.uppercase() } }
        if (unlocks > 0) parts += "$unlocks unlock${if (unlocks == 1) "" else "s"}"
        if (steps != null && steps > 0) parts += "${grouped(steps)} steps"
        if (top != null && top.second >= 5) parts += "most on ${top.first} (${hm(top.second)})"
        return Summary(title, parts.joinToString(" · "))
    }

    /**
     * A warning in your own words when there are any: your verdict first, a note from past you after
     * it, and the dashboard's line only when neither exists.
     */
    fun nudgeBody(dashboardText: String, verdict: String?, note: String?): String = when {
        verdict != null && note != null -> "$verdict\n“$note”"
        verdict != null -> verdict
        note != null -> "“$note”\n$dashboardText"
        else -> dashboardText
    }
}
