package dev.farazkhan.footprint

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How every Footprint notification looks: the dashboard's colours and fonts, drawn into a card image
 * (shown when the notification is expanded) and a small tile (shown beside it when collapsed).
 * Also the alarm that makes warnings and the morning summary arrive on time.
 */
object Notify {
    const val RUST = 0xFFC7503A.toInt()
    private const val INK = 0xFF151515.toInt()
    private const val BONE = 0xFFEFEBE2.toInt()
    private const val MUTE = 0xFF8C877E.toInt()
    private const val GRID = 0xFF222120.toInt()

    // ---------------------------------------------------------------- channels
    const val CH_MORNING = "morning"

    fun channel(context: Context, id: String, name: String, description: String, importance: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { this.description = description })
    }

    /** The common look: footprint icon, rust accent, dismissed on tap. */
    fun builder(context: Context, channel: String): Notification.Builder =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(RUST)
            .setAutoCancel(true)

    fun open(context: Context, code: Int, screen: String? = null): PendingIntent =
        PendingIntent.getActivity(context, code,
            Intent(context, MainActivity::class.java).apply {
                if (screen != null) putExtra(MainActivity.EXTRA_SCREEN, screen)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A button on a notification that comes back to [AlarmReceiver]. */
    fun action(context: Context, code: Int, label: String, action: String, extras: (Intent) -> Unit = {}): Notification.Action {
        val pi = PendingIntent.getBroadcast(context, code,
            Intent(context, AlarmReceiver::class.java).setAction(action).also(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), label, pi).build()
    }

    /** Puts [card] in the expanded view and [tile] beside the collapsed one. Never fails the notification. */
    fun decorate(b: Notification.Builder, card: Bitmap?, tile: Bitmap?, summary: String): Notification.Builder {
        if (tile != null) b.setLargeIcon(tile)
        if (card != null) b.setStyle(Notification.BigPictureStyle().bigPicture(card)
            .bigLargeIcon(null as Bitmap?).setSummaryText(summary))
        else b.setStyle(Notification.BigTextStyle().bigText(summary))
        return b
    }

    // ---------------------------------------------------------------- drawing
    private class Fonts(context: Context) {
        val heavy: Typeface = font(context, R.font.montserrat_black, Typeface.DEFAULT_BOLD)
        val hand: Typeface = font(context, R.font.caveat_bold, Typeface.SERIF)
        val mono: Typeface = font(context, R.font.courier_prime_bold, Typeface.MONOSPACE)
        private fun font(c: Context, id: Int, fallback: Typeface) = runCatching { c.resources.getFont(id) }.getOrNull() ?: fallback
    }

    private fun paint(face: Typeface, size: Float, color: Int, spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = size; this.color = color; letterSpacing = spacing
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 0.92f)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()

    private fun background(c: Canvas, w: Int, h: Int) {
        c.drawColor(INK)
        val g = Paint().apply { color = GRID; strokeWidth = 2f }
        var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), g); x += 48f }
        var y = 0f; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, g); y += 48f }
        c.drawRect(0f, 0f, 14f, h.toFloat(), Paint().apply { color = RUST })     // the rust edge, like the cards
    }

    /**
     * A 1200×600 card in the dashboard's style: a spaced-caps kicker, a heavy headline, a handwritten
     * rust line, then either a paper note (a message from past you) or up to three stats.
     */
    fun card(
        context: Context, kicker: String, headline: String, line: String?,
        note: String? = null, noteMeta: String? = null, stats: List<Pair<String, String>> = emptyList(),
    ): Bitmap? = runCatching {
        val w = 1200; val h = 600; val pad = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val f = Fonts(context)
        background(c, w, h)
        val inner = w - pad * 2

        c.drawText(kicker.uppercase(Locale.ROOT), pad.toFloat(), 96f, paint(f.mono, 28f, MUTE, 0.2f))

        // headline: as big as fits on one line
        val hp = paint(f.heavy, 116f, BONE, -0.03f)
        while (hp.measureText(headline) > inner && hp.textSize > 54f) hp.textSize -= 4f
        c.drawText(TextUtils.ellipsize(headline, hp, inner.toFloat(), TextUtils.TruncateAt.END).toString(),
            pad.toFloat(), 120f + hp.textSize * 0.82f, hp)
        var y = 140f + hp.textSize * 0.82f

        if (!line.isNullOrBlank()) {
            val l = layout(line, paint(f.hand, if (note != null) 44f else 50f, RUST), inner, 2)
            c.save(); c.translate(pad.toFloat(), y); l.draw(c); c.restore()
            y += l.height + 26f
        }

        if (!note.isNullOrBlank()) {
            // a paper tag, slightly turned, like the stickers in the dashboard
            val np = paint(f.hand, 44f, INK)
            val nl = layout("“$note”", np, inner - 60, 2)
            val boxH = nl.height + (if (noteMeta != null) 58f else 34f)
            val top = minOf(y, h - boxH - 34f)
            c.save(); c.rotate(-1.2f, w / 2f, top + boxH / 2)
            c.drawRect(pad + 8f, top + 8f, pad + 8f + inner, top + 8f + boxH, Paint().apply { color = RUST })
            c.drawRect(pad.toFloat(), top, (pad + inner).toFloat(), top + boxH, Paint().apply { color = BONE })
            c.translate(pad + 30f, top + 18f); nl.draw(c)
            if (noteMeta != null) c.drawText(noteMeta.uppercase(Locale.ROOT), 0f, nl.height + 26f, paint(f.mono, 22f, 0xFF5A5650.toInt(), 0.15f))
            c.restore()
        } else if (stats.isNotEmpty()) {
            val n = stats.size.coerceAtMost(3)
            val gap = 22f; val bw = (inner - gap * (n - 1)) / n; val top = maxOf(y + 6f, h - 190f); val bh = 150f
            val border = Paint().apply { color = 0xFF3A3835.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
            for (i in 0 until n) {
                val (label, value) = stats[i]
                val left = pad + i * (bw + gap)
                c.drawRect(RectF(left, top, left + bw, top + bh), border)
                c.drawText(label.uppercase(Locale.ROOT), left + 22f, top + 44f, paint(f.mono, 22f, MUTE, 0.18f))
                val vp = paint(f.heavy, 54f, BONE, -0.02f)
                while (vp.measureText(value) > bw - 44f && vp.textSize > 26f) vp.textSize -= 2f
                c.drawText(value, left + 22f, top + bh - 30f, vp)
            }
        }
        val mark = paint(f.heavy, 26f, 0xFF4A4743.toInt(), -0.02f)
        c.drawText("foot print", w - pad - mark.measureText("foot print"), 96f, mark)
        bmp
    }.getOrNull()

    /** A small square for the collapsed view: one heavy word or number on ink, rust underline. */
    fun tile(context: Context, text: String): Bitmap? = runCatching {
        val s = 256
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val f = Fonts(context)
        c.drawColor(INK)
        val p = paint(f.heavy, 120f, BONE, -0.04f).apply { textAlign = Paint.Align.CENTER }
        while (p.measureText(text) > s - 40 && p.textSize > 40f) p.textSize -= 4f
        c.drawText(text, s / 2f, s / 2f + p.textSize * 0.36f, p)
        c.drawRect(s * 0.3f, s * 0.78f, s * 0.7f, s * 0.78f + 12f, Paint().apply { color = RUST })
        bmp
    }.getOrNull()

    // ---------------------------------------------------------------- on time
    /** Everything due now: warnings, snoozed warnings, the morning summary, the weekly review. */
    fun runDue(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching { Nudges.fireDue(context, now) }
        runCatching { Morning.fireDue(context, now) }
        runCatching { Weekly.fireDue(context, now) }
    }

    /**
     * Wakes the app for the next warning or summary. The 15-minute job alone can be held back for
     * hours by battery saving, and a warning is useless after the moment it warns about. An inexact
     * "allow while idle" alarm needs no special permission and still runs in Doze.
     */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()) = runCatching {
        val cfg = Config(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return@runCatching
        val pi = PendingIntent.getBroadcast(context, 900,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dayStart = Limits.startOfToday(now)
        val next = if (!cfg.disclosureAccepted) null else NotifyPlan.nextAlarm(
            now, dayStart,
            slots = if (cfg.nudgesEnabled) Nudges.slots(cfg) else emptyList(),
            fired = Nudges.firedToday(cfg, now), muted = cfg.nudgesMuted,
            snoozes = Nudges.snoozes(cfg).values, quietFrom = cfg.quietFrom, quietTo = cfg.quietTo,
            morning = cfg.morningMinute, morningSentToday = cfg.morningSentDay == dayKey(now))
        if (next == null) am.cancel(pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(next, now + 5_000L), pi)
    }

    fun dayKey(now: Long): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(now))
}

