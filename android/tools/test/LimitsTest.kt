// Tests the pure part of Limits on the JVM (no device needed).
// Run: android/tools/test/run.sh
import dev.farazkhan.footprint.Limits
import dev.farazkhan.footprint.Limits.Ev

private var passed = 0
private fun check(name: String, expected: Long, actual: Long) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}
private const val M = 60_000L

fun main() {
    val t0 = 0L
    check("a closed session counts once",
        10 * M, Limits.foldUsage(listOf(Ev(Limits.OPENED, t0), Ev(Limits.LEFT, t0 + 10 * M)), t0 + 60 * M))

    check("a session still open counts up to now",
        7 * M, Limits.foldUsage(listOf(Ev(Limits.OPENED, t0)), t0 + 7 * M))

    check("switching to another app ends the session",
        3 * M, Limits.foldUsage(listOf(Ev(Limits.OPENED, t0), Ev(Limits.OTHER, t0 + 3 * M)), t0 + 30 * M))

    check("the screen going off ends the session",
        2 * M, Limits.foldUsage(listOf(Ev(Limits.OPENED, t0), Ev(Limits.LEFT, t0 + 2 * M)), t0 + 30 * M))

    check("separate visits add up",
        15 * M, Limits.foldUsage(listOf(
            Ev(Limits.OPENED, t0), Ev(Limits.LEFT, t0 + 5 * M),
            Ev(Limits.OPENED, t0 + 20 * M), Ev(Limits.OTHER, t0 + 30 * M)), t0 + 40 * M))

    check("a repeated open does not restart the clock",
        10 * M, Limits.foldUsage(listOf(
            Ev(Limits.OPENED, t0), Ev(Limits.OPENED, t0 + 4 * M), Ev(Limits.LEFT, t0 + 10 * M)), t0 + 40 * M))

    check("a stray close before any open is ignored",
        0L, Limits.foldUsage(listOf(Ev(Limits.LEFT, t0 + 5 * M)), t0 + 40 * M))

    check("no events means no time", 0L, Limits.foldUsage(emptyList(), t0 + 40 * M))

    check("an app open all day is capped by now, not by the event list",
        90 * M, Limits.foldUsage(listOf(Ev(Limits.OPENED, t0), Ev(Limits.OTHER, t0 + 60 * M),
            Ev(Limits.OPENED, t0 + 90 * M)), t0 + 120 * M))

    println("\n$passed passed")
}
