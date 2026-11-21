// Tests StepMerge.kt on the JVM: recorded hours win, sensor readings keep only what lies outside them.
import dev.farazkhan.footprint.StepMerge

private var passed = 0
private fun <T> eq(name: String, expected: T, actual: T) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}

fun main() {
    val H = 3_600_000L
    eq("no recorded window: reading untouched", listOf(StepMerge.Part(0, 10 * H, 1000.0)), StepMerge.outside(0, 10 * H, 1000.0, 0, 0))
    eq("reading entirely inside the window is dropped", emptyList<StepMerge.Part>(), StepMerge.outside(2 * H, 4 * H, 500.0, H, 10 * H))
    eq("reading before the window is kept", listOf(StepMerge.Part(0, H, 100.0)), StepMerge.outside(0, H, 100.0, 2 * H, 5 * H))
    eq("47-hour reading that runs into the window keeps its share before it",
        listOf(StepMerge.Part(0, 10 * H, 2000.0)), StepMerge.outside(0, 40 * H, 8000.0, 10 * H, 50 * H))
    eq("reading sticking out on both sides keeps both ends",
        listOf(StepMerge.Part(0, H, 100.0), StepMerge.Part(3 * H, 4 * H, 100.0)), StepMerge.outside(0, 4 * H, 400.0, H, 3 * H))
    eq("overlap in proportion", 250.0, StepMerge.overlap(0, 4 * H, 1000.0, 3 * H, 10 * H))
    eq("no overlap", 0.0, StepMerge.overlap(0, H, 1000.0, 2 * H, 3 * H))
    println("StepMerge: $passed passed")
}
