package dev.farazkhan.footprint

/**
 * Builds the scrolling list the home-screen widget shows.
 *
 * Kept free of Android types so it can be tested on the JVM: see android/tools/test/.
 * The order is deliberate — the questions come first, because answering them is the one thing
 * that makes the rest of the app work.
 */
object WidgetFeed {

    data class Session(val pkg: String, val label: String, val start: Long, val end: Long) {
        val minutes: Int get() = ((end - start) / 60_000L).toInt()
        /** Same shape the dashboard uses, so an answer from either side matches the other. */
        val key: String get() = "$pkg|$start"
    }

    data class AppUse(val pkg: String, val label: String, val minutes: Int)
    data class Limit(val pkg: String, val label: String, val limitMin: Int, val usedMin: Int)

    const val HEADER = 0
    const val QUESTION = 1
    const val APP = 2
    const val LIMIT = 3
    const val DAY = 4
    const val NOTE = 5

    /** One swipeable page. [rows] is empty for a question card, which carries its own fields. */
    data class Card(
        val kind: Int, val title: String, val hint: String = "",
        val rows: List<Row> = emptyList(), val question: Row? = null,
        val index: Int = 0, val total: Int = 1
    )

    /**
     * One line in the widget.
     * [value] is the right-hand figure, [sub] the small line under the title,
     * [fill] a 0..1 bar width, [key] the session a question answers.
     */
    data class Row(
        val type: Int, val title: String, val sub: String = "", val value: String = "",
        val fill: Double = 0.0, val key: String = "", val pkg: String = "", val start: Long = 0L,
        val rank: String = ""
    )

    /** "● ○ ○ ○" — which page you are on. Falls back to a counter when there are many. */
    fun dots(page: Int, total: Int): String {
        if (total <= 1) return ""
        if (total > 8) return "${page + 1}/$total"
        return (0 until total).joinToString(" ") { if (it == page) "●" else "○" }
    }