/** "Yesterday: 4h 12m on your phone", once a morning, quietly. */
object Morning {
    private const val NOTE_ID = 46

    fun fireDue(context: Context, now: Long = System.currentTimeMillis()) {
        val cfg = Config(context)
        if (!cfg.disclosureAccepted || cfg.morningMinute < 0 || !Nudges.canPost(context)) return
        val today = Limits.startOfToday(now)
        val day = Notify.dayKey(now)
        if (!NotifyPlan.morningDue(((now - today) / 60_000L).toInt(), cfg.morningMinute, cfg.morningSentDay == day)) return
        cfg.morningSentDay = day                                          // once, even if drawing fails
        post(context, cfg, today)
    }

    /** Also used by "Send a test" in Settings. */
    fun post(context: Context, cfg: Config, today: Long) {
        val store = EventStore.get(context)
        val yStart = today - 86_400_000L
        val days = store.dailyMinutes(today, 8)                           // oldest first; [6] = yesterday
        val past = days.take(6).filter { it > 0 }
        val usual = if (past.size >= 3) past.sum() / past.size else null
        val unlocks = store.countType("unlock", yStart, today)
        val steps = if (cfg.collectSteps) store.stepsBetween(yStart, today) else null
        val top = store.topApp(yStart, today)
        val s = NotifyPlan.morning(days[6], usual, unlocks, steps, top) ?: return

        Notify.channel(context, Notify.CH_MORNING, "Morning summary", "Yesterday in one quiet notification.",
            NotificationManager.IMPORTANCE_LOW)
        val dayName = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(yStart))
        val stats = ArrayList<Pair<String, String>>()
        stats += "Unlocks" to unlocks.toString()
        if (steps != null && steps > 0) stats += "Steps" to NotifyPlan.grouped(steps)
        if (top != null) stats += "Most" to top.first
        val card = Notify.card(context, "Yesterday · $dayName", NotifyPlan.hm(days[6]),
            NotifyPlan.vsUsual(days[6], usual), stats = stats)
        val tile = Notify.tile(context, if (days[6] >= 60) "${days[6] / 60}h" else "${days[6]}m")
        val b = Notify.builder(context, Notify.CH_MORNING)
            .setContentTitle(s.title).setContentText(s.body)
            .setContentIntent(Notify.open(context, NOTE_ID, "today"))
            .setSubText("Morning summary")
        Notify.decorate(b, card, tile, s.body)
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, b.build()) }
    }
}

