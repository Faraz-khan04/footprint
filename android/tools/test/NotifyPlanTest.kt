// Tests NotifyPlan.kt on the JVM: when notifications fire, and the morning summary wording.
// Run: android/tools/test/run.sh
import dev.farazkhan.footprint.NotifyPlan
import dev.farazkhan.footprint.NotifyPlan.Slot

private var passed = 0
private fun <T> eq(name: String, expected: T, actual: T) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}
private const val M = 60_000L
private const val DAY = 86_400_000L
private const val D0 = 1_000L * DAY                 // some local midnight
private fun at(h: Int, m: Int = 0) = D0 + h * 60 * M + m * M

fun main() {
    // ---------------------------------------------------------------- quiet hours
    eq("quiet across midnight: 01:00", true, NotifyPlan.inQuiet(60, 30, 420))
    eq("quiet across midnight: 08:00", false, NotifyPlan.inQuiet(480, 30, 420))
    eq("quiet across midnight: 23:30 with 23:00-07:00", true, NotifyPlan.inQuiet(23 * 60 + 30, 23 * 60, 420))
    eq("same start and end means no quiet hours", false, NotifyPlan.inQuiet(100, 300, 300))

    // ---------------------------------------------------------------- due
    val s = Slot("risk-22-50", 22 * 60 + 45)
    eq("due at its minute", true, NotifyPlan.due(s, 22 * 60 + 45, false, false, 30, 420))
    eq("still due 20 minutes late", true, NotifyPlan.due(s, 23 * 60 + 5, false, false, 30, 420))
    eq("not 21 minutes late", false, NotifyPlan.due(s, 23 * 60 + 6, false, false, 30, 420))
    eq("not before", false, NotifyPlan.due(s, 22 * 60 + 44, false, false, 30, 420))
    eq("not twice a day", false, NotifyPlan.due(s, 22 * 60 + 45, true, false, 30, 420))
    eq("not when muted", false, NotifyPlan.due(s, 22 * 60 + 45, false, true, 30, 420))

    // ---------------------------------------------------------------- next alarm
    val slots = listOf(Slot("a", 13 * 60), Slot("b", 22 * 60 + 45))
    fun next(now: Long, fired: Set<String> = emptySet(), muted: Set<String> = emptySet(),
             snoozes: List<Long> = emptyList(), morning: Int = -1, sent: Boolean = false) =
        NotifyPlan.nextAlarm(now, D0, slots, fired, muted, snoozes, 30, 420, morning, sent)
    eq("next is the 13:00 warning", at(13), next(at(9)))
    eq("after 13:00 fired, the 22:45 one", at(22, 45), next(at(13, 5), fired = setOf("a")))
    eq("13:00 missed and not fired: fire now while in its window", at(13, 10), next(at(13, 10)))
    eq("both fired: 13:00 tomorrow", at(13) + DAY, next(at(23), fired = setOf("a", "b")))
    eq("muted warnings are skipped", at(22, 45), next(at(9), muted = setOf("a")))
    eq("a snooze comes first", at(9, 10), next(at(9), snoozes = listOf(at(9, 10))))
    eq("an old snooze is ignored", at(13), next(at(9), snoozes = listOf(at(8))))
    eq("morning summary at 08:30", at(8, 30), next(at(7), morning = 8 * 60 + 30))
    eq("morning summary sent: tomorrow's", at(13), next(at(9), morning = 8 * 60 + 30, sent = true))
    eq("phone was off at 08:30: send it at 09:15", at(9, 15), next(at(9, 15), morning = 8 * 60 + 30))
    eq("nothing planned", null, NotifyPlan.nextAlarm(at(9), D0, emptyList(), emptySet(), emptySet(), emptyList(), 30, 420, -1, false))
    eq("a warning inside quiet hours never fires",
        null, NotifyPlan.nextAlarm(at(9), D0, listOf(Slot("q", 60)), emptySet(), emptySet(), emptyList(), 30, 420, -1, false))
    eq("morning due 80 min late", true, NotifyPlan.morningDue(8 * 60 + 30 + 80, 8 * 60 + 30, false))
    eq("morning not due 2 hours late", false, NotifyPlan.morningDue(10 * 60 + 31, 8 * 60 + 30, false))

    // ---------------------------------------------------------------- wording
    eq("less than usual", "38m less than usual", NotifyPlan.vsUsual(200, 238))
    eq("more than usual", "1h 5m more than usual", NotifyPlan.vsUsual(300, 235))
    eq("close to usual", "about your usual", NotifyPlan.vsUsual(240, 236))
    eq("no usual yet", null, NotifyPlan.vsUsual(240, null))
    val m = NotifyPlan.morning(252, 290, 37, 8214, "Instagram" to 65)!!
    eq("morning title", "Yesterday: 4h 12m on your phone", m.title)
    eq("morning body", "38m less than usual · 37 unlocks · 8,214 steps · most on Instagram (1h 5m)", m.body)
    eq("morning without steps or usual", "1 unlock", NotifyPlan.morning(3, null, 1, null, null)!!.body)
    eq("nothing recorded, no summary", null, NotifyPlan.morning(0, 100, 0, 0, null))
    eq("verdict and note", "You said no 8 times.\n“Sleep.”", NotifyPlan.nudgeBody("x", "You said no 8 times.", "Sleep."))
    eq("only the dashboard line", "x", NotifyPlan.nudgeBody("x", null, null))
    eq("note before the dashboard line", "“Sleep.”\nx", NotifyPlan.nudgeBody("x", null, "Sleep."))

    println("NotifyPlan: $passed passed")
}
