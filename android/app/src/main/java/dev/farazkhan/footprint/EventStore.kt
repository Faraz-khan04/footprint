package dev.farazkhan.footprint

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything the app knows, in one private SQLite file on the phone.
 *
 * events: type = app | unlock | screen_on | screen_off | notif | steps | study
 *               | intent | lock_kept | lock_snoozed | lock_ended | lock_walked | reels_blocked
 *               | steps_rec (hourly steps from Google Play services, see RecordedSteps)
 *         app sessions use start_ms..end_ms; point events have end_ms = start_ms; steps keep a count in value.
 *         study sessions keep the subject in pkg. intent keeps the reason code in value; lock_* keep
 *         what the screen was for (see LockActivity.Mode) in value; reels_blocked keeps which feed
 *         (index in ReelsMatch.TARGETS) in value.
 * apps:   display name, category and icon for each app seen.
 */
class EventStore private constructor(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, "footprint.db", null, DB_VERSION) {

    companion object {
        /** 2: answers to "was it worth it?" moved here from the dashboard's browser storage. */
        const val DB_VERSION = 2
        const val KEEP_DAYS = 90
        @Volatile private var instance: EventStore? = null
        fun get(context: Context): EventStore =
            instance ?: synchronized(this) { instance ?: EventStore(context).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 type TEXT NOT NULL, pkg TEXT NOT NULL DEFAULT '',
                 start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, value REAL,
                 UNIQUE(type, pkg, start_ms))"""
        )
        db.execSQL("CREATE INDEX ix_events_start ON events(start_ms)")
        db.execSQL(
            """CREATE TABLE labels (
                 key TEXT PRIMARY KEY, pkg TEXT NOT NULL DEFAULT '',
                 start_ms INTEGER NOT NULL DEFAULT 0, regret INTEGER NOT NULL, at_ms INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE apps (
                 pkg TEXT PRIMARY KEY, label TEXT NOT NULL, category TEXT NOT NULL,
                 icon_b64 TEXT, updated_ms INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Adds the labels table. No existing data is touched; the dashboard hands its own
        // answers over on first run (see "importLabels" in MainActivity).
        if (oldVersion < 2) runCatching {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS labels (
                     key TEXT PRIMARY KEY, pkg TEXT NOT NULL DEFAULT '',
                     start_ms INTEGER NOT NULL DEFAULT 0, regret INTEGER NOT NULL, at_ms INTEGER NOT NULL)"""
            )
        }
    }

    // ---------------------------------------------------------------- worth-it answers
    /** All answers as { key: 0|1 }, 1 meaning regretted. */
    fun labels(): JSONObject {
        val out = JSONObject()
        readableDatabase.rawQuery("SELECT key, regret FROM labels", null).use { c ->
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1))
        }
        return out
    }

    fun labelCount(): Int =
        DatabaseUtils.queryNumEntries(readableDatabase, "labels").toInt()

    fun setLabel(key: String, regret: Boolean, pkg: String = "", startMs: Long = 0L) {
        val cv = ContentValues().apply {
            put("key", key); put("pkg", pkg); put("start_ms", startMs)
            put("regret", if (regret) 1 else 0); put("at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("labels", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteLabel(key: String) {
        writableDatabase.delete("labels", "key = ?", arrayOf(key))
    }

    /** True when this session already has an answer. */
    fun isLabelled(key: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM labels WHERE key = ? LIMIT 1", arrayOf(key)).use { it.moveToNext() }

    // ---------------------------------------------------------------- writes
    private fun insert(type: String, pkg: String, start: Long, end: Long, value: Double? = null) {
        val cv = ContentValues().apply {
            put("type", type); put("pkg", pkg); put("start_ms", start); put("end_ms", end)
            if (value != null) put("value", value)
        }
        writableDatabase.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun session(pkg: String, start: Long, end: Long) { if (end > start) insert("app", pkg, start, end) }
    fun point(type: String, at: Long, pkg: String = "") = insert(type, pkg, at, at)
    fun point(type: String, at: Long, pkg: String, value: Double) = insert(type, pkg, at, at, value)

    /** A timed study session. The subject goes where an app's package would. */
    fun study(subject: String, start: Long, end: Long) {
        if (end - start >= 60_000L) insert("study", subject.take(40), start, end)
    }
    fun steps(start: Long, end: Long, count: Double) = insert("steps", "", start, end, count)

    /** One hour of steps from Play services. Replaces the same hour: the current hour keeps filling up. */
    fun recordedSteps(start: Long, end: Long, count: Double) {
        val cv = ContentValues().apply {
            put("type", "steps_rec"); put("pkg", ""); put("start_ms", start); put("end_ms", end); put("value", count)
        }
        writableDatabase.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The time Play services' recorded hours cover; sensor readings inside it are dropped (StepMerge). */
    private fun recordedWindow(): Pair<Long, Long> {
        val cfg = Config(context)
        return if (cfg.recordingSince > 0 && cfg.recordingSyncedTo > cfg.recordingSince)
            cfg.recordingSince to cfg.recordingSyncedTo else 0L to 0L
    }

    /** All step rows that end after [fromMs], merged: recorded hours plus sensor readings outside them. */
    private fun stepRows(fromMs: Long, toMs: Long = Long.MAX_VALUE): List<StepMerge.Part> {
        val (a, b) = recordedWindow()
        val out = ArrayList<StepMerge.Part>()
        readableDatabase.rawQuery(
            "SELECT type, start_ms, end_ms, value FROM events WHERE type IN ('steps','steps_rec') AND end_ms > ? AND start_ms < ? ORDER BY start_ms",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val s = c.getLong(1); val e = c.getLong(2); val v = c.getDouble(3)
                if (c.getString(0) == "steps_rec") out += StepMerge.Part(s, e, v)
                else out += StepMerge.outside(s, e, v, a, b)
            }
        }
        return out
    }

    fun appNeedsInfo(pkg: String, maxAgeMs: Long): Boolean =
        readableDatabase.rawQuery("SELECT updated_ms FROM apps WHERE pkg = ?", arrayOf(pkg)).use { c ->
            !c.moveToFirst() || System.currentTimeMillis() - c.getLong(0) > maxAgeMs
        }

    fun saveApp(pkg: String, label: String, category: String, iconB64: String?) {
        val cv = ContentValues().apply {
            put("pkg", pkg); put("label", label); put("category", category)
            put("icon_b64", iconB64); put("updated_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("apps", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun prune(now: Long = System.currentTimeMillis(), keepDays: Int = KEEP_DAYS) {
        val days = keepDays.coerceIn(7, 365)
        writableDatabase.delete("events", "start_ms < ?", arrayOf((now - days * 86_400_000L).toString()))
    }

    /** Apps seen recently, most-used first: used by the "hide an app" list in Settings. */
    fun recentApps(sinceMs: Long): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        readableDatabase.rawQuery(
            """SELECT e.pkg, COALESCE(a.label, e.pkg), SUM(e.end_ms - e.start_ms) / 60000 AS mins
                 FROM events e LEFT JOIN apps a ON a.pkg = e.pkg
                 WHERE e.type = 'app' AND e.start_ms >= ?
                 GROUP BY e.pkg ORDER BY mins DESC LIMIT 60""",
            arrayOf(sinceMs.toString())
        ).use { c -> while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getInt(2))) }
        return out
    }

    /** Apps in these categories used since [sinceMs]: the starting "distracting apps" list. */
    fun recentAppsInCategories(sinceMs: Long, categories: Set<String>): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            """SELECT DISTINCT e.pkg, a.category FROM events e JOIN apps a ON a.pkg = e.pkg
                 WHERE e.type = 'app' AND e.start_ms >= ?""",
            arrayOf(sinceMs.toString())
        ).use { c -> while (c.moveToNext()) if (c.getString(1) in categories) out.add(c.getString(0)) }
        return out
    }

    /** Subjects of recent study sessions, most recent first. */
    fun recentSubjects(limit: Int = 6): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT pkg, MAX(start_ms) AS last FROM events WHERE type = 'study' AND pkg != '' GROUP BY pkg ORDER BY last DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** Minutes of timed study on [subject] (any capitalisation) since [sinceMs]. */
    fun studyMinutes(subject: String, sinceMs: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(end_ms - start_ms), 0) / 60000 FROM events WHERE type = 'study' AND LOWER(pkg) = LOWER(?) AND start_ms >= ?",
            arrayOf(subject.trim(), sinceMs.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Your latest "was it worth it?" answers for one app: (visit start, regretted), newest first. */
    fun answersFor(pkg: String, limit: Int = 60): List<Pair<Long, Boolean>> {
        val out = ArrayList<Pair<Long, Boolean>>()
        // Keys are "pkg|start". Answers handed over from the old browser storage have only the key.
        val escaped = pkg.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        readableDatabase.rawQuery(
            """SELECT key, start_ms, regret FROM labels
                 WHERE pkg = ? OR (pkg = '' AND key LIKE ? ESCAPE '\')""",
            arrayOf(pkg, "$escaped|%")
        ).use { c ->
            while (c.moveToNext()) {
                val start = c.getLong(1).takeIf { it > 0 }
                    ?: c.getString(0).substringAfterLast('|').toLongOrNull() ?: continue
                out.add(start to (c.getInt(2) == 1))
            }
        }
        return out.sortedByDescending { it.first }.take(limit)
    }

    /** Finished app sessions in a window, newest first: (key, pkg, label, start, end). */
    fun sessionsBetween(fromMs: Long, toMs: Long, limit: Int = 200):
        List<WidgetFeed.Session> {
        val out = ArrayList<WidgetFeed.Session>()
        readableDatabase.rawQuery(
            """SELECT e.pkg, COALESCE(a.label, e.pkg), e.start_ms, e.end_ms
                 FROM events e LEFT JOIN apps a ON a.pkg = e.pkg
                 WHERE e.type = 'app' AND e.start_ms >= ? AND e.start_ms <= ?
                 ORDER BY e.start_ms DESC LIMIT ?""",
            arrayOf(fromMs.toString(), toMs.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                WidgetFeed.Session(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))
            )
        }
        return out
    }

    /** How many point events of [type] (e.g. "unlock") fell in [fromMs, toMs). */
    fun countType(type: String, fromMs: Long, toMs: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE type = ? AND start_ms >= ? AND start_ms < ?",
            arrayOf(type, fromMs.toString(), toMs.toString())
        ).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    /**
     * Steps in [fromMs, toMs). A reading that only partly overlaps (Android read the counter late)
     * counts in proportion to the overlap, the same as the dashboard's spreadSteps.
     */
    fun stepsBetween(fromMs: Long, toMs: Long): Int =
        stepRows(fromMs, toMs).sumOf { StepMerge.overlap(it.start, it.end, it.steps, fromMs, toMs) }.toInt()

    /** The app used most in [fromMs, toMs): (label, minutes), or null. */
    fun topApp(fromMs: Long, toMs: Long): Pair<String, Int>? {
        val per = HashMap<String, Long>(); val names = HashMap<String, String>()
        readableDatabase.rawQuery(
            """SELECT e.pkg, COALESCE(a.label, e.pkg), e.start_ms, e.end_ms FROM events e
                 LEFT JOIN apps a ON a.pkg = e.pkg
                 WHERE e.type = 'app' AND e.end_ms > ? AND e.start_ms < ?""",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val ms = minOf(c.getLong(3), toMs) - maxOf(c.getLong(2), fromMs)
                if (ms <= 0) continue
                per[c.getString(0)] = (per[c.getString(0)] ?: 0L) + ms
                names[c.getString(0)] = c.getString(1)
            }
        }
        val best = per.maxByOrNull { it.value } ?: return null
        return (names[best.key] ?: best.key) to (best.value / 60_000L).toInt()
    }

    /** Minutes of app use per day for the last [days] days, oldest first. */
    fun dailyMinutes(todayStartMs: Long, days: Int): IntArray {
        val from = todayStartMs - (days - 1L) * 86_400_000L
        val out = IntArray(days)
        readableDatabase.rawQuery(
            """SELECT start_ms, end_ms FROM events
                 WHERE start_ms >= ? AND type = 'app' AND end_ms >= ?""",
            arrayOf((from - 86_400_000L).toString(), from.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val s = maxOf(c.getLong(0), from); val e = c.getLong(1)
                if (e <= s) continue
                val i = ((s - from) / 86_400_000L).toInt()
                if (i in 0 until days) out[i] += ((e - s) / 60_000L).toInt()
            }
        }
        return out
    }

    /** Remove everything recorded for one app (used when you hide it). */
    fun forgetApp(pkg: String) {
        writableDatabase.delete("events", "pkg = ?", arrayOf(pkg))
        writableDatabase.delete("apps", "pkg = ?", arrayOf(pkg))
    }

    fun wipe() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", null, null)
            db.delete("apps", null, null)
            db.delete("labels", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("VACUUM")
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "events")

    // ---------------------------------------------------------------- reads
    /** The raw format web/analytics.js expects, for events that end after `fromMs`. */
    fun rawJson(fromMs: Long, haveIcons: Set<String> = emptySet()): String {
        val sessions = JSONArray(); val unlocks = JSONArray(); val on = JSONArray(); val off = JSONArray()
        val notifs = JSONArray(); val steps = JSONArray(); val study = JSONArray(); val rules = JSONArray()
        val seen = HashSet<String>()
        // Filter on start_ms so the index does the work: "end_ms >= ?" makes SQLite read every row.
        // The extra day covers a session that began before the window and ended inside it.
        readableDatabase.rawQuery(
            """SELECT type, pkg, start_ms, end_ms, value FROM events
                 WHERE start_ms >= ? AND end_ms >= ? ORDER BY start_ms""",
            arrayOf((fromMs - 86_400_000L).toString(), fromMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0); val pkg = c.getString(1); val s = c.getLong(2); val e = c.getLong(3)
                when (type) {
                    "app" -> { sessions.put(JSONArray().put(pkg).put(s).put(e)); seen += pkg }
                    "unlock" -> unlocks.put(s)
                    "screen_on" -> on.put(s)
                    "screen_off" -> off.put(s)
                    "notif" -> { notifs.put(JSONArray().put(pkg).put(s)); seen += pkg }
                    "study" -> study.put(JSONArray().put(pkg).put(s).put(e))
                    "intent", "lock_kept", "lock_snoozed", "lock_ended", "lock_walked", "reels_blocked" -> {
                        rules.put(JSONArray().put(type).put(pkg).put(s).put(if (c.isNull(4)) 0 else c.getInt(4)))
                        seen += pkg
                    }
                }
            }
        }
        for (p in stepRows(fromMs)) steps.put(JSONArray().put(p.start).put(p.end).put(p.steps))
        val apps = JSONObject(); val icons = JSONObject()
        readableDatabase.rawQuery("SELECT pkg, label, category, icon_b64 FROM apps", null).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(0)
                if (pkg !in seen) continue
                apps.put(pkg, JSONObject().put("n", c.getString(1)).put("cat", c.getString(2)))
                // Icons are the biggest part of the payload and never change, so send each one once.
                if (!c.isNull(3) && pkg !in haveIcons) icons.put(pkg, c.getString(3))
            }
        }
        return JSONObject()
            .put("apps", apps).put("icons", icons).put("sessions", sessions).put("unlocks", unlocks)
            .put("screenOn", on).put("screenOff", off).put("notifs", notifs).put("steps", steps)
            .put("study", study).put("rules", rules)
            .toString()
    }

    /** Minutes of app use per hour for the last 7 days (oldest first) and today's total; used by the widget. */
    fun weekHeatmap(todayStartMs: Long): Pair<List<IntArray>, Int> {
        val from = todayStartMs - 6 * 86_400_000L - 3_600_000L * 26   // DST slack
        val days = List(7) { IntArray(24) }
        val dayStarts = LongArray(8) { i ->
            java.util.Calendar.getInstance().apply { timeInMillis = todayStartMs; add(java.util.Calendar.DAY_OF_YEAR, i - 6) }.timeInMillis
        }
        val minutes = List(7) { DoubleArray(24) }
        readableDatabase.rawQuery(
            """SELECT start_ms, end_ms FROM events
                 WHERE start_ms >= ? AND type = 'app' AND end_ms >= ? ORDER BY start_ms""",
            arrayOf((from - 86_400_000L).toString(), from.toString())
        ).use { c ->
            while (c.moveToNext()) {
                var s = c.getLong(0); val e = c.getLong(1)
                while (s < e) {
                    val d = (0..6).firstOrNull { s >= dayStarts[it] && s < dayStarts[it + 1] }
                    if (d == null) { s = if (s < dayStarts[0]) dayStarts[0] else e; continue }
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = s }
                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
                    val next = minOf(e, cal.timeInMillis)
                    minutes[d][hour] += (next - s) / 60_000.0
                    s = next
                }
            }
        }
        for (d in 0..6) for (h in 0..23) days[d][h] = minutes[d][h].coerceAtMost(60.0).toInt()
        return days to minutes[6].sum().toInt()
    }

    // ---------------------------------------------------------------- export
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private fun isoOf(ms: Long) = iso.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun exportCsv(out: Writer) {
        val labels = HashMap<String, Pair<String, String>>()
        readableDatabase.rawQuery("SELECT pkg, label, category FROM apps", null).use { c ->
            while (c.moveToNext()) labels[c.getString(0)] = c.getString(1) to c.getString(2)
        }
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        out.write("type,start,end,duration_seconds,app,package,category,value\n")
        readableDatabase.rawQuery("SELECT type, pkg, start_ms, end_ms, value FROM events ORDER BY start_ms", null).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1); val s = c.getLong(2); val e = c.getLong(3)
                val (label, cat) = labels[pkg] ?: ("" to "")
                val value = if (c.isNull(4)) "" else c.getDouble(4).toString()
                out.write("${c.getString(0)},${isoOf(s)},${isoOf(e)},${(e - s) / 1000},${q(label)},${q(pkg)},${q(cat)},$value\n")
            }
        }
    }

    fun exportJson(out: Writer) {
        val all = JSONObject(rawJson(0))
        all.remove("icons")
        all.put("exported_at", isoOf(System.currentTimeMillis()))
        all.put("format", "footprint-1: times are Unix milliseconds; see apps for names and categories")
        out.write(all.toString(2))
    }
}
