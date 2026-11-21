package dev.farazkhan.footprint

import java.util.Calendar

/** The pure part of the weekly review, so it runs in JVM tests (android/tools/test/WeekMathTest.kt). */
object WeekMath {

    /** The most recent scheduled time at or before [now], or 0 when the review is off. [day] is 1 = Monday .. 7 = Sunday. */
    fun lastOccurrence(day: Int, minute: Int, now: Long): Long {
        if (day !in 1..7 || minute !in 0..1439) return 0L
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val calDay = if (day == 7) Calendar.SUNDAY else day + 1
        var guard = 0
        while ((c.get(Calendar.DAY_OF_WEEK) != calDay || c.timeInMillis > now) && guard++ < 8) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    /** "35m less than last week", or similar. */
    fun compare(week: Int, last: Int): String {
        if (last == 0) return "Your first weekly review is ready. Tap to see it."
        val d = week - last
        return when {
            kotlin.math.abs(d) < 10 -> "About the same as last week. Tap for your review."
            d < 0 -> "${fmt(-d)} less than last week. Tap for your review."
            else -> "${fmt(d)} more than last week. Tap for your review."
        }
    }

    fun fmt(min: Int): String {
        val h = min / 60; val m = min % 60
        return if (h > 0 && m > 0) "${h}h ${m}m" else if (h > 0) "${h}h" else "${m}m"
    }
}
