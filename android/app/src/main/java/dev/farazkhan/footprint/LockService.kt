package dev.farazkhan.footprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches which app is in the foreground and shows the right screen over it: a daily limit, too
 * many opens, a break, a blocked time, a study session, or "why are you opening this?".
 *
 * Runs only while there is a rule to enforce or a study session to time, and only reads apps while
 * the screen is on. Nothing leaves the phone: it reads the same usage events the rest of the app reads.
 */
class LockService : Service() {

    companion object {
        private const val CHANNEL = "limits"
        private const val NOTE_ID = 42
        /** Fast enough that "why?" appears as the app opens. Used only when such a rule exists. */
        private const val POLL_FAST_MS = 1_000L
        private const val POLL_MS = 2_500L
        private const val STEP_READ_GAP_MS = 10 * 60_000L
        /** Don't show a screen again for the same app straight after it was dismissed. */
        private const val REARM_MS = 4_000L
        private const val RELOAD_MS = 15_000L

        fun canDrawOver(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Visits whose open was already dealt with ("why?" answered), per app. Same process as LockActivity. */
        private val handled = HashMap<String, Long>()
        @Synchronized fun markHandled(pkg: String, visitStart: Long) { handled[pkg] = visitStart }
        @Synchronized private fun wasHandled(pkg: String, visitStart: Long) = handled[pkg] == visitStart

        /** Start, stop or refresh the watcher to match the current settings. */
        fun sync(context: Context) {
            val cfg = Config(context)
            val ready = cfg.disclosureAccepted && UsageCollector.hasAccess(context)
            val rules = runCatching { RuleStore.load(context, cfg) }.getOrNull()
            val wanted = ready && (Focus.active(cfg) || (rules?.any() == true && canDrawOver(context)))
            val intent = Intent(context, LockService::class.java)
            val running = live
            runCatching {
                when {
                    // Already running: refresh in place. Starting it again from the background (the
                    // 15-minute job, the end of a study session) is restricted on Android 12+.
                    wanted && running != null -> Handler(Looper.getMainLooper()).post { running.refreshState() }
                    wanted -> context.startForegroundService(intent)
                    else -> context.stopService(intent)
                }
            }
        }

        @Volatile private var live: LockService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Separate from [handler], so the screen going off doesn't cancel the end of a study session. */
    private val timer = Handler(Looper.getMainLooper())
    private var lastShownAt = 0L
    private var lastShownPkg: String? = null

    private var rules = Rules.RuleSet()
    private var rulesAt = 0L
    private var tracker: Rules.Tracker? = null
    private var readUpTo = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> { schedule(); readSteps() }
                Intent.ACTION_SCREEN_OFF -> { handler.removeCallbacksAndMessages(null); readSteps() }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }
            handler.postDelayed(this, if (rules.opens.isNotEmpty() || rules.intent.isNotEmpty()) POLL_FAST_MS else POLL_MS)
        }
    }

    /**
     * Android may hold the 15-minute job back for hours, and the step chip only gives a running total,
     * so a late reading can't say which day the steps were on. While this service is running anyway,
     * take a reading when the screen turns on or off, at most every 10 minutes.
     */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun readSteps() {
        io.launch { runCatching { StepCollector.collectIfStale(applicationContext, STEP_READ_GAP_MS) } }
    }

    /** Redraws the study notification once a minute so its bar keeps filling. */
    private val ticker = Handler(Looper.getMainLooper())
    private val redraw = object : Runnable {
        override fun run() {
            if (!Focus.active(Config(this@LockService))) return
            runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification()) }
            ticker.postDelayed(this, 60_000L)
        }
    }

    private val focusDone = Runnable {
        if (Focus.finishIfDue(this)) refreshState()
    }

    override fun onCreate() {
        super.onCreate()
        live = this
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, notification())
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, filter)
        refreshState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshState()
        return START_STICKY
    }

    override fun onDestroy() {
        if (live === this) live = null
        handler.removeCallbacksAndMessages(null)
        timer.removeCallbacksAndMessages(null)
        ticker.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        io.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Settings or the study session changed: reload rules, redraw the notification, re-arm the timer. */
    fun refreshState() {
        val cfg = Config(this)
        Focus.finishIfDue(this)
        reloadRules(cfg)
        Limits.resetRunning()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification()) }
        timer.removeCallbacksAndMessages(null)
        ticker.removeCallbacksAndMessages(null)
        if (Focus.active(cfg)) ticker.postDelayed(redraw, 60_000L)
        if (Focus.active(cfg)) {
            val due = if (cfg.focusEnd > 0L) cfg.focusEnd else cfg.focusStart + Focus.MAX_MS
            timer.postDelayed(focusDone, (due - System.currentTimeMillis()).coerceAtLeast(0L) + 500L)
        } else if (!rules.any() || !canDrawOver(this)) {
            stopSelf(); return
        }
        schedule()
    }

    private fun reloadRules(cfg: Config) {
        rules = runCatching { RuleStore.load(this, cfg) }.getOrDefault(Rules.RuleSet())
        rulesAt = System.currentTimeMillis()
    }

    private fun schedule() {
        handler.removeCallbacksAndMessages(null)
        if (screenOn()) handler.post(tick)
    }

    private fun screenOn(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    /** Read usage events since the last tick into today's tracker. */
    private fun track(now: Long): Rules.Tracker? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val day = Limits.startOfToday(now)
        var t = tracker
        if (t == null || t.day != day) {
            val carry = t?.fg                                  // an app open across midnight stays open
            t = Rules.Tracker(packageName, day)
            readUpTo = day
            tracker = t
            if (carry != null) t.resumed(carry, day)
        }
        if (now <= readUpTo) return t
        val events = usm.queryEvents(readUpTo, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> t.resumed(e.packageName, e.timeStamp)
                // Only "paused" counts as leaving. "Stopped" can arrive after the same app's next
                // screen has already opened, and would cut that visit short.
                UsageEvents.Event.ACTIVITY_PAUSED -> t.left(e.packageName, e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> t.screenOff(e.timeStamp)
            }
        }
        readUpTo = now
        return t
    }

    private fun check() {
        val cfg = Config(this)
        val now = System.currentTimeMillis()
        if (now - rulesAt > RELOAD_MS) reloadRules(cfg)
        val focusing = Focus.active(cfg) && cfg.focusLock
        if (!focusing && !rules.any()) return
        val t = track(now) ?: return
        val pkg = t.fg ?: return
        if (pkg == packageName) return                               // never over our own dashboard
        if (!focusing && pkg !in rules.watched() && pkg !in rules.distract) return
        if (!canDrawOver(this)) return
        if (pkg == lastShownPkg && now - lastShownAt < REARM_MS) return

        val visitStart = t.visitStart(pkg)
        val state = Rules.Now(
            pkg = pkg, at = now,
            dow = RuleStore.dayOfWeek(now), minute = RuleStore.minuteOfDay(now),
            usedMs = t.usedMs(pkg, now), visitMs = t.visitMs(pkg, now), visits = t.visits(pkg),
            visitHandled = wasHandled(pkg, visitStart),
            snoozedUntil = Limits.snoozedUntil(cfg, pkg),
            breakUntil = RuleStore.breakUntil(cfg, pkg),
            focusActive = focusing
        )
        val action = Rules.decide(rules, state)
        val mode = when (action) {
            Rules.Action.NONE -> return
            Rules.Action.FOCUS -> LockActivity.Mode.FOCUS
            Rules.Action.SCHEDULE -> LockActivity.Mode.SCHEDULE
            Rules.Action.LIMIT -> LockActivity.Mode.LIMIT
            Rules.Action.BREAK -> LockActivity.Mode.BREAK
            Rules.Action.START_BREAK -> {
                RuleStore.startBreak(cfg, pkg, now + rules.breakMin * 60_000L)
                LockActivity.Mode.BREAK
            }
            Rules.Action.OPENS -> LockActivity.Mode.OPENS
            Rules.Action.INTENT -> LockActivity.Mode.INTENT
        }
        lastShownPkg = pkg; lastShownAt = now
        val schedule = Rules.activeSchedule(rules.schedules, state.dow, state.minute)
        runCatching {
            startActivity(LockActivity.intent(this, mode, pkg,
                usedMs = state.usedMs,
                limitMin = Rules.effectiveLimit(rules, pkg, state.dow),
                visits = state.visits, maxVisits = rules.opens[pkg] ?: 0,
                visitStart = visitStart, visitMin = (state.visitMs / 60_000L).toInt(),
                until = when (mode) {
                    LockActivity.Mode.BREAK -> RuleStore.breakUntil(cfg, pkg)
                    LockActivity.Mode.FOCUS -> cfg.focusEnd
                    LockActivity.Mode.SCHEDULE -> schedule?.let { now + Rules.minutesLeft(it, state.minute) * 60_000L } ?: 0L
                    else -> 0L
                },
                label = if (mode == LockActivity.Mode.SCHEDULE) schedule?.name.orEmpty()
                        else if (mode == LockActivity.Mode.FOCUS) cfg.focusSubject else ""))
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "App limits and study timer", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Footprint is checking your app limits or timing a study session."
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val cfg = Config(this)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
        if (Focus.active(cfg)) {
            // Rust, like the study screen; a live countdown, a bar that fills, and the buttons you'd want.
            b.setContentTitle("Studying ${cfg.focusSubject}")
                .setContentText(if (cfg.focusLock) "Distracting apps are locked until it ends." else "Nothing is locked.")
                .setUsesChronometer(true)
                .setColor(Notify.RUST).setColorized(true)
                .setSubText("Study")
            val now = System.currentTimeMillis()
            if (cfg.focusEnd > 0L) {
                b.setWhen(cfg.focusEnd).setChronometerCountDown(true)
                val total = (cfg.focusEnd - cfg.focusStart).coerceAtLeast(1L)
                b.setProgress(1000, ((now - cfg.focusStart).coerceIn(0L, total) * 1000 / total).toInt(), false)
                b.addAction(Notify.action(this, 62, "+10 min", AlarmReceiver.FOCUS_PLUS))
            } else b.setWhen(cfg.focusStart)
            // Stopping from the notification would make a locked session too easy to walk away from.
            if (!cfg.focusLock) b.addAction(Notify.action(this, 63, "Stop", AlarmReceiver.FOCUS_STOP))
        } else {
            b.setContentTitle("App limits are on").setContentText("Nothing leaves your phone.")
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        }
        return b.build()
    }
}
