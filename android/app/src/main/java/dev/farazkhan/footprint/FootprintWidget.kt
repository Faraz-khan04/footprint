package dev.farazkhan.footprint

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Home-screen widget: today's total, then one page per section — the sessions waiting to be
 * judged, what your limits have left, today's apps, and the last seven days.
 *
 * The arrows move between pages and the dots show where you are. A single page is drawn at a
 * time, which is the only way to get an exact layout: a swipeable collection (StackView) always
 * leaves the other pages showing around the edges.
 */
class FootprintWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context, id))
    }

    /** Resized: the page fits its rows to the height, so draw it again. */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, widgetId, newOptions)
        manager.updateAppWidget(widgetId, build(context, widgetId))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val cfg = Config(context)
        for (id in ids) cfg.setWidgetPage(id, 0)
        super.onDeleted(context, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_PAGE -> {
                val cfg = Config(context)
                cfg.setWidgetPage(id, cfg.widgetPage(id) + intent.getIntExtra(EX_STEP, 1))
                refresh(context)
            }
            ACTION_ANSWER -> {
                val key = intent.getStringExtra(EX_KEY).orEmpty()
                if (key.isNotEmpty()) runCatching {
                    EventStore.get(context).setLabel(
                        key, intent.getBooleanExtra(EX_REGRET, false),
                        intent.getStringExtra(EX_PKG).orEmpty(), intent.getLongExtra(EX_START, 0L)
                    )
                }
                refresh(context)
            }
            ACTION_OPEN -> runCatching {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    companion object {
        const val ACTION_PAGE = "dev.farazkhan.footprint.WIDGET_PAGE"
        const val ACTION_ANSWER = "dev.farazkhan.footprint.WIDGET_ANSWER"
        const val ACTION_OPEN = "dev.farazkhan.footprint.WIDGET_OPEN"
        const val EX_KEY = "key"
        const val EX_PKG = "pkg"
        const val EX_START = "start"
        const val EX_REGRET = "regret"
        const val EX_STEP = "step"

        /** One row slot in the layout. */
        private data class Slot(val root: Int, val rank: Int, val title: Int, val value: Int,
                                val sub: Int, val bar: Int)

        private val DOTS = listOf(R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4,
            R.id.dot5, R.id.dot6, R.id.dot7, R.id.dot8)

        private val ROWS = listOf(
            Slot(R.id.row1, R.id.row1_rank, R.id.row1_title, R.id.row1_value, R.id.row1_sub, R.id.row1_bar),
            Slot(R.id.row2, R.id.row2_rank, R.id.row2_title, R.id.row2_value, R.id.row2_sub, R.id.row2_bar),
            Slot(R.id.row3, R.id.row3_rank, R.id.row3_title, R.id.row3_value, R.id.row3_sub, R.id.row3_bar),
            Slot(R.id.row4, R.id.row4_rank, R.id.row4_title, R.id.row4_value, R.id.row4_sub, R.id.row4_bar),
            Slot(R.id.row5, R.id.row5_rank, R.id.row5_title, R.id.row5_value, R.id.row5_sub, R.id.row5_bar),
            Slot(R.id.row6, R.id.row6_rank, R.id.row6_title, R.id.row6_value, R.id.row6_sub, R.id.row6_bar),
            Slot(R.id.row7, R.id.row7_rank, R.id.row7_title, R.id.row7_value, R.id.row7_sub, R.id.row7_bar)
        )

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FootprintWidget::class.java))
            for (id in ids) manager.updateAppWidget(id, build(context, id))
        }

        /** How many rows fit at the widget's current height, so a small widget isn't cut off. */
        private fun rowsThatFit(context: Context, widgetId: Int): Int {
            val h = runCatching {
                AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            }.getOrDefault(0)
            if (h <= 0) return 5
            return ((h - 104) / 30).coerceIn(2, ROWS.size)   // the total and the title take ~104dp
        }

        private fun build(context: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_footprint)
            val cfg = Config(context)
            val store = EventStore.get(context)
            val now = System.currentTimeMillis()
            val todayStart = Limits.startOfToday(now)

            val todayMin = runCatching { store.weekHeatmap(todayStart).second }.getOrDefault(0)
            v.setTextViewText(R.id.widget_total, "${todayMin / 60}h ${todayMin % 60}m")

            val cards = runCatching { cards(context, cfg, store, now, todayStart, widgetId) }
                .getOrDefault(emptyList())

            if (cards.isEmpty()) {
                v.setViewVisibility(R.id.q_block, View.GONE)
                v.setViewVisibility(R.id.list_block, View.GONE)
                v.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                v.setTextViewText(R.id.card_title, "")
                for (d in DOTS) v.setViewVisibility(d, View.GONE)
                v.setViewVisibility(R.id.nav_prev, View.GONE)
                v.setViewVisibility(R.id.nav_next, View.GONE)
                v.setOnClickPendingIntent(R.id.widget_root, open(context))
                return v
            }

            v.setViewVisibility(R.id.widget_empty, View.GONE)
            val page = ((cfg.widgetPage(widgetId) % cards.size) + cards.size) % cards.size   // wraps both ways
            val card = cards[page]

            v.setTextViewText(R.id.card_title, card.title)
            // one dot per page, the current one in rust
            for (i in DOTS.indices) {
                if (i >= cards.size || cards.size < 2) {
                    v.setViewVisibility(DOTS[i], View.GONE)
                } else {
                    v.setViewVisibility(DOTS[i], View.VISIBLE)
                    v.setImageViewResource(DOTS[i], if (i == page) R.drawable.dot_on else R.drawable.dot_off)
                }
            }
            v.setViewVisibility(R.id.nav_prev, if (cards.size > 1) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.nav_next, if (cards.size > 1) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.nav_prev, pageIntent(context, widgetId, -1))
            v.setOnClickPendingIntent(R.id.nav_next, pageIntent(context, widgetId, +1))

            val q = card.question
            if (card.kind == WidgetFeed.QUESTION && q != null) {
                v.setViewVisibility(R.id.q_block, View.VISIBLE)
                v.setViewVisibility(R.id.list_block, View.GONE)
                v.setTextViewText(R.id.q_title, q.title)
                v.setTextViewText(R.id.q_sub, "${q.sub} · ${q.value}")
                v.setOnClickPendingIntent(R.id.q_yes, answer(context, widgetId, q, false))
                v.setOnClickPendingIntent(R.id.q_no, answer(context, widgetId, q, true))
            } else {
                v.setViewVisibility(R.id.q_block, View.GONE)
                v.setViewVisibility(R.id.list_block, View.VISIBLE)
                for (i in ROWS.indices) {
                    val (root, rank, title, value, sub, bar) = ROWS[i]
                    val r = card.rows.getOrNull(i)
                    if (r == null) { v.setViewVisibility(root, View.GONE); continue }
                    v.setViewVisibility(root, View.VISIBLE)
                    v.setTextViewText(rank, r.rank)
                    v.setViewVisibility(rank, if (r.rank.isEmpty()) View.GONE else View.VISIBLE)
                    v.setTextViewText(title, r.title)
                    v.setTextViewText(value, r.value)
                    v.setTextViewText(sub, r.sub)
                    v.setViewVisibility(sub, if (r.sub.isEmpty()) View.GONE else View.VISIBLE)
                    v.setProgressBar(bar, 100, (r.fill * 100).toInt().coerceIn(0, 100), false)
                    v.setViewVisibility(bar, if (r.fill > 0.0) View.VISIBLE else View.GONE)
                }
                v.setOnClickPendingIntent(R.id.list_block, open(context))
            }
            v.setOnClickPendingIntent(R.id.widget_total, open(context))
            return v
        }

        private fun cards(
            context: Context, cfg: Config, store: EventStore, now: Long, todayStart: Long, widgetId: Int
        ): List<WidgetFeed.Card> {
            val answered = HashSet<String>()
            val labels = store.labels()
            for (k in labels.keys()) answered.add(k)

            val skip = HashSet<String>(cfg.hiddenApps)
            skip.add(context.packageName)
            runCatching {
                context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
            }.getOrNull()?.let { skip.add(it) }

            val sessions = store.sessionsBetween(now - 2 * 86_400_000L, now - 2 * 60_000L)
            val apps = store.recentApps(todayStart).map { WidgetFeed.AppUse(it.first, it.second, it.third) }
            val limits = Limits.all(cfg).entries.map { (pkg, min) ->
                val label = apps.firstOrNull { it.pkg == pkg }?.label
                    ?: sessions.firstOrNull { it.pkg == pkg }?.label ?: pkg
                WidgetFeed.Limit(pkg, label, min, (Limits.usedTodayMs(context, pkg, now) / 60_000L).toInt())
            }
            val week = store.dailyMinutes(todayStart, 7)
            return WidgetFeed.cards(sessions, answered, skip, apps, limits, week, todayStart,
                cfg.askMinMinutes, maxApps = rowsThatFit(context, widgetId))
        }

        // ---- the taps
        private fun open(context: Context) = PendingIntent.getBroadcast(
            context, 1, Intent(context, FootprintWidget::class.java).setAction(ACTION_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun pageIntent(context: Context, widgetId: Int, step: Int) = PendingIntent.getBroadcast(
            context, widgetId * 10 + (if (step > 0) 2 else 3),
            Intent(context, FootprintWidget::class.java).setAction(ACTION_PAGE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra(EX_STEP, step),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun answer(context: Context, widgetId: Int, q: WidgetFeed.Row, regret: Boolean) =
            PendingIntent.getBroadcast(
                context, (q.key + regret).hashCode(),
                Intent(context, FootprintWidget::class.java).setAction(ACTION_ANSWER)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(EX_KEY, q.key).putExtra(EX_PKG, q.pkg)
                    .putExtra(EX_START, q.start).putExtra(EX_REGRET, regret),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
