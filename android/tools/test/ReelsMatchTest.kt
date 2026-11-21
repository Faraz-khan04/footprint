// Tests Reels/Shorts recognition (ReelsMatch.kt) and the weekly review schedule (WeekMath.kt) on the JVM.
// Run: android/tools/test/run.sh
import dev.farazkhan.footprint.ReelsMatch
import dev.farazkhan.footprint.ReelsMatch.Node
import dev.farazkhan.footprint.WeekMath
import java.util.Calendar
import java.util.TimeZone

private var passed = 0
private fun <T> eq(name: String, expected: T, actual: T) {
    if (expected != actual) { println("FAIL $name: expected $expected, got $actual"); kotlin.system.exitProcess(1) }
    passed++; println("PASS $name")
}
private const val YT = "com.google.android.youtube"
private const val IG = "com.instagram.android"
private val ALL = setOf("yt", "ig", "snap")

private fun id(pkg: String, name: String, area: Float = 0.9f, visible: Boolean = true) =
    Node("$pkg:id/$name", null, false, visible, area)
private fun tab(label: String, selected: Boolean) = Node(null, label, selected, true, 0.02f)

private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance().apply {
    clear(); set(y, mo - 1, d, h, mi, 0)
}.timeInMillis

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))

    // ---------------------------------------------------------------- YouTube
    eq("the Shorts player is caught", "yt", ReelsMatch.match(YT, listOf(id(YT, "reel_recycler")), ALL)?.key)
    eq("the Shorts tab selected is caught", "yt", ReelsMatch.match(YT, listOf(tab("Shorts", true)), ALL)?.key)
    eq("the Shorts tab not selected is fine", null, ReelsMatch.match(YT, listOf(tab("Shorts", false), tab("Home", true)), ALL)?.key)
    eq("a small Shorts shelf in the home feed is fine", null, ReelsMatch.match(YT, listOf(id(YT, "reel_recycler", area = 0.2f)), ALL)?.key)
    eq("a normal video is fine", null, ReelsMatch.match(YT, listOf(id(YT, "watch_player"), id(YT, "player_view")), ALL)?.key)
    eq("switched off: nothing happens", null, ReelsMatch.match(YT, listOf(id(YT, "reel_recycler")), setOf("ig"))?.key)

    // ---------------------------------------------------------------- Instagram
    eq("the Reels viewer is caught", "ig", ReelsMatch.match(IG, listOf(id(IG, "clips_viewer_view_pager")), ALL)?.key)
    eq("the Reels page kept behind the feed isn't", null,
        ReelsMatch.match(IG, listOf(id(IG, "clips_viewer_view_pager", visible = false), tab("Home", true)), ALL)?.key)
    eq("the Reels tab selected is caught", "ig", ReelsMatch.match(IG, listOf(tab("Reels", true)), ALL)?.key)
    eq("a long caption that mentions reels is not a tab", null,
        ReelsMatch.match(IG, listOf(tab("Watch my new reels from the trip this summer", true)), ALL)?.key)
    eq("Instagram Lite counts as Instagram", "ig", ReelsMatch.match("com.instagram.lite", listOf(tab("reels", true)), ALL)?.key)

    // ---------------------------------------------------------------- others
    eq("Snapchat Spotlight", "snap", ReelsMatch.match("com.snapchat.android", listOf(id("com.snapchat.android", "spotlight_feed")), ALL)?.key)
    eq("Snapchat is off unless chosen", null, ReelsMatch.match("com.snapchat.android", listOf(tab("Spotlight", true)), setOf("yt", "ig"))?.key)
    eq("other apps are never matched", null, ReelsMatch.match("com.whatsapp", listOf(tab("Reels", true), id("com.whatsapp", "reel_recycler")), ALL)?.key)
    eq("an id without a package prefix still works", "yt", ReelsMatch.match(YT, listOf(Node("shorts_player", null, false, true, 1f)), ALL)?.key)
    eq("screen names for the diagnostics list", listOf("a", "b"),
        ReelsMatch.shortIds(listOf(id(YT, "a"), id(YT, "b"), id(YT, "a"), id(YT, "hidden", visible = false))))
    eq("the service only listens to these apps", setOf(YT, IG, "com.instagram.lite", "com.snapchat.android"), ReelsMatch.PACKAGES)

    // ---------------------------------------------------------------- weekly review schedule
    // 2026-09-27 is a Sunday.
    eq("later on the review day: today's time", at(2026, 9, 27, 19, 0), WeekMath.lastOccurrence(7, 19 * 60, at(2026, 9, 27, 21, 0)))
    eq("earlier on the review day: last week's", at(2026, 9, 20, 19, 0), WeekMath.lastOccurrence(7, 19 * 60, at(2026, 9, 27, 18, 59)))
    eq("mid-week: the last Sunday", at(2026, 9, 27, 19, 0), WeekMath.lastOccurrence(7, 19 * 60, at(2026, 9, 30, 9, 0)))
    eq("a Friday review", at(2026, 9, 25, 20, 30), WeekMath.lastOccurrence(5, 20 * 60 + 30, at(2026, 9, 28, 8, 0)))
    eq("off", 0L, WeekMath.lastOccurrence(-1, 19 * 60, at(2026, 9, 28, 8, 0)))
    eq("less than last week", "35m less than last week. Tap for your review.", WeekMath.compare(600, 635))
    eq("more than last week", "1h 5m more than last week. Tap for your review.", WeekMath.compare(700, 635))
    eq("about the same", "About the same as last week. Tap for your review.", WeekMath.compare(640, 635))
    eq("first week", "Your first weekly review is ready. Tap to see it.", WeekMath.compare(640, 0))
    eq("hours only", "2h", WeekMath.fmt(120))

    println("\n$passed passed")
}
