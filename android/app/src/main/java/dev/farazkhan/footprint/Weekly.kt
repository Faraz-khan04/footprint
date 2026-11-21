package dev.farazkhan.footprint

import android.app.NotificationManager
import android.content.Context

/**
 * The weekly review: once a week, a notification with this week's screen time against last
 * week's, which opens the review in the You tab. Checked by the 15-minute job, like nudges.
 */
object Weekly {

    private const val CHANNEL = "weekly_review"
    private const val NOTE_ID = 44
    /** A review that was missed by more than this (phone off, battery saver) is skipped, not sent late. */
    private const val LATE_MS = 12 * 3_600_000L

    fun fireDue(context: Context, now: Long = System.currentTimeMillis()) {
        val cfg = Config(context)
        if (!cfg.disclosureAccepted || cfg.weeklyDay < 0) return
        val due = WeekMath.lastOccurrence(cfg.weeklyDay, cfg.weeklyMinute, now)
        if (due == 0L || cfg.weeklyFiredFor >= due || now - due > LATE_MS) return
        cfg.weeklyFiredFor = due
        if (!Nudges.canPost(context)) return
        val days = runCatching { EventStore.get(context).dailyMinutes(Limits.startOfToday(now), 14) }.getOrNull() ?: return
        val last = days.take(7).sum(); val week = days.drop(7).sum()
        if (week == 0 && last == 0) return                                  // nothing recorded yet
        post(context, "Your week: ${WeekMath.fmt(week)} on your phone", WeekMath.compare(week, last), WeekMath.fmt(week),
            listOf("Last week" to WeekMath.fmt(last), "A day" to WeekMath.fmt(week / 7)))
    }

    private fun post(context: Context, title: String, text: String, headline: String, stats: List<Pair<String, String>>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        Notify.channel(context, CHANNEL, "Weekly review", "Once a week, how your week went.",
            NotificationManager.IMPORTANCE_DEFAULT)
        val card = Notify.card(context, "Your week", headline, text, stats = stats)
        val b = Notify.builder(context, CHANNEL)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(Notify.open(context, 2, "review"))
            .setSubText("Weekly review")
        Notify.decorate(b, card, Notify.tile(context, headline.substringBefore(' ')), text)
        runCatching { nm.notify(NOTE_ID, b.build()) }
    }
}
