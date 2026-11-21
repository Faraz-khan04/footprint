package dev.farazkhan.footprint

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Reads Android's usage event log and turns it into app sessions, unlocks, screen on/off and
 * notification events. Everything is written to the local database; nothing is sent anywhere.
 *
 * A session starts when an app comes to the front and ends when another app comes to the front,
 * you go to the home screen, or the screen turns off. A session still open at the end of a run is
 * recorded up to "now" and continued on the next run, so nothing is counted twice or lost.
 */
class UsageCollector(
    private val context: Context,
    private val cfg: Config,
    private val store: EventStore,
) {
    companion object {
        // Raw event codes, so this compiles cleanly against minSdk 26
        private const val ACTIVITY_RESUMED = 1          // MOVE_TO_FOREGROUND before API 29
        private const val NOTIFICATION_INTERRUPTION = 12
        private const val SCREEN_INTERACTIVE = 15
        private const val SCREEN_NON_INTERACTIVE = 16
        private const val KEYGUARD_HIDDEN = 18          // phone unlocked (API 28+)

        private const val FIRST_RUN_BACKFILL_MS = 24L * 60 * 60 * 1000
        private const val MIN_SESSION_MS = 1_000L
        private const val APP_INFO_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val ICON_PX = 96

        /** Categories for apps whose Play category is missing or unhelpful. */
        private val CATEGORY_OVERRIDES = mapOf(
            "com.instagram.android" to "Social", "com.facebook.katana" to "Social", "com.twitter.android" to "Social",
            "com.zhiliaoapp.musically" to "Video", "com.ss.android.ugc.trill" to "Video",
            "com.reddit.frontpage" to "Social", "com.snapchat.android" to "Social", "com.linkedin.android" to "Social",
            "com.google.android.youtube" to "Video", "com.netflix.mediaclient" to "Video",
            "com.whatsapp" to "Chat", "org.telegram.messenger" to "Chat", "com.discord" to "Chat",
            "com.spotify.music" to "Music", "com.google.android.apps.maps" to "Maps",
            "com.android.chrome" to "Browser", "com.brave.browser" to "Browser", "org.mozilla.firefox" to "Browser",
            // study apps, so exam mode is useful out of the box
            "com.google.android.apps.classroom" to "Education", "com.duolingo" to "Education",
            "com.unacademyapp" to "Education", "com.byjus.thelearningapp" to "Education",
            "com.google.android.apps.docs" to "Productivity", "com.microsoft.office.onenote" to "Productivity",
            "notion.id" to "Productivity", "com.google.android.keep" to "Productivity",
            "com.adobe.reader" to "Productivity", "com.google.android.apps.docs.editors.docs" to "Productivity",
        )

        /**
         * Whether we may read usage events. Some phones report the grant as MODE_DEFAULT instead of
         * MODE_ALLOWED, so fall back to the permission check, and finally to simply trying a read.
         */
        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            if (mode == AppOpsManager.MODE_ALLOWED) return true
            if (mode == AppOpsManager.MODE_DEFAULT &&
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
            ) return true
            // Last resort: actually try. Without access Android returns no events at all.
            return runCatching {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                usm.queryEvents(now - 6 * 3_600_000L, now).hasNextEvent()
            }.getOrDefault(false)
        }
    }

    private val pm: PackageManager = context.packageManager

    /** Launchers, System UI and this app itself aren't screen time you'd care about. */
    private val ignored: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers: List<String> =
            runCatching { pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        setOf(context.packageName, "com.android.systemui", "android") + launchers.toSet() + cfg.hiddenApps
    }

    /** This phone's clock app(s), whose alarm screen on the lock screen is not screen time (AlarmGate). */
    private val clockApps: Set<String> by lazy {
        val found = HashSet(AlarmGate.KNOWN)
        runCatching { pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName }
            .getOrNull()?.let { if (it != "android") found += it }
        runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent?.creatorPackage }
            .getOrNull()?.let { found += it }
        found
    }

    /** Returns the number of app sessions recorded. */
    fun collect(now: Long): Int {
        if (!hasAccess(context)) return 0
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return 0
        val from = cfg.usageCursor.takeIf { it > 0 } ?: (now - FIRST_RUN_BACKFILL_MS)
        if (now <= from) return 0

        var fg: String? = cfg.carryPackage
        var fgStart = if (fg != null) maxOf(cfg.carrySince, from) else 0L
        var sessions = 0
        val gate = AlarmGate(clockApps, cfg.carryUnlocked)

        fun close(end: Long) {
            val pkg = fg ?: return
            if (end - fgStart >= MIN_SESSION_MS) { record(pkg, fgStart, end); sessions++ }
            fg = null
        }

        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            when (e.eventType) {
                ACTIVITY_RESUMED -> {
                    val pkg = e.packageName
                    if (pkg in ignored) { close(t); gate.counts("") }
                    else if (!gate.counts(pkg)) close(t)              // alarm on the lock screen
                    else if (pkg != fg) { close(t); fg = pkg; fgStart = t }
                }
                SCREEN_NON_INTERACTIVE -> { close(t); gate.screenOff(); store.point("screen_off", t) }
                SCREEN_INTERACTIVE -> {
                    gate.screenOn(); store.point("screen_on", t)
                    fg?.let { if (!gate.counts(it)) close(t) }       // the alarm came up just before
                }
                KEYGUARD_HIDDEN -> {
                    store.point("unlock", t)
                    // unlocked while the clock app was in front: from here on it is real use
                    gate.unlock()?.let { if (fg == null) { fg = it; fgStart = t } }
                }
                NOTIFICATION_INTERRUPTION -> {
                    val pkg = e.packageName
                    if (cfg.collectNotifications && pkg != null && pkg !in ignored) {
                        store.point("notif", t, pkg); saveAppInfo(pkg)
                    }
                }
            }
        }

        val open = fg
        if (open != null) {
            if (now - fgStart >= MIN_SESSION_MS) { record(open, fgStart, now); sessions++ }
            cfg.carryPackage = open
            cfg.carrySince = now
        } else {
            cfg.carryPackage = null
        }
        cfg.carryUnlocked = gate.unlocked
        cfg.usageCursor = now
        return sessions
    }

    private fun record(pkg: String, start: Long, end: Long) {
        store.session(pkg, start, end)
        saveAppInfo(pkg)
    }

    /** Name, category and icon for an app, refreshed weekly (an app can be renamed or updated). */
    private fun saveAppInfo(pkg: String) {
        if (!store.appNeedsInfo(pkg, APP_INFO_MAX_AGE_MS)) return
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg
        store.saveApp(pkg, label, category(pkg, info), icon(pkg))
    }

    private fun category(pkg: String, info: ApplicationInfo?): String {
        CATEGORY_OVERRIDES[pkg]?.let { return it }
        return when (info?.category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_VIDEO -> "Video"
            ApplicationInfo.CATEGORY_AUDIO -> "Music"
            ApplicationInfo.CATEGORY_IMAGE -> "Photos"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_MAPS -> "Maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            else -> "Other"
        }
    }

    /** The app's own icon from this phone, as a small PNG the dashboard can draw. */
    private fun icon(pkg: String): String? = runCatching {
        val drawable = pm.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bmp))
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }.getOrNull()
}