    fun fmt(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

    /**
     * @param sessions finished sessions, newest first
     * @param answered keys already answered, from either the dashboard or the widget
     * @param skip packages never to ask about (the dialer, hidden apps, this app)
     */
    fun cards(
        sessions: List<Session>,
        answered: Set<String>,
        skip: Set<String>,
        apps: List<AppUse>,
        limits: List<Limit>,
        week: IntArray,
        todayStart: Long,
        askMinMinutes: Int,
        maxQuestions: Int = 3,
        maxApps: Int = 6
    ): List<Card> {
        val out = ArrayList<Card>()

        // ---- one card per unanswered session, so answering is a swipe and a tap
        if (askMinMinutes > 0) {
            val used = HashSet<String>()
            for (s in sessions) {
                if (s.minutes < askMinMinutes) continue
                if (s.key in answered || s.pkg in skip) continue
                if (s.pkg in used) continue                     // spread the questions over apps
                used.add(s.pkg)
                out.add(Card(QUESTION, "WORTH IT?", clock(s.start), question =
                    Row(QUESTION, s.label, "${fmt(s.minutes)} in one go", clock(s.start),
                        key = s.key, pkg = s.pkg, start = s.start)))
                if (used.size >= maxQuestions) break
            }
        }

        // ---- limits
        if (limits.isNotEmpty()) {
            val rows = limits.sortedBy { it.limitMin - it.usedMin }.map { l ->
                val left = l.limitMin - l.usedMin
                Row(LIMIT, l.label,
                    if (left > 0) "${fmt(left)} left of ${fmt(l.limitMin)}" else "over by ${fmt(-left)}",
                    if (left > 0) fmt(left) else "over",
                    fill = (l.usedMin.toDouble() / l.limitMin).coerceIn(0.0, 1.0), pkg = l.pkg)
            }
            out.add(Card(LIMIT, "LIMITS TODAY", "", rows.take(maxApps)))
        }

        // ---- today's apps
        val used = apps.filter { it.minutes > 0 }
        if (used.isNotEmpty()) {
            val max = used.first().minutes.coerceAtLeast(1)
            // An Others row costs a slot, so leave room for it rather than overflowing the page.
            val foldsTail = used.size - maxApps >= 2
            val keep = if (foldsTail) maxApps - 1 else maxApps
            val shown = used.take(keep).mapIndexed { i, a ->
                Row(APP, a.label, "", fmt(a.minutes), fill = a.minutes.toDouble() / max, pkg = a.pkg,
                    rank = "%02d".format(i + 1))
            }.toMutableList()
            val rest = used.drop(keep)
            if (rest.size >= 2) shown.add(
                Row(APP, "Others", "${rest.size} apps", fmt(rest.sumOf { it.minutes }),
                    fill = rest.sumOf { it.minutes }.toDouble() / max)
            )
            out.add(Card(APP, "TODAY", "", shown))
        }

        // ---- the week
        if (week.any { it > 0 }) {
            val max = (week.maxOrNull() ?: 1).coerceAtLeast(1)
            val rows = week.indices.map { i ->
                val day = todayStart - (week.size - 1L - i) * 86_400_000L
                Row(DAY, dayName(day, i == week.size - 1), "", fmt(week[i]),
                    fill = week[i].toDouble() / max, start = day)
            }
            out.add(Card(DAY, "LAST 7 DAYS", "", rows.takeLast(maxApps)))
        }

        if (out.isEmpty())
            out.add(Card(NOTE, "NOTHING YET", "", listOf(
                Row(NOTE, "Nothing recorded yet", "open Footprint to set it up"))))

        // number the pages so each card can show "2/4"
        return out.mapIndexed { i, c -> c.copy(index = i + 1, total = out.size) }
    }

    /** Flat version, kept for anything that wants every row in order. */
    fun build(
        sessions: List<Session>,
        answered: Set<String>,
        skip: Set<String>,
        apps: List<AppUse>,
        limits: List<Limit>,
        week: IntArray,
        todayStart: Long,
        askMinMinutes: Int,
        maxQuestions: Int = 3,
        maxApps: Int = 8
    ): List<Row> {
        val rows = ArrayList<Row>()

        // ---- questions worth answering: long enough, unanswered, and not the same app twice
        if (askMinMinutes > 0) {
            val picked = ArrayList<Session>()
            val used = HashSet<String>()
            for (s in sessions) {
                if (s.minutes < askMinMinutes) continue
                if (s.key in answered || s.pkg in skip) continue
                if (s.pkg in used) continue                    // spread the questions over apps
                picked.add(s); used.add(s.pkg)
                if (picked.size >= maxQuestions) break
            }
            if (picked.isNotEmpty()) {
                rows.add(Row(HEADER, "WORTH IT?", "tap to answer"))
                for (s in picked) rows.add(
                    Row(QUESTION, s.label, "${fmt(s.minutes)} in one go", clock(s.start),
                        key = s.key, pkg = s.pkg, start = s.start)
                )
            }
        }

        // ---- what limits have left today
        if (limits.isNotEmpty()) {
            rows.add(Row(HEADER, "LIMITS TODAY"))
            for (l in limits.sortedBy { l -> l.limitMin - l.usedMin }) {
                val left = l.limitMin - l.usedMin
                rows.add(Row(LIMIT, l.label,
                    if (left > 0) "${fmt(left)} left of ${fmt(l.limitMin)}" else "over by ${fmt(-left)}",
                    if (left > 0) fmt(left) else "over",
                    fill = (l.usedMin.toDouble() / l.limitMin).coerceIn(0.0, 1.0), pkg = l.pkg))
            }
        }

        // ---- today's apps, with the small ones folded away
        val shown = apps.filter { it.minutes > 0 }.take(maxApps)
        val rest = apps.filter { it.minutes > 0 }.drop(maxApps)
        if (shown.isNotEmpty()) {
            val max = shown.first().minutes.coerceAtLeast(1)
            rows.add(Row(HEADER, "TODAY"))
            for (a in shown) rows.add(Row(APP, a.label, "", fmt(a.minutes),
                fill = a.minutes.toDouble() / max, pkg = a.pkg))
            if (rest.size >= 2) rows.add(
                Row(APP, "Others", "${rest.size} apps", fmt(rest.sumOf { it.minutes }))
            )
        }

        // ---- the week, oldest first
        if (week.any { it > 0 }) {
            rows.add(Row(HEADER, "LAST 7 DAYS"))
            val max = (week.maxOrNull() ?: 1).coerceAtLeast(1)
            for (i in week.indices) {
                val day = todayStart - (week.size - 1L - i) * 86_400_000L
                rows.add(Row(DAY, dayName(day, i == week.size - 1), "", fmt(week[i]),
                    fill = week[i].toDouble() / max, start = day))
            }
        }

        if (rows.isEmpty()) rows.add(Row(NOTE, "Nothing recorded yet", "open Footprint to set it up"))
        return rows
    }

    // ---- small formatters, kept here so the tests cover them too
    fun clock(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun dayName(ms: Long, isToday: Boolean): String {
        if (isToday) return "Today"
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return DAYS[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }
}
