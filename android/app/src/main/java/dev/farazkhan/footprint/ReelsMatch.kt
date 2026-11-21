package dev.farazkhan.footprint

/**
 * Recognising a short-video feed (YouTube Shorts, Instagram Reels, Snapchat Spotlight) from what
 * Android's accessibility API reports about the screen. Pure, so it runs in JVM tests
 * (android/tools/test/ReelsMatchTest.kt); the service that feeds it lives in the "full" edition.
 *
 * Only view ids, whether a tab is selected, and a tab's short label are looked at. Never message
 * text, never anything typed.
 *
 * These apps rename their views now and then. When detection stops working, Settings → Reels and
 * Shorts → "What Footprint sees" lists the ids on screen, and the new one goes in [TARGETS].
 */
object ReelsMatch {

    /** One element on screen, reduced to what matching needs. */
    data class Node(
        val id: String?,              // e.g. "com.google.android.youtube:id/reel_recycler"
        val label: String?,           // content description or short text, for tabs
        val selected: Boolean,
        val visible: Boolean,
        /** Share of the screen this element covers, 0..1. */
        val area: Float
    )

    data class Target(
        val key: String,
        val name: String,
        val packages: Set<String>,
        /** Any visible element whose id contains one of these, covering at least [minArea] of the screen. */
        val idHints: List<String>,
        /** A selected tab whose label is one of these (compared ignoring case). */
        val tabLabels: List<String>,
        val minArea: Float = 0.4f
    )

    val TARGETS = listOf(
        Target(
            key = "yt", name = "YouTube Shorts",
            packages = setOf("com.google.android.youtube"),
            // The full-screen Shorts player only. The Shorts shelf on the home feed is small, so
            // the area check keeps the rest of YouTube usable.
            idHints = listOf("reel_recycler", "reel_player_page_container", "reel_watch_player", "shorts_player"),
            tabLabels = listOf("Shorts")
        ),
        Target(
            key = "ig", name = "Instagram Reels",
            packages = setOf("com.instagram.android", "com.instagram.lite"),
            // Instagram keeps the Reels page attached behind the home feed, so only visible views count.
            idHints = listOf("clips_viewer", "clips_swipe_refresh_container", "clips_video_container", "reels_viewer"),
            tabLabels = listOf("Reels")
        ),
        Target(
            key = "snap", name = "Snapchat Spotlight",
            packages = setOf("com.snapchat.android"),
            idHints = listOf("spotlight_page", "spotlight_feed"),
            tabLabels = listOf("Spotlight")
        )
    )

    val PACKAGES: Set<String> = TARGETS.flatMap { it.packages }.toSet()

    fun targetFor(pkg: String): Target? = TARGETS.firstOrNull { pkg in it.packages }

    /** The feed on screen, or null. [enabled] holds the keys the user switched on. */
    fun match(pkg: String, nodes: List<Node>, enabled: Set<String>): Target? {
        val t = targetFor(pkg) ?: return null
        if (t.key !in enabled) return null
        for (n in nodes) {
            if (!n.visible) continue
            val id = n.id?.substringAfter(":id/", n.id)
            if (id != null && n.area >= t.minArea && t.idHints.any { id.contains(it, ignoreCase = true) }) return t
            val label = n.label?.trim()
            if (n.selected && label != null && label.length <= 24 && t.tabLabels.any { label.equals(it, ignoreCase = true) }) return t
        }
        return null
    }

    /** Ids only, without the package prefix, for the "What Footprint sees" list. */
    fun shortIds(nodes: List<Node>): List<String> =
        nodes.filter { it.visible }.mapNotNull { it.id?.substringAfter(":id/", it.id) }.distinct()
}

/** The last screen the Reels service looked at, per app, for "What Footprint sees". Memory only. */
object ReelsDiag {
    private val seen = LinkedHashMap<String, Pair<Long, List<String>>>()
    @Synchronized fun record(pkg: String, ids: List<String>) { seen[pkg] = System.currentTimeMillis() to ids.take(80) }
    @Synchronized fun snapshot(): Map<String, Pair<Long, List<String>>> = LinkedHashMap(seen)
}
