package dev.farazkhan.footprint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Where app rules are kept (private SharedPreferences, through Config) and how they change.
 *
 * "Looser rules wait until tomorrow": when that is on, any change that gives you more phone time
 * is parked and applied at midnight. Stricter changes apply at once. Turning the option itself off
 * also waits, otherwise it would be a one-tap way around it.
 */
object RuleStore {

    private const val K_OPENS = "rule_opens"
    private const val K_CAPS = "rule_caps"
    private const val K_INTENT = "rule_intent"
    private const val K_DISTRACT = "rule_distract"
    private const val K_SCHED = "rule_schedules"
    private const val K_BREAK = "rule_break"
    private const val K_WMODE = "rule_weekend_mode"
    private const val K_WDAYS = "rule_weekend_days"
    private const val K_SNOOZES = "rule_snoozes"
    private const val K_DELAY = "rule_delay"
    private const val K_PENDING = "rule_pending"
    private const val K_SNOOZE_COUNT = "rule_snooze_count"
    private const val K_BREAK_UNTIL = "rule_break_until"
    private const val K_INSTEAD = "rule_instead"
    private const val K_WALK = "rule_walk"
    private const val K_REELS = "rule_reels"
    private const val K_REELS_WHEN = "rule_reels_when"
    private const val K_REELS_CONSENT = "rule_reels_consent"

    /** Extra minutes earned by walking on a rule screen. */
    const val WALK_MIN = 10

    val DEFAULT_INSTEAD = listOf("Drink a glass of water", "Stand up and stretch", "Read two pages of your notes")
    private val DISTRACT_CATEGORIES = setOf("Social", "Video", "Games")

    // ---------------------------------------------------------------- calendar helpers
    fun dayOfWeek(now: Long): Int {
        val d = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1           // 1 = Monday .. 7 = Sunday
    }

    fun minuteOfDay(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun nextMidnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = Limits.startOfToday(now); add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    // ---------------------------------------------------------------- json helpers
    private fun obj(cfg: Config, key: String) = runCatching { JSONObject(cfg.ruleString(key, "{}")) }.getOrDefault(JSONObject())
    private fun arr(cfg: Config, key: String) = runCatching { JSONArray(cfg.ruleString(key, "[]")) }.getOrDefault(JSONArray())
    private fun intMap(o: JSONObject): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (k in o.keys()) { val v = o.optInt(k); if (v > 0) out[k] = v }
        return out
    }
    private fun strSet(a: JSONArray): Set<String> = (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }.toSet()
    private fun putInt(cfg: Config, key: String, pkg: String, v: Int) {
        val o = obj(cfg, key)
        if (v > 0) o.put(pkg, v) else o.remove(pkg)
        cfg.setRuleString(key, o.toString())
    }

    private fun schedToJson(s: Rules.Schedule) = JSONObject()
        .put("id", s.id).put("name", s.name).put("from", s.from).put("to", s.to)
        .put("days", JSONArray(s.days.sorted())).put("on", s.on)

    private fun schedFromJson(o: JSONObject): Rules.Schedule? = runCatching {
        val d = o.optJSONArray("days") ?: JSONArray()
        Rules.Schedule(
            o.getString("id"), o.optString("name", "Blocked time"),
            o.getInt("from").coerceIn(0, 1439), o.getInt("to").coerceIn(0, 1439),
            (0 until d.length()).map { d.getInt(it) }.filter { it in 1..7 }.toSet(),
            o.optBoolean("on", true)
        )
    }.getOrNull()

    // ---------------------------------------------------------------- reading
    fun load(context: Context, cfg: Config = Config(context), now: Long = System.currentTimeMillis()): Rules.RuleSet {
        applyDue(cfg, now)
        val sched = arr(cfg, K_SCHED)
        return Rules.RuleSet(
            enabled = cfg.limitsEnabled,
            limits = Limits.all(cfg),
            opens = intMap(obj(cfg, K_OPENS)),
            caps = intMap(obj(cfg, K_CAPS)),
            intent = strSet(arr(cfg, K_INTENT)),
            distract = distract(context, cfg),
            schedules = (0 until sched.length()).mapNotNull { sched.optJSONObject(it)?.let(::schedFromJson) },
            breakMin = cfg.ruleString(K_BREAK, "10").toIntOrNull() ?: 10,
            weekendMode = cfg.ruleString(K_WMODE, "0").toIntOrNull() ?: 0,
            weekendDays = cfg.ruleString(K_WDAYS, "0").toIntOrNull() ?: 0,
            snoozesPerDay = cfg.ruleString(K_SNOOZES, "-1").toIntOrNull() ?: -1
        )
    }

