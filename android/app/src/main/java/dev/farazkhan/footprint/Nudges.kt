package dev.farazkhan.footprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Nudges are worked out by the dashboard from the sessions you marked "not worth it" and saved here.
 * An alarm (Notify.schedule) wakes the app at each one's minute; the 15-minute job is the backup.
 * Each fires at most once a day, inside its own 20-minute window, in your own words when it can:
 * your verdict on that app at this time of day, and a note you wrote for later. Buttons on it:
 * "I'll skip it", "In 10 min" and "Not useful" (that warning is never sent again).
 */
object Nudges {
    private const val CHANNEL = "nudges"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warnings just before the moments you tend to regret, and review reminders."
                enableVibration(false)
            }
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    /** Called by the dashboard through the bridge whenever the model changes. */
    fun save(context: Context, list: JSONArray) {
        Config(context).nudges = list.toString()
        Notify.schedule(context)
    }

    private fun list(cfg: Config): JSONArray = runCatching { JSONArray(cfg.nudges) }.getOrNull() ?: JSONArray()

    private fun idOf(n: JSONObject, i: Int) = n.optString("id").ifBlank { "nudge$i" }

    fun slots(cfg: Config): List<NotifyPlan.Slot> {
        val a = list(cfg)
        return (0 until a.length()).mapNotNull { i ->
            val n = a.optJSONObject(i) ?: return@mapNotNull null
            val h = n.optInt("hour", -1)
            if (h < 0) null else NotifyPlan.Slot(idOf(n, i), h * 60 + n.optInt("minute", 0))
        }
    }

    private fun today(now: Long): String = Notify.dayKey(now)

    fun firedToday(cfg: Config, now: Long): Set<String> {
        val fired = runCatching { JSONObject(cfg.nudgesFired) }.getOrNull() ?: return emptySet()
        val day = today(now)
        return fired.keys().asSequence().filter { fired.optString(it) == day }.toSet()
    }

    fun snoozes(cfg: Config): Map<String, Long> {
        val o = runCatching { JSONObject(cfg.nudgeSnoozes) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    /** Fires anything due now: alarm, 15-minute job and the app opening all call this. */
    fun fireDue(context: Context, now: Long = System.currentTimeMillis()) {
        val cfg = Config(context)
        val all = list(cfg)
        if (all.length() == 0 || !cfg.nudgesEnabled || !canPost(context)) return
        val minuteOfDay = ((now - Limits.startOfToday(now)) / 60_000L).toInt()
        if (NotifyPlan.inQuiet(minuteOfDay, cfg.quietFrom, cfg.quietTo)) return

        val fired = runCatching { JSONObject(cfg.nudgesFired) }.getOrNull() ?: JSONObject()
        val day = today(now)
        val muted = cfg.nudgesMuted
        val snoozed = snoozes(cfg).toMutableMap()
        ensureChannel(context)
        for (i in 0 until all.length()) {
            val n = all.optJSONObject(i) ?: continue
            val id = idOf(n, i)
            val slot = NotifyPlan.Slot(id, n.optInt("hour", -1) * 60 + n.optInt("minute", 0))
            val snoozeAt = snoozed[id]
            val fromSnooze = snoozeAt != null && now >= snoozeAt && id !in muted
            if (!fromSnooze && (n.optInt("hour", -1) < 0 ||
                    !NotifyPlan.due(slot, minuteOfDay, fired.optString(id) == day, id in muted, cfg.quietFrom, cfg.quietTo))) continue
            if (fromSnooze) snoozed.remove(id)
            post(context, cfg, n, id, now)
            fired.put(id, day)
        }
        // drop snoozes that are long past (phone was off)
        snoozed.entries.removeAll { now - it.value > NotifyPlan.WINDOW_MIN * 60_000L * 3 }
        cfg.nudgeSnoozes = JSONObject(snoozed as Map<*, *>).toString()
        cfg.nudgesFired = fired.toString()
    }

    private fun post(context: Context, cfg: Config, n: JSONObject, id: String, now: Long) {
        val pkg = n.optString("pkg")
        val app = n.optString("app")
        val isRisk = pkg.isNotBlank()
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        val opts = Motivation.options(cfg)

        // your own words: the verdict on this app at this time of day, and a note to later you
        val verdict = if (isRisk && opts.verdict) runCatching {
            val zone = java.util.TimeZone.getDefault()
            val answers = EventStore.get(context).answersFor(pkg).map { (start, regret) ->
                val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = start }
                FutureYou.Answer(start, cal.get(java.util.Calendar.HOUR_OF_DAY), regret)
            }
            FutureYou.verdict(answers, hour)?.let { FutureYou.verdictLine(it, app.ifBlank { "this app" }) }
        }.getOrNull() else null
        val note = if (isRisk && opts.notes) runCatching {
            FutureYou.pickNote(Motivation.notes(cfg), FutureYou.Moment.INTENT, hour, now)
        }.getOrNull() else null
        note?.let { runCatching { Motivation.markShown(cfg, it.id, now) } }

        val text = NotifyPlan.nudgeBody(n.optString("text"), verdict, note?.text)
        val title = when {
            isRisk && app.isNotBlank() -> "Before you open $app"
            else -> n.optString("title").ifBlank { "Footprint" }
        }
        val code = id.hashCode()
        val clock = "%02d:%02d".format(hour, java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.MINUTE))
        val card = if (isRisk) Notify.card(context, "About that time · $clock", app.ifBlank { "Heads up" },
                line = verdict ?: n.optString("text"),
                note = note?.text, noteMeta = note?.let { FutureYou.ago(it.createdMs, now) })
            else Notify.card(context, "Worth it? · $clock", "Two taps each.", n.optString("text"))
        val tile = Notify.tile(context, if (isRisk) app.take(2).ifBlank { "!" } else "?")

        val b = Notify.builder(context, CHANNEL)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(Notify.open(context, code, if (isRisk) null else "worth"))
            .setSubText(if (isRisk) "Heads up" else "Check-in")
        Notify.decorate(b, card, tile, text)
        if (isRisk) {
            val extras = { it: Intent -> it.putExtra(AlarmReceiver.EXTRA_ID, id); it.putExtra(AlarmReceiver.EXTRA_PKG, pkg); Unit }
            b.addAction(Notify.action(context, code + 1, "I'll skip it", AlarmReceiver.SKIP, extras))
            b.addAction(Notify.action(context, code + 2, "In 10 min", AlarmReceiver.SNOOZE, extras))
            b.addAction(Notify.action(context, code + 3, "Not useful", AlarmReceiver.MUTE, extras))
        }
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(code, b.build()) }
    }

    /** "Send a sample" in Settings: a warning as it would look, using your own answers if you have any. */
    fun preview(context: Context) {
        val cfg = Config(context)
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val top = runCatching { EventStore.get(context).recentApps(now - 7 * 86_400_000L).firstOrNull() }.getOrNull()
        val n = JSONObject().put("id", "preview").put("title", "About that time")
            .put("pkg", top?.first ?: "com.instagram.android").put("app", top?.second ?: "Instagram")
            .put("text", "Last 6 times around now, ${top?.second ?: "Instagram"} took 24m you said wasn't worth it.")
        post(context, cfg, n, "preview", now)
    }

    /** A button on a warning: say thanks briefly, remember the answer, and plan the next alarm. */
    fun answer(context: Context, action: String, id: String, pkg: String, now: Long) {
        if (id.isBlank()) return
        val cfg = Config(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val value = when (action) { AlarmReceiver.SKIP -> 0.0; AlarmReceiver.SNOOZE -> 1.0; else -> 2.0 }
        runCatching { EventStore.get(context).point("nudge", now, pkg, value) }
        val (title, text) = when (action) {
            AlarmReceiver.SKIP -> "Good call." to "Noted. That's one evening back."
            AlarmReceiver.SNOOZE -> {
                val s = snoozes(cfg).toMutableMap(); s[id] = now + NotifyPlan.SNOOZE_MIN * 60_000L
                cfg.nudgeSnoozes = JSONObject(s as Map<*, *>).toString()
                "In 10 minutes, then." to "Footprint will remind you once more."
            }
            else -> {
                cfg.nudgesMuted = cfg.nudgesMuted + id
                "Got it, no more of this one." to "Unmute it in Settings > Reminders."
            }
        }
        runCatching {
            nm.notify(id.hashCode(), Notify.builder(context, CHANNEL)
                .setContentTitle(title).setContentText(text)
                .setTimeoutAfter(4_000L).setOnlyAlertOnce(true).build())
        }
    }
}
