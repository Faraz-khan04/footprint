// Tests the widget's list builder on the JVM (no device needed).
import dev.farazkhan.footprint.WidgetFeed
import dev.farazkhan.footprint.WidgetFeed.AppUse
import dev.farazkhan.footprint.WidgetFeed.Limit
import dev.farazkhan.footprint.WidgetFeed.Session

private var passed = 0
private fun ok(name: String, cond: Boolean, detail: String = "") {
    if (!cond) { println("FAIL $name ${detail}"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}
private const val M = 60_000L
private val today = System.currentTimeMillis() / 86_400_000L * 86_400_000L

private fun session(pkg: String, minutes: Int, hoursAgo: Int) =
    Session(pkg, pkg.replaceFirstChar { it.uppercase() }, today + (12 - hoursAgo) * 60 * M,
        today + ((12 - hoursAgo) * 60 + minutes) * M)

private fun build(
    sessions: List<Session> = emptyList(), answered: Set<String> = emptySet(),
    skip: Set<String> = emptySet(), apps: List<AppUse> = emptyList(),
    limits: List<Limit> = emptyList(), week: IntArray = IntArray(7), askMin: Int = 5
) = WidgetFeed.build(sessions, answered, skip, apps, limits, week, today, askMin)

private fun cards(
    sessions: List<Session> = emptyList(), answered: Set<String> = emptySet(),
    skip: Set<String> = emptySet(), apps: List<AppUse> = emptyList(),
    limits: List<Limit> = emptyList(), week: IntArray = IntArray(7), askMin: Int = 5,
    maxRows: Int = 6
) = WidgetFeed.cards(sessions, answered, skip, apps, limits, week, today, askMin, maxApps = maxRows)

fun main() {
    // ---- questions
    val s1 = session("insta", 30, 3)
    val s2 = session("reddit", 25, 5)
    var rows = build(sessions = listOf(s1, s2))
    val qs = rows.filter { it.type == WidgetFeed.QUESTION }
    ok("unanswered sessions become questions", qs.size == 2, qs.toString())
    ok("questions come first", rows.first().type == WidgetFeed.HEADER && rows[1].type == WidgetFeed.QUESTION)
    ok("a question carries the session key", qs[0].key == s1.key, qs[0].key)

    rows = build(sessions = listOf(s1, s2), answered = setOf(s1.key))
    ok("an answered session is not asked again",
        rows.none { it.key == s1.key } && rows.any { it.key == s2.key })

    rows = build(sessions = listOf(s1, s2), skip = setOf("insta"))
    ok("a skipped app is never asked about", rows.none { it.pkg == "insta" && it.type == WidgetFeed.QUESTION })

    rows = build(sessions = listOf(session("insta", 30, 3), session("insta", 40, 6), s2))
    val qPkgs = rows.filter { it.type == WidgetFeed.QUESTION }.map { it.pkg }
    ok("the same app is not asked twice", qPkgs.distinct().size == qPkgs.size, qPkgs.toString())

    rows = build(sessions = listOf(session("insta", 3, 2)))
    ok("a short session is not worth asking about", rows.none { it.type == WidgetFeed.QUESTION })

    rows = build(sessions = listOf(s1), askMin = 0)
    ok("questions turned off in Settings means none here", rows.none { it.type == WidgetFeed.QUESTION })

    // ---- limits
    rows = build(limits = listOf(Limit("insta", "Instagram", 45, 20), Limit("yt", "YouTube", 30, 40)))
    val lim = rows.filter { it.type == WidgetFeed.LIMIT }
    ok("the limit closest to running out comes first", lim[0].title == "YouTube", lim.map { it.title }.toString())
    ok("an overrun limit says so", lim[0].sub.contains("over by 10m"), lim[0].sub)
    ok("a limit with room left shows the time", lim[1].sub.contains("25m left of 45m"), lim[1].sub)

    // ---- today's apps
    val many = (1..12).map { AppUse("p$it", "App $it", 60 - it * 4) }
    rows = build(apps = many)
    val appRows = rows.filter { it.type == WidgetFeed.APP }
    ok("the app list is capped", appRows.size == 9, appRows.size.toString())      // 8 + Others
    val others = appRows.last()
    ok("the tail is folded into Others", others.title == "Others" && others.sub == "4 apps", others.toString())

    rows = build(apps = listOf(AppUse("a", "A", 30), AppUse("b", "B", 5)))
    ok("a short list has no Others row", rows.none { it.title == "Others" })

    // ---- the week
    rows = build(week = intArrayOf(120, 90, 200, 60, 300, 45, 150))
    val days = rows.filter { it.type == WidgetFeed.DAY }
    ok("seven days are listed", days.size == 7, days.size.toString())
    ok("the last one is today", days.last().title == "Today", days.last().title)
    ok("the busiest day fills its bar", days[4].fill == 1.0, days[4].fill.toString())

    // ---- nothing at all
    rows = build()
    ok("an empty phone gets one clear line", rows.size == 1 && rows[0].type == WidgetFeed.NOTE, rows.toString())

    // ---- formatting
    ok("time reads naturally", WidgetFeed.fmt(64) == "1h 4m" && WidgetFeed.fmt(45) == "45m")

    // ---- swipeable pages
    var pages = cards(sessions = listOf(s1, s2),
        apps = listOf(AppUse("insta", "Instagram", 40)),
        limits = listOf(Limit("insta", "Instagram", 45, 20)),
        week = intArrayOf(10, 20, 30, 40, 50, 60, 70))
    ok("every section becomes its own page",
        pages.map { it.title } == listOf("WORTH IT?", "WORTH IT?", "LIMITS TODAY", "TODAY", "LAST 7 DAYS"),
        pages.map { it.title }.toString())
    ok("pages are numbered for the counter",
        pages.first().index == 1 && pages.first().total == pages.size && pages.last().index == pages.size)
    ok("a question page carries one session, not rows",
        pages[0].question != null && pages[0].rows.isEmpty())
    ok("a list page carries rows, not a question",
        pages[3].question == null && pages[3].rows.isNotEmpty())

    pages = cards(sessions = listOf(s1), answered = setOf(s1.key), apps = listOf(AppUse("a", "A", 10)))
    ok("an answered session leaves no question page", pages.none { it.kind == WidgetFeed.QUESTION })

    pages = cards()
    ok("an empty phone still gets one page", pages.size == 1 && pages[0].kind == WidgetFeed.NOTE)

    pages = cards(apps = (1..12).map { AppUse("p$it", "App $it", 60 - it * 4) })
    val todayPage = pages.first { it.title == "TODAY" }
    ok("a page never holds more rows than the layout has slots", todayPage.rows.size <= 7,
        todayPage.rows.size.toString())
    ok("the folded tail still appears on the page", todayPage.rows.last().title == "Others",
        todayPage.rows.last().title)

    pages = cards(week = intArrayOf(120, 90, 200, 60, 300, 45, 150), maxRows = 7)
    ok("the week page can hold all seven days", pages.first { it.title == "LAST 7 DAYS" }.rows.size == 7)

    // ---- a resized widget shows fewer rows rather than cutting them off
    val small = cards(apps = (1..10).map { AppUse("p$it", "App $it", 50 - it * 3) },
        limits = listOf(Limit("a", "A", 30, 10), Limit("b", "B", 30, 25), Limit("c", "C", 60, 5)),
        week = intArrayOf(10, 20, 30, 40, 50, 60, 70), maxRows = 2)
    ok("a short widget keeps every page within its row budget",
        small.filter { it.kind != WidgetFeed.QUESTION }.all { it.rows.size <= 2 },
        small.map { "${it.title}:${it.rows.size}" }.toString())
    ok("a short week page keeps the most recent days",
        small.first { it.title == "LAST 7 DAYS" }.rows.last().title == "Today")

    val tall = cards(week = intArrayOf(10, 20, 30, 40, 50, 60, 70), maxRows = 7)
    ok("a tall widget shows the whole week", tall.first { it.title == "LAST 7 DAYS" }.rows.size == 7)

    // ---- the page dots
    ok("dots show which page you are on", WidgetFeed.dots(0, 4) == "● ○ ○ ○", WidgetFeed.dots(0, 4))
    ok("the dot moves with the page", WidgetFeed.dots(2, 4) == "○ ○ ● ○", WidgetFeed.dots(2, 4))
    ok("a single page needs no dots", WidgetFeed.dots(0, 1) == "")
    ok("too many pages fall back to a counter", WidgetFeed.dots(8, 12) == "9/12", WidgetFeed.dots(8, 12))

    println("\n$passed passed")
}
