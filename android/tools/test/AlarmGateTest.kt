// Tests AlarmGate.kt on the JVM: the alarm screen on the lock screen is not screen time.
import dev.farazkhan.footprint.AlarmGate

private var passed = 0
private fun <T> eq(name: String, expected: T, actual: T) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}

fun main() {
    val clock = "com.google.android.deskclock"
    fun gate() = AlarmGate(setOf(clock))

    var g = gate(); g.screenOff(); g.screenOn()
    eq("alarm on the lock screen does not count", false, g.counts(clock))
    eq("it is held in case you unlock", clock, g.held)
    eq("unlocking while it is in front starts it", clock, g.unlock())
    eq("then it counts", true, g.counts(clock))

    g = gate(); g.screenOff(); g.screenOn(); g.counts(clock)
    g.screenOff()
    eq("alarm dismissed and screen off: nothing waiting", null, g.unlock())

    g = gate(); g.screenOff(); g.screenOn(); g.unlock()
    eq("clock opened after unlocking counts (timer, stopwatch)", true, g.counts(clock))

    g = gate(); g.screenOff()
    eq("alarm arriving before the screen-on event still does not count", false, g.counts(clock))

    g = gate(); g.screenOff(); g.screenOn()
    eq("other apps are never held back", true, g.counts("com.instagram.android"))
    g.counts(clock); g.counts("com.instagram.android")
    eq("another app in front clears the held clock", null, g.unlock())

    eq("first run starts unlocked", true, gate().counts(clock))
    eq("state carried from the last run", false, AlarmGate(setOf(clock), unlocked = false).counts(clock))

    println("AlarmGate: $passed passed")
}
