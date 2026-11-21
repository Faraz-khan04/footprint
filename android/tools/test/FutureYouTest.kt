// Tests "Future You" (FutureYou.kt) on the JVM: verdicts, exchange rate, deadlines, notes, quotes.
// Run: android/tools/test/run.sh
import dev.farazkhan.footprint.FutureYou
import dev.farazkhan.footprint.FutureYou.Answer
import dev.farazkhan.footprint.FutureYou.Goal
import dev.farazkhan.footprint.FutureYou.Moment
import dev.farazkhan.footprint.FutureYou.Note
import dev.farazkhan.footprint.FutureYou.Quote
import dev.farazkhan.footprint.FutureYou.When

private var passed = 0
private fun <T> eq(name: String, expected: T, actual: T) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}
private const val M = 60_000L
private const val H = 60 * M
private const val D = 24 * H

private fun goal(total: Int = 12, left: Int = 12, deadline: Long = 0L, estimate: Int = 60) =
    Goal("DBMS", "DBMS", "chapter", total, left, deadline, estimate, 0L)

fun main() {
    // ---------------------------------------------------------------- time of day
    eq("23:00 is night", FutureYou.Part.NIGHT, FutureYou.partOf(23))
    eq("03:00 is night", FutureYou.Part.NIGHT, FutureYou.partOf(3))
    eq("05:00 is morning", FutureYou.Part.MORNING, FutureYou.partOf(5))
    eq("16:00 is afternoon", FutureYou.Part.AFTERNOON, FutureYou.partOf(16))
    eq("21:00 is evening", FutureYou.Part.EVENING, FutureYou.partOf(21))

    // ---------------------------------------------------------------- verdict
    run {
        val night = (1..9).map { Answer(it * D, 23, regret = it != 5) }          // 8 of 9 regretted
        val day = (1..5).map { Answer(it * D + H, 14, regret = false) }
        val v = FutureYou.verdict(night + day, 0)
        eq("night answers are used at night", FutureYou.Verdict(9, 8, FutureYou.Part.NIGHT), v)
        eq("verdict wording", "The last 9 times you used Instagram late at night, you said it wasn't worth it 8 times.",
            FutureYou.verdictLine(v!!, "Instagram"))
        eq("no verdict in the afternoon, when you were happy with it", null, FutureYou.verdict(night + day, 15))
    }
    run {
        val few = listOf(Answer(1, 9, true), Answer(2, 22, true), Answer(3, 15, true))
        val v = FutureYou.verdict(few, 10)
        eq("too few at this time: all times together", FutureYou.Verdict(3, 3, null), v)
        eq("wording without a time and every time", "The last 3 times you used YouTube, you said it wasn't worth it every time.",
            FutureYou.verdictLine(v!!, "YouTube"))
    }
    eq("two answers say nothing", null, FutureYou.verdict(listOf(Answer(1, 9, true), Answer(2, 9, true)), 9))
    eq("exactly half counts", 2, FutureYou.verdict((1..4).map { Answer(it.toLong(), 9, it % 2 == 0) }, 9)?.regretted)
    run {
        val many = (1..30).map { Answer(it.toLong(), 20, regret = it > 20) }       // newest 10 all regretted
        eq("only the latest ten count", FutureYou.Verdict(10, 10, FutureYou.Part.EVENING), FutureYou.verdict(many, 19))
    }

    // ---------------------------------------------------------------- pace and cost
    eq("estimate before enough is done", FutureYou.Pace(60.0, false), FutureYou.pace(goal(left = 11), 500))
    eq("own pace after two", FutureYou.Pace(90.0, true), FutureYou.pace(goal(left = 10), 180))
    eq("no study time keeps the estimate", FutureYou.Pace(60.0, false), FutureYou.pace(goal(left = 8), 0))
    eq("pace has a floor", 5.0, FutureYou.pace(goal(left = 2), 20).minPerUnit)
    eq("quarter", "about a quarter of a chapter", FutureYou.amount(0.25, "chapter"))
    eq("half", "about half a chapter", FutureYou.amount(0.5, "chapter"))
    eq("an before a vowel", "about half an exercise", FutureYou.amount(0.5, "exercise"))
    eq("three quarters", "about three quarters of a topic", FutureYou.amount(0.8, "topic"))
    eq("one", "about one lecture", FutureYou.amount(1.0, "lecture"))
    eq("one and a half", "about 1.5 chapters", FutureYou.amount(1.4, "chapter"))
    eq("whole numbers", "about 3 chapters", FutureYou.amount(3.1, "chapter"))
    eq("big numbers round", "about 14 pages", FutureYou.amount(13.6, "page"))
    eq("plural of -y", "about 2 summaries", FutureYou.amount(2.0, "summary"))
    eq("tiny says nothing", null, FutureYou.amount(0.1, "chapter"))
    eq("cost at your estimate", "That's about 1.5 chapters of DBMS, at your estimate of 60 minutes each.",
        FutureYou.costLine(90, goal(), FutureYou.pace(goal(), 0)))
    eq("cost at your pace", "That's about half a chapter of DBMS, at your pace.",
        FutureYou.costLine(45, goal(left = 10), FutureYou.pace(goal(left = 10), 180)))
    eq("a few minutes say nothing", null, FutureYou.costLine(4, goal(), FutureYou.Pace(60.0, false)))

    // ---------------------------------------------------------------- deadline
    val p = FutureYou.Pace(60.0, false)
    eq("per day", "12 chapters left, 18 days to go: about 40m a day.", FutureYou.deadlineLine(goal(deadline = 118), p, 100))
    eq("hours a day", "12 chapters left, 4 days to go: about 3h a day.", FutureYou.deadlineLine(goal(deadline = 104), p, 100))
    eq("one day", "1 chapter left, 1 day to go: about 1h a day.", FutureYou.deadlineLine(goal(left = 1, deadline = 101), p, 100))
    eq("today", "12 chapters left, and the day is today.", FutureYou.deadlineLine(goal(deadline = 100), p, 100))
    eq("passed", "12 chapters left. The date has passed.", FutureYou.deadlineLine(goal(deadline = 90), p, 100))
    eq("no date", null, FutureYou.deadlineLine(goal(), p, 100))
    eq("finished", "All 12 chapters done.", FutureYou.deadlineLine(goal(left = 0), p, 100))
    eq("hm", "1h 5m", FutureYou.hm(65))

    // ---------------------------------------------------------------- notes
    run {
        val any = Note(1, "Finish OS first.", When.ANY, 10, 0)
        val night = Note(2, "Sleep. Exam at 9.", When.NIGHT, 20, 0)
        val blocked = Note(3, "Back to the book.", When.BLOCKED, 30, 0)
        val limit = Note(4, "You set this limit for a reason.", When.LIMIT, 40, 0)
        val all = listOf(any, night, blocked, limit)
        val now = 100 * D
        eq("night note at night", 2L, FutureYou.pickNote(all, Moment.INTENT, 23, now)?.id)
        eq("blocked note in blocked time", 3L, FutureYou.pickNote(all, Moment.BLOCKED, 14, now)?.id)
        eq("limit note on a limit", 4L, FutureYou.pickNote(all, Moment.LIMIT, 14, now)?.id)
        eq("any-time note otherwise", 1L, FutureYou.pickNote(all, Moment.INTENT, 14, now)?.id)
        eq("nothing fits", null, FutureYou.pickNote(listOf(night), Moment.INTENT, 14, now))
        val a = Note(5, "A", When.ANY, 1, now - 5 * M)
        val b = Note(6, "B", When.ANY, 2, now - 3 * H)
        eq("the one shown longest ago", 6L, FutureYou.pickNote(listOf(a, b), Moment.INTENT, 14, now)?.id)
        eq("a note just shown still shows if it's the only one", 5L, FutureYou.pickNote(listOf(a), Moment.INTENT, 14, now)?.id)
        eq("blank notes never show", null, FutureYou.pickNote(listOf(Note(7, "  ", When.ANY, 1, 0)), Moment.INTENT, 14, now))
    }
    eq("ago: moment", "FROM YOU, A MOMENT AGO", FutureYou.ago(0, 10 * M))
    eq("ago: yesterday", "FROM YOU, YESTERDAY", FutureYou.ago(0, 30 * H))
    eq("ago: days", "FROM YOU, 3 DAYS AGO", FutureYou.ago(0, 3 * D + H))
    eq("ago: weeks", "FROM YOU, 3 WEEKS AGO", FutureYou.ago(0, 22 * D))

    // ---------------------------------------------------------------- quotes
    run {
        val qs = listOf(Quote("t1", "", listOf("time")), Quote("n1", "", listOf("night")), Quote("f1", "", listOf("focus")))
        eq("night tags first at night", "night", FutureYou.tagsFor(Moment.LIMIT, 1).first())
        eq("no night tag by day", false, "night" in FutureYou.tagsFor(Moment.LIMIT, 13))
        eq("focus for blocked time", "focus", FutureYou.tagsFor(Moment.BLOCKED, 13).first())
        eq("matching quote", "f1", FutureYou.pickQuote(qs, listOf("focus"), 7)?.text)
        eq("night quote on even seconds at night", "n1", FutureYou.pickQuote(qs, listOf("night", "time"), 4)?.text)
        eq("otherwise any matching tag", true, FutureYou.pickQuote(qs, listOf("night", "time"), 5)?.text in setOf("t1", "n1"))
        eq("unknown tag falls back to all", true, FutureYou.pickQuote(qs, listOf("zzz"), 1) != null)
        eq("no quotes", null, FutureYou.pickQuote(emptyList(), listOf("time"), 1))
        eq("same quote all day", FutureYou.quoteOfDay(qs, 20000), FutureYou.quoteOfDay(qs, 20000))
        eq("negative seed is safe", true, FutureYou.pickQuote(qs, listOf("time"), -3) != null)
    }

    println("FutureYou: $passed passed")
}
