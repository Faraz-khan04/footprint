package dev.farazkhan.footprint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Storage and reading for "Future You" (the logic is in FutureYou.kt): your goal, your notes to
 * yourself, which parts appear on the lock screens, and the quotes bundled with the dashboard.
 *
 * Everything is in the app's private preferences and database. Nothing leaves the phone.
 */
object Motivation {

    private const val GOAL = "fy_goal"
    private const val NOTES = "fy_notes"
    private const val OPTS = "fy_opts"
    private const val ASK = "fy_ask"
    const val MAX_NOTES = 30
    const val MAX_NOTE_CHARS = 200
    /** A study session at least this long ends with "leave a note for later". */
    const val ASK_AFTER_MIN = 15

    // ---------------------------------------------------------------- options
    /** Which parts appear on the screens shown over apps. All on by default. */
    data class Options(val verdict: Boolean = true, val cost: Boolean = true, val notes: Boolean = true, val quotes: Boolean = true)

    fun options(cfg: Config): Options = runCatching {
        val o = JSONObject(cfg.ruleString(OPTS, "{}"))
        Options(o.optBoolean("verdict", true), o.optBoolean("cost", true), o.optBoolean("notes", true), o.optBoolean("quotes", true))
    }.getOrDefault(Options())

    fun setOptions(cfg: Config, o: JSONObject) {
        val cur = options(cfg)
        val out = JSONObject()
            .put("verdict", o.optBoolean("verdict", cur.verdict))
            .put("cost", o.optBoolean("cost", cur.cost))
            .put("notes", o.optBoolean("notes", cur.notes))
            .put("quotes", o.optBoolean("quotes", cur.quotes))
        cfg.setRuleString(OPTS, out.toString())
    }

    // ---------------------------------------------------------------- goal
    fun goal(cfg: Config): FutureYou.Goal? = runCatching {
        val s = cfg.ruleString(GOAL, "")
        if (s.isEmpty()) return null
        val o = JSONObject(s)
        FutureYou.Goal(
            name = o.getString("name"), subject = o.optString("subject", o.getString("name")),
            unit = o.optString("unit", "chapter"), total = o.getInt("total"), left = o.getInt("left"),
            deadlineDay = o.optLong("deadline", 0L), estimateMin = o.optInt("estimate", 60),
            startMs = o.optLong("start", 0L)
        )
    }.getOrNull()

    private fun clean(s: String, max: Int) = s.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(max)

    /** Save the goal from the dashboard. [o] null or missing name removes it. */
    fun setGoal(cfg: Config, o: JSONObject?, now: Long = System.currentTimeMillis()) {
        val name = o?.optString("name")?.let { clean(it, 40) }.orEmpty()
        if (o == null || name.isEmpty()) { cfg.setRuleString(GOAL, null); return }
        val old = goal(cfg)
        val total = o.optInt("total", old?.total ?: 10).coerceIn(1, 500)
        val left = o.optInt("left", old?.left ?: total).coerceIn(0, total)
        val unit = clean(o.optString("unit", "chapter"), 16).lowercase().ifEmpty { "chapter" }
        val out = JSONObject()
            .put("name", name)
            .put("subject", clean(o.optString("subject", name), 40).ifEmpty { name })
            .put("unit", unit)
            .put("total", total).put("left", left)
            .put("deadline", o.optLong("deadline", 0L).coerceIn(0L, 200_000L))
            .put("estimate", o.optInt("estimate", old?.estimateMin ?: 60).coerceIn(5, 1_000))
            .put("start", if (old != null && old.name == name) old.startMs else now)
        cfg.setRuleString(GOAL, out.toString())
    }

    /** "I finished one" (+1) or "undo" (-1). */
    fun stepGoal(cfg: Config, delta: Int) {
        val g = goal(cfg) ?: return
        val o = JSONObject(cfg.ruleString(GOAL, "{}"))
        o.put("left", (g.left - delta).coerceIn(0, g.total))
        cfg.setRuleString(GOAL, o.toString())
    }

    /** Study minutes on the goal's subject since the goal was set. */
    fun studyMinutes(context: Context, goal: FutureYou.Goal): Int = runCatching {
        EventStore.get(context).studyMinutes(goal.subject, goal.startMs)
    }.getOrDefault(0)

    fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

