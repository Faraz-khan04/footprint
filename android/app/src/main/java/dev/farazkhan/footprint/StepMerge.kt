package dev.farazkhan.footprint

/**
 * Two sources of steps: the phone's own sensor, read whenever Footprint can (Android only answers an
 * app that is on screen, so readings can span hours or days), and Google Play services' step
 * recording (RecordedSteps.kt), which counts in the background and gives exact hourly totals.
 *
 * Where recorded hours exist (the window [from, to)), they win and the sensor readings are dropped;
 * a sensor reading that only partly overlaps the window keeps the part outside it, in proportion.
 * Pure Kotlin, tested on the JVM (tools/test/StepMergeTest.kt).
 */
object StepMerge {
    data class Part(val start: Long, val end: Long, val steps: Double)

    /** The parts of a sensor reading outside the recorded window, with steps in proportion. */
    fun outside(start: Long, end: Long, steps: Double, from: Long, to: Long): List<Part> {
        if (to <= from || end <= from || start >= to) return listOf(Part(start, end, steps))
        if (end <= start) return if (start in from until to) emptyList() else listOf(Part(start, end, steps))
        val len = (end - start).toDouble()
        val out = ArrayList<Part>(2)
        if (start < from) out += Part(start, from, steps * (from - start) / len)
        if (end > to) out += Part(to, end, steps * (end - to) / len)
        return out
    }

    /** Steps from a row inside [a, b), in proportion to the overlap. */
    fun overlap(start: Long, end: Long, steps: Double, a: Long, b: Long): Double =
        if (end <= start) (if (start in a until b) steps else 0.0)
        else steps * (minOf(end, b) - maxOf(start, a)).coerceAtLeast(0L) / (end - start)
}