    /**
     * Apps shown the study screen and blocked-time screen. Until you choose, it's the social, video
     * and game apps you've used in the last two weeks.
     */
    fun distract(context: Context, cfg: Config = Config(context)): Set<String> {
        if (cfg.hasRule(K_DISTRACT)) return strSet(arr(cfg, K_DISTRACT))
        return runCatching {
            EventStore.get(context).recentAppsInCategories(
                System.currentTimeMillis() - 14L * 86_400_000L, DISTRACT_CATEGORIES)
        }.getOrDefault(emptySet()) - cfg.hiddenApps
    }

    fun delayOn(cfg: Config): Boolean = cfg.ruleString(K_DELAY, "0") == "1"

    fun doInstead(cfg: Config): List<String> {
        if (!cfg.hasRule(K_INSTEAD)) return DEFAULT_INSTEAD
        val a = arr(cfg, K_INSTEAD)
        return (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } }
    }

    fun setDoInstead(cfg: Config, list: List<String>) {
        cfg.setRuleString(K_INSTEAD, JSONArray(list.map { it.trim() }.filter { it.isNotEmpty() }.take(12)).toString())
    }

    // ---------------------------------------------------------------- pending (looser) changes
    private fun pending(cfg: Config) = obj(cfg, K_PENDING)

    /** The value waiting for [key], or null when nothing is waiting. */
    fun pendingValue(cfg: Config, key: String): Any? = pending(cfg).optJSONObject(key)?.opt("v")

    private fun park(cfg: Config, key: String, value: Any, now: Long) {
        val p = pending(cfg)
        p.put(key, JSONObject().put("v", value).put("at", nextMidnight(now)))
        cfg.setRuleString(K_PENDING, p.toString())
    }

    private fun unpark(cfg: Config, key: String) {
        val p = pending(cfg)
        if (!p.has(key)) return
        p.remove(key)
        cfg.setRuleString(K_PENDING, p.toString())
    }

    /** Apply every parked change whose day has come. */
    fun applyDue(cfg: Config, now: Long) {
        val p = pending(cfg)
        if (p.length() == 0) return
        val due = p.keys().asSequence().toList().filter { (p.optJSONObject(it)?.optLong("at", Long.MAX_VALUE) ?: 0L) <= now }
        if (due.isEmpty()) return
        for (key in due) {
            val v = p.getJSONObject(key).opt("v")
            p.remove(key)
            cfg.setRuleString(K_PENDING, p.toString())      // written first, so apply() can't loop back here
            apply(cfg, key, v)
        }
    }

    /** Write one change for real. */
    private fun apply(cfg: Config, key: String, v: Any?) {
        val i = (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull() ?: 0
        val arg = key.substringAfter(':', "")
        when (key.substringBefore(':')) {
            "limit" -> Limits.set(cfg, arg, i)
            "opens" -> putInt(cfg, K_OPENS, arg, i)
            "cap" -> putInt(cfg, K_CAPS, arg, i)
            "enabled" -> cfg.limitsEnabled = v == true || v?.toString() == "true"
            "delay" -> cfg.setRuleString(K_DELAY, if (v == true || v?.toString() == "true") "1" else "0")
            "snoozes" -> cfg.setRuleString(K_SNOOZES, i.toString())
            "break" -> cfg.setRuleString(K_BREAK, i.toString())
            "wmode" -> cfg.setRuleString(K_WMODE, i.toString())
            "wdays" -> cfg.setRuleString(K_WDAYS, i.toString())
            "distract" -> setDistractNow(cfg, arg, false)
            "walk" -> cfg.setRuleString(K_WALK, i.toString())
            "reels" -> {
                val o = obj(cfg, K_REELS)
                o.put(arg, v == true || v?.toString() == "true")
                cfg.setRuleString(K_REELS, o.toString())
            }
            "reelswhen" -> cfg.setRuleString(K_REELS_WHEN, i.toString())
            "sched" -> {
                val list = arr(cfg, K_SCHED)
                val out = JSONArray()
                val replacement = (v as? String)?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                for (j in 0 until list.length()) {
                    val o = list.optJSONObject(j) ?: continue
                    if (o.optString("id") == arg) { if (replacement != null) out.put(replacement) }
                    else out.put(o)
                }
                cfg.setRuleString(K_SCHED, out.toString())
            }
        }
    }

    /** Outcome of a change: done now, or parked until midnight. */
    enum class Change { NOW, TOMORROW, SAME }

    private fun change(cfg: Config, key: String, value: Any, looser: Boolean, now: Long): Change {
        if (looser && delayOn(cfg)) { park(cfg, key, value, now); return Change.TOMORROW }
        unpark(cfg, key)
        apply(cfg, key, value)
        return Change.NOW
    }

    private fun intChange(cfg: Config, key: String, from: Int, to: Int, unlimited: Int, now: Long): Change {
        if (from == to) { unpark(cfg, key); return Change.SAME }
        return change(cfg, key, to, Rules.isLooser(from, to, unlimited), now)
    }

    // ---------------------------------------------------------------- per-app changes
    fun setLimit(context: Context, pkg: String, minutes: Int, now: Long = System.currentTimeMillis()): Change {
        val cfg = Config(context)
        val r = intChange(cfg, "limit:$pkg", Limits.minutesFor(cfg, pkg), minutes, 0, now)
        Limits.clearSnooze(cfg, pkg); Limits.resetRunning()
        return r
    }

    fun setOpens(context: Context, pkg: String, n: Int, now: Long = System.currentTimeMillis()): Change {
        val cfg = Config(context)
        return intChange(cfg, "opens:$pkg", intMap(obj(cfg, K_OPENS))[pkg] ?: 0, n, 0, now)
    }

    fun setCap(context: Context, pkg: String, minutes: Int, now: Long = System.currentTimeMillis()): Change {
        val cfg = Config(context)
        return intChange(cfg, "cap:$pkg", intMap(obj(cfg, K_CAPS))[pkg] ?: 0, minutes, 0, now)
    }

    /** Asking "why?" is a question, not a limit, so it always changes at once. */
    fun setIntent(cfg: Config, pkg: String, on: Boolean) {
        val s = strSet(arr(cfg, K_INTENT)).toMutableSet()
        if (on) s += pkg else s -= pkg
        cfg.setRuleString(K_INTENT, JSONArray(s.sorted()).toString())
    }

    private fun setDistractNow(cfg: Config, pkg: String, on: Boolean) {
        val s = strSet(arr(cfg, K_DISTRACT)).toMutableSet()
        if (on) s += pkg else s -= pkg
        cfg.setRuleString(K_DISTRACT, JSONArray(s.sorted()).toString())
    }

    fun setDistract(context: Context, pkg: String, on: Boolean, now: Long = System.currentTimeMillis()): Change {
        val cfg = Config(context)
        // The first edit turns the suggested list into your own list.
        if (!cfg.hasRule(K_DISTRACT)) cfg.setRuleString(K_DISTRACT, JSONArray(distract(context, cfg).sorted()).toString())
        if (on) { unpark(cfg, "distract:$pkg"); setDistractNow(cfg, pkg, true); return Change.NOW }
        if (pkg !in strSet(arr(cfg, K_DISTRACT))) return Change.SAME
        return change(cfg, "distract:$pkg", false, looser = true, now = now)
    }

    // ---------------------------------------------------------------- global changes
    fun setEnabled(cfg: Config, on: Boolean, now: Long = System.currentTimeMillis()): Change {
        if (on == cfg.limitsEnabled) { unpark(cfg, "enabled"); return Change.SAME }
        return change(cfg, "enabled", on, looser = !on, now = now)
    }

    fun setDelay(cfg: Config, on: Boolean, now: Long = System.currentTimeMillis()): Change {
        if (on == delayOn(cfg)) { unpark(cfg, "delay"); return Change.SAME }
        return change(cfg, "delay", on, looser = !on, now = now)
    }

    fun setSnoozes(cfg: Config, n: Int, now: Long = System.currentTimeMillis()): Change =
        intChange(cfg, "snoozes", cfg.ruleString(K_SNOOZES, "-1").toIntOrNull() ?: -1, n, -1, now)

    fun setBreak(cfg: Config, min: Int, now: Long = System.currentTimeMillis()): Change {
        val from = cfg.ruleString(K_BREAK, "10").toIntOrNull() ?: 10
        if (from == min) { unpark(cfg, "break"); return Change.SAME }
        return change(cfg, "break", min, looser = min < from, now = now)      // a shorter break is looser
    }

    fun setWeekendMode(cfg: Config, mode: Int, now: Long = System.currentTimeMillis()): Change {
        val from = cfg.ruleString(K_WMODE, "0").toIntOrNull() ?: 0
        if (from == mode) { unpark(cfg, "wmode"); return Change.SAME }
        return change(cfg, "wmode", mode, looser = mode > from, now = now)
    }

    fun setWeekendDays(cfg: Config, which: Int, now: Long = System.currentTimeMillis()): Change {
        val from = cfg.ruleString(K_WDAYS, "0").toIntOrNull() ?: 0
        if (from == which) { unpark(cfg, "wdays"); return Change.SAME }
        return change(cfg, "wdays", which, looser = which < from, now = now)  // two weekend days are looser than one
    }

    fun rawBreak(cfg: Config) = cfg.ruleString(K_BREAK, "10").toIntOrNull() ?: 10
    fun rawWeekendMode(cfg: Config) = cfg.ruleString(K_WMODE, "0").toIntOrNull() ?: 0
    fun rawWeekendDays(cfg: Config) = cfg.ruleString(K_WDAYS, "0").toIntOrNull() ?: 0
    fun rawSnoozes(cfg: Config) = cfg.ruleString(K_SNOOZES, "-1").toIntOrNull() ?: -1
    fun rawOpens(cfg: Config, pkg: String) = intMap(obj(cfg, K_OPENS))[pkg] ?: 0
    fun rawCap(cfg: Config, pkg: String) = intMap(obj(cfg, K_CAPS))[pkg] ?: 0
    fun rawIntent(cfg: Config, pkg: String) = pkg in strSet(arr(cfg, K_INTENT))

    // ---------------------------------------------------------------- walking for extra time
    /** Steps to walk for [WALK_MIN] more minutes; 0 = off. */
    fun walkSteps(cfg: Config): Int = cfg.ruleString(K_WALK, "0").toIntOrNull() ?: 0

    fun setWalk(cfg: Config, steps: Int, now: Long = System.currentTimeMillis()): Change {
        val from = walkSteps(cfg)
        if (from == steps) { unpark(cfg, "walk"); return Change.SAME }
        // Turning it on, or needing fewer steps, is a new way to more time.
        val looser = (from == 0 && steps > 0) || (steps in 1 until from)
        return change(cfg, "walk", steps, looser, now)
    }

    // ---------------------------------------------------------------- reels and shorts ("full" edition)
    /** Which feeds are blocked: keys from ReelsMatch.TARGETS. YouTube and Instagram start on. */
    fun reelsEnabled(cfg: Config): Set<String> {
        val o = obj(cfg, K_REELS)
        return ReelsMatch.TARGETS.map { it.key }.filter { o.optBoolean(it, it != "snap") }.toSet()
    }

    fun setReels(cfg: Config, key: String, on: Boolean, now: Long = System.currentTimeMillis()): Change {
        if (on == (key in reelsEnabled(cfg))) { unpark(cfg, "reels:$key"); return Change.SAME }
        return change(cfg, "reels:$key", on, looser = !on, now = now)
    }

    /** 0 = always, 1 = only during blocked times and study sessions. */
    fun reelsWhen(cfg: Config): Int = cfg.ruleString(K_REELS_WHEN, "0").toIntOrNull() ?: 0

    fun setReelsWhen(cfg: Config, mode: Int, now: Long = System.currentTimeMillis()): Change {
        val from = reelsWhen(cfg)
        if (from == mode) { unpark(cfg, "reelswhen"); return Change.SAME }
        return change(cfg, "reelswhen", mode, looser = mode > from, now = now)
    }

    /** The user read the in-app disclosure for the Accessibility service and agreed. */
    fun reelsConsent(cfg: Config): Boolean = cfg.ruleString(K_REELS_CONSENT, "0") == "1"
    fun setReelsConsent(cfg: Config, ok: Boolean) = cfg.setRuleString(K_REELS_CONSENT, if (ok) "1" else "0")

    // ---------------------------------------------------------------- schedules
    fun schedules(cfg: Config): List<Rules.Schedule> {
        val a = arr(cfg, K_SCHED)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::schedFromJson) }
    }

    /** A new blocked time applies at once: it can only make things stricter. */
    fun addSchedule(cfg: Config, s: Rules.Schedule) {
        val a = arr(cfg, K_SCHED)
        if (a.length() >= 6) return
        a.put(schedToJson(s))
        cfg.setRuleString(K_SCHED, a.toString())
    }

    /**
     * Editing or deleting an existing blocked time can free up phone time, so with the delay on it
     * waits until tomorrow. Turning one on is always immediate.
     */
    fun updateSchedule(cfg: Config, s: Rules.Schedule, now: Long = System.currentTimeMillis()): Change {
        val old = schedules(cfg).firstOrNull { it.id == s.id } ?: return Change.SAME
        if (old == s) { unpark(cfg, "sched:${s.id}"); return Change.SAME }
        val onlyTurnedOn = old.copy(on = true) == s && !old.on
        return change(cfg, "sched:${s.id}", schedToJson(s).toString(), looser = !onlyTurnedOn, now = now)
    }

    /** A name never gives anyone more time, so it changes at once, parked change or not. */
    fun renameSchedule(cfg: Config, id: String, name: String) {
        val a = arr(cfg, K_SCHED)
        for (j in 0 until a.length()) a.optJSONObject(j)?.let { if (it.optString("id") == id) it.put("name", name.take(30)) }
        cfg.setRuleString(K_SCHED, a.toString())
        val p = pending(cfg)
        p.optJSONObject("sched:$id")?.let { w ->
            val v = w.optString("v")
            if (v.isNotEmpty()) runCatching { w.put("v", JSONObject(v).put("name", name.take(30)).toString()) }
            cfg.setRuleString(K_PENDING, p.toString())
        }
    }

    fun deleteSchedule(cfg: Config, id: String, now: Long = System.currentTimeMillis()): Change =
        change(cfg, "sched:$id", "", looser = true, now = now)

    /** What a schedule will look like tomorrow, if a change is parked: null = being deleted. */
    fun pendingSchedule(cfg: Config, id: String): Pair<Boolean, Rules.Schedule?> {
        val v = pendingValue(cfg, "sched:$id") ?: return false to null
        val s = (v as? String)?.takeIf { it.isNotEmpty() }?.let { runCatching { schedFromJson(JSONObject(it)) }.getOrNull() }
        return true to s
    }

    // ---------------------------------------------------------------- extra time and breaks
    private fun todayCounts(cfg: Config, now: Long): JSONObject {
        val o = obj(cfg, K_SNOOZE_COUNT)
        val day = Limits.startOfToday(now)
        return if (o.optLong("day") == day) o else JSONObject().put("day", day)
    }

    fun snoozesUsed(cfg: Config, pkg: String, now: Long): Int = todayCounts(cfg, now).optInt(pkg, 0)

    /** -1 = no cap. */
    fun snoozesLeft(cfg: Config, pkg: String, now: Long): Int {
        val cap = rawSnoozes(cfg)
        return if (cap < 0) -1 else (cap - snoozesUsed(cfg, pkg, now)).coerceAtLeast(0)
    }

    fun noteSnooze(cfg: Config, pkg: String, now: Long) {
        val o = todayCounts(cfg, now)
        o.put(pkg, o.optInt(pkg, 0) + 1)
        cfg.setRuleString(K_SNOOZE_COUNT, o.toString())
    }

    fun breakUntil(cfg: Config, pkg: String): Long = obj(cfg, K_BREAK_UNTIL).optLong(pkg, 0L)

    fun startBreak(cfg: Config, pkg: String, until: Long) {
        val o = obj(cfg, K_BREAK_UNTIL)
        // drop finished breaks so this never grows
        val now = System.currentTimeMillis()
        for (k in o.keys().asSequence().toList()) if (o.optLong(k) < now) o.remove(k)
        o.put(pkg, until)
        cfg.setRuleString(K_BREAK_UNTIL, o.toString())
    }

    /** "Delete everything" keeps your rules but forgets today's counters. */
    fun resetCounters(cfg: Config) {
        cfg.setRuleString(K_SNOOZE_COUNT, null)
        cfg.setRuleString(K_BREAK_UNTIL, null)
    }
}