    // ---------------------------------------------------------------- notes
    fun notes(cfg: Config): List<FutureYou.Note> = runCatching {
        val a = JSONArray(cfg.ruleString(NOTES, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            FutureYou.Note(
                id = o.optLong("id"), text = o.optString("text"),
                whenTag = runCatching { FutureYou.When.valueOf(o.optString("when", "ANY")) }.getOrDefault(FutureYou.When.ANY),
                createdMs = o.optLong("at"), shownMs = o.optLong("shown")
            )
        }
    }.getOrDefault(emptyList())

    private fun saveNotes(cfg: Config, list: List<FutureYou.Note>) {
        val a = JSONArray()
        list.forEach { n ->
            a.put(JSONObject().put("id", n.id).put("text", n.text).put("when", n.whenTag.name)
                .put("at", n.createdMs).put("shown", n.shownMs))
        }
        cfg.setRuleString(NOTES, a.toString())
    }

    /** Adds a note; the oldest goes when there are too many. Returns false for an empty one. */
    fun addNote(cfg: Config, text: String, whenName: String, now: Long = System.currentTimeMillis()): Boolean {
        val t = clean(text, MAX_NOTE_CHARS)
        if (t.isEmpty()) return false
        val w = runCatching { FutureYou.When.valueOf(whenName) }.getOrDefault(FutureYou.When.ANY)
        val list = (notes(cfg) + FutureYou.Note(now, t, w, now, 0L)).takeLast(MAX_NOTES)
        saveNotes(cfg, list)
        cfg.setRuleString(ASK, null)
        return true
    }

    fun deleteNote(cfg: Config, id: Long) = saveNotes(cfg, notes(cfg).filter { it.id != id })

    fun markShown(cfg: Config, id: Long, now: Long = System.currentTimeMillis()) =
        saveNotes(cfg, notes(cfg).map { if (it.id == id) it.copy(shownMs = now) else it })

    // ---------------------------------------------------------------- "leave a note" after studying
    /** Remember that a study session just ended, so the dashboard can offer to write a note. */
    fun askAfterStudy(cfg: Config, subject: String, minutes: Int, now: Long = System.currentTimeMillis()) {
        if (minutes < ASK_AFTER_MIN) return
        cfg.setRuleString(ASK, JSONObject().put("subject", subject).put("minutes", minutes).put("at", now).toString())
    }

    fun dismissAsk(cfg: Config) = cfg.setRuleString(ASK, null)

    /** Offered for a day after the session. */
    private fun ask(cfg: Config, now: Long): JSONObject? = runCatching {
        val o = JSONObject(cfg.ruleString(ASK, ""))
        if (now - o.optLong("at") > 24 * 3_600_000L) null else o
    }.getOrNull()

    fun wipe(cfg: Config) {
        cfg.setRuleString(NOTES, null); cfg.setRuleString(GOAL, null); cfg.setRuleString(ASK, null)
    }

    // ---------------------------------------------------------------- quotes
    @Volatile private var quotesCache: List<FutureYou.Quote>? = null

    /** The quotes in the dashboard's quotes.js, parsed once. */
    fun quotes(context: Context): List<FutureYou.Quote> = quotesCache ?: runCatching {
        val s = context.assets.open("quotes.js").bufferedReader().use { it.readText() }
        val from = s.indexOf('[', s.indexOf("window.FQ").coerceAtLeast(0))
        val a = JSONArray(s.substring(from, s.lastIndexOf(']') + 1))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val tags = o.optJSONArray("tags") ?: JSONArray()
            FutureYou.Quote(o.getString("t"), o.optString("by"), (0 until tags.length()).map { tags.getString(it) })
        }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) quotesCache = it }

    // ---------------------------------------------------------------- for the dashboard
    fun state(context: Context, now: Long = System.currentTimeMillis()): JSONObject {
        val cfg = Config(context)
        val o = options(cfg)
        val out = JSONObject()
            .put("options", JSONObject().put("verdict", o.verdict).put("cost", o.cost).put("notes", o.notes).put("quotes", o.quotes))
            .put("today", today())
        goal(cfg)?.let { g ->
            val min = studyMinutes(context, g)
            val p = FutureYou.pace(g, min)
            out.put("goal", JSONObject(cfg.ruleString(GOAL, "{}"))
                .put("studyMin", min).put("pace", p.minPerUnit).put("learned", p.learned))
        }
        val notes = JSONArray()
        notes(cfg).sortedByDescending { it.createdMs }.forEach { n ->
            notes.put(JSONObject().put("id", n.id).put("text", n.text).put("when", n.whenTag.name).put("at", n.createdMs))
        }
        out.put("notes", notes)
        ask(cfg, now)?.let { out.put("ask", it) }
        return out
    }
}
