package dev.farazkhan.footprint

import android.app.NotificationManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A timed study session: a countdown (25 or 50 minutes) or a stopwatch that runs until you stop it.
 *
 * The state lives in Config, so it survives the dashboard closing and the watcher restarting. The
 * time is written to the event store when the session ends, and counts as study in Exam mode even
 * when you were studying from a book with the phone face down.
 */
object Focus {

    /** A stopwatch left running stops itself after this long. */
    const val MAX_MS = 4 * 3_600_000L
    private const val CHANNEL = "focus_done"
    private const val NOTE_ID = 43

    fun active(cfg: Config): Boolean = cfg.focusStart > 0L

    /** Start a session. [minutes] = 0 for a stopwatch. Replaces any session already running. */
    fun start(context: Context, subject: String, minutes: Int, lock: Boolean, now: Long = System.currentTimeMillis()) {
        val cfg = Config(context)
        if (active(cfg)) stop(context, now)
        cfg.focusSubject = subject.trim().take(40).ifEmpty { "Study" }
        cfg.focusLock = lock
        cfg.focusPlanMin = minutes.coerceIn(0, 240)
        cfg.focusEnd = if (minutes > 0) now + minutes * 60_000L else 0L
        cfg.focusStart = now
        LockService.sync(context)
    }

    /**
     * End the session now and keep the time studied. Returns the minutes recorded.
     * Called for "Stop" and for "End the session" on the study screen.
     */
    fun stop(context: Context, now: Long = System.currentTimeMillis()): Int {
        val cfg = Config(context)
        if (!active(cfg)) return 0
        val end = when {
            cfg.focusEnd in 1..now -> cfg.focusEnd                     // already over: keep the planned end
            else -> minOf(now, cfg.focusStart + MAX_MS)
        }
        val minutes = finish(context, cfg, end)
        LockService.sync(context)
        return minutes
    }

    /** "+10 min" on the study notification. Only for a countdown; a stopwatch has no end to move. */
    fun extend(context: Context, minutes: Int) {
        val cfg = Config(context)
        if (!active(cfg) || cfg.focusEnd <= 0L) return
        cfg.focusEnd += minutes * 60_000L
        cfg.focusPlanMin = (cfg.focusPlanMin + minutes).coerceAtMost(240)
        LockService.sync(context)
    }

    /** "Another 25" on the session-done notification: same subject, same lock. */
    fun again(context: Context) {
        val cfg = Config(context)
        if (active(cfg)) return
        start(context, cfg.focusSubject, 25, cfg.focusLock)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID)
    }

    /** Close a countdown that ran out, or a stopwatch left on too long. True when one was closed. */
    fun finishIfDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val cfg = Config(context)
        if (!active(cfg)) return false
        val due = if (cfg.focusEnd > 0L) cfg.focusEnd else cfg.focusStart + MAX_MS
        if (now < due) return false
        val minutes = finish(context, cfg, due)
        notifyDone(context, cfg.focusSubject, minutes, countdown = cfg.focusEnd > 0L)
        LockService.sync(context)
        return true
    }

    private fun finish(context: Context, cfg: Config, end: Long): Int {
        val start = cfg.focusStart
        cfg.focusStart = 0L; cfg.focusEnd = 0L                           // cleared first: never record twice
        runCatching { EventStore.get(context).study(cfg.focusSubject, start, end) }
        runCatching { FootprintWidget.refresh(context) }
        val minutes = ((end - start) / 60_000L).toInt()
        runCatching { Motivation.askAfterStudy(cfg, cfg.focusSubject, minutes) }   // "leave a note for later"
        return minutes
    }

    /** What the dashboard needs to draw the timer. */
    fun state(context: Context, now: Long = System.currentTimeMillis()): JSONObject {
        val cfg = Config(context)
        finishIfDue(context, now)
        val subjects = runCatching { EventStore.get(context).recentSubjects() }.getOrDefault(emptyList())
        return JSONObject()
            .put("active", active(cfg))
            .put("start", cfg.focusStart)
            .put("end", cfg.focusEnd)
            .put("subject", cfg.focusSubject)
            .put("lock", cfg.focusLock)
            .put("plan", cfg.focusPlanMin)
            .put("now", now)
            .put("canLock", LockService.canDrawOver(context))
            .put("distract", RuleStore.distract(context, cfg).size)
            .put("subjects", JSONArray(subjects))
    }

    private fun notifyDone(context: Context, subject: String, minutes: Int, countdown: Boolean) {
        if (!Nudges.canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        Notify.channel(context, CHANNEL, "Study sessions", "When a study session you timed is over.",
            NotificationManager.IMPORTANCE_DEFAULT)
        val title = if (countdown) "$minutes minutes of $subject, done" else "Stopwatch stopped after ${minutes / 60}h ${minutes % 60}m"
        val text = when {
            minutes >= Motivation.ASK_AFTER_MIN -> "Tap to leave a line for the you who'll want to scroll later."
            countdown -> "Take a short break. Your apps are unlocked."
            else -> "It stops by itself after 4 hours. The time is saved."
        }
        val card = Notify.card(context, "Study · $subject", "${FutureYou.hm(minutes)} done.",
            if (minutes >= Motivation.ASK_AFTER_MIN) "Leave a line for later you while it's fresh." else "Take a short break.")
        // Opens the note box: right after studying is when a note to your later self is most honest.
        val b = Notify.builder(context, CHANNEL)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(Notify.open(context, 1, "note"))
            .setSubText("Study")
            .addAction(Notify.action(context, 61, "Another 25 min", AlarmReceiver.FOCUS_AGAIN))
        Notify.decorate(b, card, Notify.tile(context, FutureYou.hm(minutes).substringBefore(' ')), text)
        runCatching { nm.notify(NOTE_ID, b.build()) }
    }
}
