package dev.farazkhan.footprint

import kotlin.math.roundToInt

/**
 * "Future You": the maths and wording behind the three things shown on Footprint's screens.
 *
 *  1. Your own verdict: how often you said this app, at this time of day, wasn't worth it.
 *  2. Your exchange rate: time on an app, in chapters (or topics, lectures…) of your own goal,
 *     at the pace your own study sessions show.
 *  3. A message from past you: a line you wrote after studying, shown when it fits the moment.
 *
 * Plus a quote picked for the moment. Pure Kotlin, no Android types, so it is tested on the JVM
 * (android/tools/test/FutureYouTest.kt). Storage lives in Motivation.kt.
 */
object FutureYou {

    // ---------------------------------------------------------------- time of day
    enum class Part(val phrase: String) {
        NIGHT("late at night"), MORNING("in the morning"), AFTERNOON("in the afternoon"), EVENING("in the evening")
    }

    fun partOf(hour: Int): Part = when (hour) {
        in 5..11 -> Part.MORNING
        in 12..16 -> Part.AFTERNOON
        in 17..21 -> Part.EVENING
        else -> Part.NIGHT                                  // 22:00 to 04:59
    }

    // ---------------------------------------------------------------- 1. your own verdict
    /** One "was it worth it?" answer for an app: when that visit started (hour) and the answer. */
    data class Answer(val startMs: Long, val hour: Int, val regret: Boolean)

    data class Verdict(val asked: Int, val regretted: Int, val part: Part?)

    /** How many recent answers to look at, and the fewest that say anything. */
    const val VERDICT_WINDOW = 10
    const val VERDICT_MIN = 3

    /**
     * Your recent answers for this app at this time of day (or at any time, if there are too few),
     * but only when at least half said "not worth it": the point is to remind, not to nag about
     * something you're happy with.
     */
    fun verdict(answers: List<Answer>, hour: Int): Verdict? {
        val recent = answers.sortedByDescending { it.startMs }
        val part = partOf(hour)
        val same = recent.filter { partOf(it.hour) == part }.take(VERDICT_WINDOW)
        val (pick, p) = if (same.size >= VERDICT_MIN) same to part else recent.take(VERDICT_WINDOW) to null
        if (pick.size < VERDICT_MIN) return null
        val bad = pick.count { it.regret }
        if (bad * 2 < pick.size) return null
        return Verdict(pick.size, bad, p)
    }

    fun verdictLine(v: Verdict, app: String): String {
        val whenPart = v.part?.let { " ${it.phrase}" } ?: ""
        val times = if (v.regretted == v.asked) "every time" else "${v.regretted} times"
        return "The last ${v.asked} times you used $app$whenPart, you said it wasn't worth it $times."
    }

    // ---------------------------------------------------------------- 2. your exchange rate
    /**
     * A goal to measure time against: "12 chapters of DBMS by 20 Oct".
     * [unit] is singular ("chapter"); [total] and [left] count units; [deadlineDay] is an epoch day
     * (0 = no date); [estimateMin] is your own guess of minutes per unit, used until real data exists;
     * [subject] is the study-timer subject whose sessions count towards it.
     */
    data class Goal(
        val name: String, val subject: String, val unit: String,
        val total: Int, val left: Int, val deadlineDay: Long, val estimateMin: Int, val startMs: Long
    ) {
        val done: Int get() = (total - left).coerceAtLeast(0)
    }

    data class Pace(val minPerUnit: Double, val learned: Boolean)

    /** Units finished before your own pace replaces the estimate. */
    const val LEARN_AFTER = 2

    /** Your pace: study minutes on the goal's subject per unit finished, once there are enough units. */
    fun pace(goal: Goal, studyMin: Int): Pace {
        if (goal.done >= LEARN_AFTER && studyMin >= 10) {
            return Pace((studyMin.toDouble() / goal.done).coerceIn(5.0, 1_000.0), true)
        }
        return Pace(goal.estimateMin.coerceIn(5, 1_000).toDouble(), false)
    }

    fun plural(unit: String, n: Double): String =
        if (n > 0.999 && n < 1.001) unit else when {
            unit.endsWith("s") -> unit
            unit.endsWith("y") && unit.length > 1 && unit[unit.length - 2] !in "aeiou" -> unit.dropLast(1) + "ies"
            else -> unit + "s"
        }

