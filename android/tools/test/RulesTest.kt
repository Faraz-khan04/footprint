// Tests the pure rule engine (Rules.kt) on the JVM: visits, blocked times, weekends and what to show.
// Run: android/tools/test/run.sh
import dev.farazkhan.footprint.Rules
import dev.farazkhan.footprint.Rules.Action

private var passed = 0
private fun <T> eq(name: String, expected: T, actual: T) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}
private const val S = 1_000L
private const val M = 60_000L
private const val SELF = "dev.farazkhan.footprint"
private const val IG = "com.instagram.android"
private const val YT = "com.google.android.youtube"

private fun now(
    pkg: String = IG, at: Long = 100 * M, dow: Int = 3, minute: Int = 12 * 60,
    usedMs: Long = 0L, visitMs: Long = 0L, visits: Int = 1, handled: Boolean = false,
    snoozedUntil: Long = 0L, breakUntil: Long = 0L, focus: Boolean = false
) = Rules.Now(pkg, at, dow, minute, usedMs, visitMs, visits, handled, snoozedUntil, breakUntil, focus)

fun main() {
    // ---------------------------------------------------------------- tracker
    run {
        val t = Rules.Tracker(SELF, 0L)
        t.resumed(IG, 0L); t.left(IG, 10 * M)
        eq("a visit adds its time", 10 * M, t.usedMs(IG, 30 * M))
        eq("one visit so far", 1, t.visits(IG))
        t.resumed(IG, 10 * M + 30 * S)
        eq("back within a minute is the same visit", 1, t.visits(IG))
        eq("and its time keeps adding to the visit", 12 * M, t.visitMs(IG, 12 * M + 30 * S))
        t.left(IG, 13 * M); t.resumed(IG, 20 * M)
        eq("back after a longer gap is a new visit", 2, t.visits(IG))
        eq("a new visit starts its own clock", 1 * M, t.visitMs(IG, 21 * M))
        eq("the day total keeps everything", 13 * M + 1 * M - 30 * S, t.usedMs(IG, 21 * M))
    }
    run {
        val t = Rules.Tracker(SELF, 0L)
        t.resumed(IG, 0L); t.left(IG, 10 * S)
        t.resumed(SELF, 10 * S)                          // our own screen over the app
        t.resumed(IG, 20 * S)
        eq("Footprint's own screen doesn't split a visit", 1, t.visits(IG))
        eq("and isn't counted as time in the app", 70 * S, t.usedMs(IG, 80 * S))
        eq("the foreground app is the one underneath", IG, t.fg)
    }
    run {
        val t = Rules.Tracker(SELF, 0L)
        t.resumed(IG, 0L); t.screenOff(5 * M)
        eq("the screen going off ends the visit", 5 * M, t.usedMs(IG, 50 * M))
        eq("nothing is in the foreground after that", null, t.fg)
        t.resumed(YT, 51 * M); t.resumed(IG, 52 * M)
        eq("switching apps closes the one before", 1 * M, t.usedMs(YT, 60 * M))
        t.left(YT, 53 * M)
        eq("a late close for an app already left is ignored", IG, t.fg)
    }
    run {
        val t = Rules.Tracker(SELF, 0L)
        t.resumed(IG, 0L); t.resumed(IG, 5 * M)
        eq("a repeated open of the same app changes nothing", 1, t.visits(IG))
        eq("and doesn't restart its time", 8 * M, t.usedMs(IG, 8 * M))
    }

    // ---------------------------------------------------------------- blocked times
    val study = Rules.Schedule("a", "Study", 16 * 60, 19 * 60, setOf(1, 2, 3, 4, 5))
    val night = Rules.Schedule("b", "Night", 23 * 60 + 30, 6 * 60 + 30, setOf(1))   // Monday night only
    eq("inside a daytime window", "a", Rules.activeSchedule(listOf(study), 2, 17 * 60)?.id)
    eq("the end minute is outside", null, Rules.activeSchedule(listOf(study), 2, 19 * 60)?.id)
    eq("the start minute is inside", "a", Rules.activeSchedule(listOf(study), 2, 16 * 60)?.id)
    eq("not on a day that isn't ticked", null, Rules.activeSchedule(listOf(study), 6, 17 * 60)?.id)
    eq("a night window starts on its own day", "b", Rules.activeSchedule(listOf(night), 1, 23 * 60 + 45)?.id)
    eq("and runs into the next morning", "b", Rules.activeSchedule(listOf(night), 2, 2 * 60)?.id)
    eq("but not into the morning of a day it didn't start", null, Rules.activeSchedule(listOf(night), 1, 2 * 60)?.id)
    eq("a Sunday night runs into Monday", "c",
        Rules.activeSchedule(listOf(night.copy(id = "c", days = setOf(7))), 1, 3 * 60)?.id)
    eq("a switched-off window never applies", null, Rules.activeSchedule(listOf(study.copy(on = false)), 2, 17 * 60)?.id)
    eq("minutes left in a daytime window", 60, Rules.minutesLeft(study, 18 * 60))
    eq("minutes left across midnight", 7 * 60 + 30, Rules.minutesLeft(night, 23 * 60))

    // ---------------------------------------------------------------- weekends
    val base = Rules.RuleSet(limits = mapOf(IG to 30))
    eq("weekday limit is the one you set", 30, Rules.effectiveLimit(base, IG, 3))
    eq("same at the weekend by default", 30, Rules.effectiveLimit(base, IG, 6))
    eq("+50% at the weekend", 45, Rules.effectiveLimit(base.copy(weekendMode = 1), IG, 7))
    eq("double at the weekend", 60, Rules.effectiveLimit(base.copy(weekendMode = 2), IG, 6))
    eq("no daily limit at the weekend", 0, Rules.effectiveLimit(base.copy(weekendMode = 3), IG, 7))
    eq("with a Sunday-only weekend, Saturday is a weekday", 30, Rules.effectiveLimit(base.copy(weekendMode = 2, weekendDays = 1), IG, 6))
    eq("an app with no limit stays unlimited", 0, Rules.effectiveLimit(base, YT, 3))

    // ---------------------------------------------------------------- deciding
    val r = Rules.RuleSet(
        limits = mapOf(IG to 30), opens = mapOf(IG to 5), caps = mapOf(IG to 15),
        intent = setOf(IG, YT), distract = setOf(IG), schedules = listOf(study)
    )
    eq("nothing reached, first visit: ask why", Action.INTENT, Rules.decide(r, now()))
    eq("asked already this visit: nothing", Action.NONE, Rules.decide(r, now(handled = true)))
    eq("a study session wins over everything", Action.FOCUS,
        Rules.decide(r, now(focus = true, snoozedUntil = Long.MAX_VALUE)))
    eq("a study session only locks distracting apps", Action.INTENT, Rules.decide(r, now(pkg = YT, focus = true)))
    eq("rules off: only a study session still locks", Action.NONE, Rules.decide(r.copy(enabled = false), now(usedMs = 99 * M)))
    eq("rules off but studying", Action.FOCUS, Rules.decide(r.copy(enabled = false), now(focus = true)))
    eq("extra time holds everything else back", Action.NONE, Rules.decide(r, now(usedMs = 99 * M, snoozedUntil = 200 * M)))
    eq("extra time runs out", Action.LIMIT, Rules.decide(r, now(usedMs = 99 * M, snoozedUntil = 99 * M)))
    eq("blocked time for a distracting app", Action.SCHEDULE, Rules.decide(r, now(dow = 2, minute = 17 * 60)))
    eq("blocked time ignores other apps", Action.INTENT, Rules.decide(r, now(pkg = YT, dow = 2, minute = 17 * 60)))
    eq("daily limit reached", Action.LIMIT, Rules.decide(r, now(usedMs = 30 * M)))
    eq("just under the limit", Action.NONE, Rules.decide(r, now(usedMs = 30 * M - 1, handled = true)))
    eq("weekend doubling moves the limit", Action.NONE,
        Rules.decide(r.copy(weekendMode = 2), now(dow = 6, usedMs = 45 * M, handled = true)))
    eq("on a break", Action.BREAK, Rules.decide(r, now(breakUntil = 105 * M, handled = true)))
    eq("a finished break doesn't show", Action.NONE, Rules.decide(r, now(breakUntil = 99 * M, handled = true)))
    eq("long enough in one go: start a break", Action.START_BREAK, Rules.decide(r, now(visitMs = 15 * M, handled = true)))
    eq("too many opens", Action.OPENS, Rules.decide(r, now(visits = 6)))
    eq("the allowed number of opens is fine", Action.INTENT, Rules.decide(r, now(visits = 5)))
    eq("an open already dealt with isn't counted again", Action.NONE, Rules.decide(r, now(visits = 6, handled = true)))
    eq("an app with no rules", Action.NONE, Rules.decide(r, now(pkg = "com.example.notes")))

    // ---------------------------------------------------------------- looser or stricter
    eq("raising a limit is looser", true, Rules.isLooser(30, 60))
    eq("lowering a limit is stricter", false, Rules.isLooser(60, 30))
    eq("removing a limit is looser", true, Rules.isLooser(30, 0))
    eq("adding a limit is stricter", false, Rules.isLooser(0, 120))
    eq("no change is not looser", false, Rules.isLooser(30, 30))
    eq("unlimited extra time is looser", true, Rules.isLooser(1, -1, unlimitedIs = -1))
    eq("capping extra time is stricter", false, Rules.isLooser(-1, 3, unlimitedIs = -1))
    eq("allowing extra time again is looser", true, Rules.isLooser(0, 1, unlimitedIs = -1))

    // ---------------------------------------------------------------- is there anything to watch?
    eq("no rules: nothing to watch", false, Rules.RuleSet().any())
    eq("a limit is something to watch", true, Rules.RuleSet(limits = mapOf(IG to 30)).any())
    eq("distracting apps alone are not", false, Rules.RuleSet(distract = setOf(IG)).any())
    eq("distracting apps plus a blocked time are", true, Rules.RuleSet(distract = setOf(IG), schedules = listOf(study)).any())
    eq("everything off", false, Rules.RuleSet(enabled = false, limits = mapOf(IG to 30)).any())

    println("\n$passed passed")
}