/** Wakes up for due notifications and handles the buttons on them. */
class AlarmReceiver : android.content.BroadcastReceiver() {
    companion object {
        const val TICK = "dev.farazkhan.footprint.TICK"
        const val SKIP = "dev.farazkhan.footprint.NUDGE_SKIP"
        const val SNOOZE = "dev.farazkhan.footprint.NUDGE_SNOOZE"
        const val MUTE = "dev.farazkhan.footprint.NUDGE_MUTE"
        const val FOCUS_PLUS = "dev.farazkhan.footprint.FOCUS_PLUS"
        const val FOCUS_STOP = "dev.farazkhan.footprint.FOCUS_STOP"
        const val FOCUS_AGAIN = "dev.farazkhan.footprint.FOCUS_AGAIN"
        const val EXTRA_ID = "id"
        const val EXTRA_PKG = "pkg"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                val now = System.currentTimeMillis()
                when (intent.action) {
                    TICK -> Notify.runDue(context, now)
                    SKIP, SNOOZE, MUTE -> Nudges.answer(context, intent.action!!,
                        intent.getStringExtra(EXTRA_ID).orEmpty(), intent.getStringExtra(EXTRA_PKG).orEmpty(), now)
                    FOCUS_PLUS -> Focus.extend(context, 10)
                    FOCUS_STOP -> { if (!Config(context).focusLock) Focus.stop(context, now) }
                    FOCUS_AGAIN -> Focus.again(context)
                }
                Notify.schedule(context)
            } finally { done.finish() }
        }.start()
    }
}