    /** "about 1.5 chapters", "about half a chapter", "about a quarter of a chapter". Null if tiny. */
    fun amount(units: Double, unit: String): String? {
        if (units < 0.2) return null
        val article = if (unit.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
        return when {
            units < 0.35 -> "about a quarter of $article $unit"
            units < 0.65 -> "about half $article $unit"
            units < 0.9 -> "about three quarters of $article $unit"
            units < 1.15 -> "about one $unit"
            units < 10 -> {
                val r = (units * 2).roundToInt() / 2.0
                val s = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
                "about $s ${plural(unit, r)}"
            }
            else -> "about ${units.roundToInt()} ${plural(unit, units)}"
        }
    }

    /**
     * "That's about 1.5 chapters of DBMS, at your pace." for [minutes] of use.
     * Null when the time is too short to be worth saying.
     */
    fun costLine(minutes: Int, goal: Goal, pace: Pace): String? {
        if (minutes < 5) return null
        val a = amount(minutes / pace.minPerUnit, goal.unit) ?: return null
        val how = if (pace.learned) "at your pace" else "at your estimate of ${pace.minPerUnit.roundToInt()} minutes each"
        return "That's $a of ${goal.name}, $how."
    }

    /** "12 chapters left, 18 days to go: about 1h 30m a day." Null without a date or when done. */
    fun deadlineLine(goal: Goal, pace: Pace, todayDay: Long): String? {
        if (goal.left <= 0) return "All ${goal.total} ${plural(goal.unit, goal.total.toDouble())} done."
        if (goal.deadlineDay <= 0L) return null
        val days = goal.deadlineDay - todayDay
        val left = "${goal.left} ${plural(goal.unit, goal.left.toDouble())} left"
        if (days < 0) return "$left. The date has passed."
        if (days == 0L) return "$left, and the day is today."
        val perDay = (goal.left * pace.minPerUnit / days).roundToInt()
        return "$left, ${days} day${if (days == 1L) "" else "s"} to go: about ${hm(perDay)} a day."
    }

    fun hm(min: Int): String {
        val h = min / 60; val m = min % 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }

    // ---------------------------------------------------------------- 3. a message from past you
    /** When a note should appear. Stored as its name, so these must not be renamed. */
    enum class When(val label: String) {
        ANY("Any time"), NIGHT("Late at night"), BLOCKED("Blocked or study time"), LIMIT("When time's up")
    }

    data class Note(val id: Long, val text: String, val whenTag: When, val createdMs: Long, val shownMs: Long)

    /** The moment a screen is shown: what it is for, and the hour. */
    enum class Moment { INTENT, LIMIT, BLOCKED }

    /** Don't show the same note again within this long, unless it's the only one that fits. */
    const val NOTE_REST_MS = 30 * 60_000L

    /**
     * A note that fits: one written for this situation first, then "any time"; within those, the one
     * shown longest ago, so they take turns.
     */
    fun pickNote(notes: List<Note>, moment: Moment, hour: Int, now: Long): Note? {
        val night = partOf(hour) == Part.NIGHT
        fun fits(n: Note) = when (n.whenTag) {
            When.ANY -> true
            When.NIGHT -> night
            When.BLOCKED -> moment == Moment.BLOCKED
            When.LIMIT -> moment == Moment.LIMIT
        }
        val fitting = notes.filter { fits(it) && it.text.isNotBlank() }
        if (fitting.isEmpty()) return null
        val specific = fitting.filter { it.whenTag != When.ANY }
        val pool = specific.ifEmpty { fitting }
        val rested = pool.filter { now - it.shownMs >= NOTE_REST_MS }.ifEmpty { pool }
        return rested.minWith(compareBy<Note> { it.shownMs }.thenBy { it.createdMs })
    }

    /** "FROM YOU, 3 DAYS AGO" */
    fun ago(createdMs: Long, now: Long): String {
        val min = ((now - createdMs) / 60_000L).coerceAtLeast(0L)
        return when {
            min < 60 -> "FROM YOU, A MOMENT AGO"
            min < 24 * 60 -> "FROM YOU, EARLIER TODAY"
            min < 48 * 60 -> "FROM YOU, YESTERDAY"
            min < 14 * 24 * 60 -> "FROM YOU, ${min / (24 * 60)} DAYS AGO"
            else -> "FROM YOU, ${min / (7 * 24 * 60)} WEEKS AGO"
        }
    }

    // ---------------------------------------------------------------- quotes
    data class Quote(val text: String, val by: String, val tags: List<String>)

    /** The kinds of quote that suit each screen. */
    fun tagsFor(moment: Moment, hour: Int): List<String> {
        val base = when (moment) {
            Moment.INTENT -> listOf("habit", "focus", "time")
            Moment.LIMIT -> listOf("time", "habit", "discipline", "future")
            Moment.BLOCKED -> listOf("focus", "study", "discipline", "start")
        }
        return if (partOf(hour) == Part.NIGHT) listOf("night") + base else base
    }

    /**
     * A quote with one of [tags] (night quotes first when [tags] starts with "night"), chosen by
     * [seed] so it changes from one screen to the next but is stable while the screen is open.
     */
    fun pickQuote(quotes: List<Quote>, tags: List<String>, seed: Long): Quote? {
        if (quotes.isEmpty()) return null
        val night = tags.firstOrNull() == "night"
        val pool = when {
            night && seed % 2L == 0L -> quotes.filter { "night" in it.tags }
            else -> quotes.filter { q -> q.tags.any { it in tags } }
        }.ifEmpty { quotes }
        return pool[Math.floorMod(seed, pool.size.toLong()).toInt()]
    }

    /** The same quote all day, a different one tomorrow. */
    fun quoteOfDay(quotes: List<Quote>, epochDay: Long): Quote? =
        if (quotes.isEmpty()) null else quotes[Math.floorMod(epochDay * 37L + 11L, quotes.size.toLong()).toInt()]
}
