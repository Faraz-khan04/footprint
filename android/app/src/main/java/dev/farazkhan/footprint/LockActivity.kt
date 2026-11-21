package dev.farazkhan.footprint

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The screen shown over an app: a limit, too many opens, a break, a blocked time, a study session,
 * or the "why are you opening this?" question.
 *
 * It asks rather than scolds, and outside a study session the choice stays with the user: leave, or
 * take a few more minutes (unless they capped that themselves in Settings). Every answer is recorded,
 * so the dashboard can show how often each rule holds.
 */
class LockActivity : Activity() {

    /** What the screen is for. Stored as the value of lock_* events, so the numbers must not change. */
    object Mode {
        const val LIMIT = 0
        const val OPENS = 1
        const val BREAK = 2
        const val SCHEDULE = 3
        const val FOCUS = 4
        const val INTENT = 5
    }

    /** Answers to "why?", stored as the value of intent events. */
    object Reason {
        const val CLOSED = 0
        const val REPLY = 1
        const val SPECIFIC = 2
        const val BORED = 3
        const val HABIT = 4
    }

    companion object {
        private const val EX_MODE = "mode"
        private const val EX_PKG = "pkg"
        private const val EX_USED = "used"
        private const val EX_LIMIT = "limit"
        private const val EX_VISITS = "visits"
        private const val EX_MAX_VISITS = "max_visits"
        private const val EX_VISIT_START = "visit_start"
        private const val EX_VISIT_MIN = "visit_min"
        private const val EX_UNTIL = "until"
        private const val EX_LABEL = "label"

        fun intent(
            context: Context, mode: Int, pkg: String,
            usedMs: Long = 0L, limitMin: Int = 0, visits: Int = 0, maxVisits: Int = 0,
            visitStart: Long = 0L, visitMin: Int = 0, until: Long = 0L, label: String = ""
        ): Intent = Intent(context, LockActivity::class.java)
            .putExtra(EX_MODE, mode).putExtra(EX_PKG, pkg)
            .putExtra(EX_USED, usedMs).putExtra(EX_LIMIT, limitMin)
            .putExtra(EX_VISITS, visits).putExtra(EX_MAX_VISITS, maxVisits)
            .putExtra(EX_VISIT_START, visitStart).putExtra(EX_VISIT_MIN, visitMin)
            .putExtra(EX_UNTIL, until).putExtra(EX_LABEL, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private val ink = Color.parseColor("#151515")
    private val paper = Color.parseColor("#EFEBE2")
    private val rust = Color.parseColor("#C7503A")
    private val mute = Color.parseColor("#8A857C")
    private val line = Color.parseColor("#34322F")

    private var pkg = ""
    private var mode = Mode.LIMIT
    private var answered = false
    private var confirmEnd = false
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false)   // minSdk is 26
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val x = intent
        pkg = x.getStringExtra(EX_PKG).orEmpty()
        mode = x.getIntExtra(EX_MODE, Mode.LIMIT)
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val cfg = Config(this)
        val now = System.currentTimeMillis()
        val until = x.getLongExtra(EX_UNTIL, 0L)
        val label = x.getStringExtra(EX_LABEL).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ink)
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        col = root
        // Future You adds lines, so the page scrolls on small screens instead of cutting buttons off.
        val scroller = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ink)
            addView(root, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val usedMin = (x.getLongExtra(EX_USED, 0L) / 60_000L).toInt()
        val moment = when (mode) {
            Mode.INTENT -> FutureYou.Moment.INTENT
            Mode.SCHEDULE, Mode.FOCUS -> FutureYou.Moment.BLOCKED
            else -> FutureYou.Moment.LIMIT
        }
        fun kicker(s: String) = root.addView(text(s, 12f, rust, mono = true))
        fun title(s: String) = root.addView(text(s, 34f, paper, bold = true).apply { setPadding(0, dp(10), 0, dp(6)) })
        fun line1(s: String) = root.addView(text(s, 16f, paper))
        fun line2(s: String) = root.addView(text(s, 15f, mute).apply { setPadding(0, dp(14), 0, dp(4)) })

        when (mode) {
            Mode.INTENT -> {
                kicker("QUICK QUESTION")
                title(appName)
                futureYou(moment, appName, usedMin)
                line1("Why are you opening it?")
                root.addView(space(18))
                listOf(
                    "Reply to someone" to Reason.REPLY,
                    "To find something" to Reason.SPECIFIC,
                    "I'm bored" to Reason.BORED,
                    "Just out of habit" to Reason.HABIT
                ).forEach { (s, code) -> root.addView(button(s, filled = false) { answer(code) }) }
                root.addView(button("Never mind, close it", filled = true) { closeIntent() })
            }
            Mode.FOCUS -> {
                kicker("STUDY TIME")
                title(label.ifEmpty { "Study" })
                line1(if (until > 0L) "$appName opens again at ${clock(until)}." else "$appName opens again when you stop the timer.")
                futureYou(moment, appName, usedMin)
                insteadLine(cfg)
                root.addView(button("Back to studying", filled = true) { record("lock_kept"); goHome() })
                root.addView(button("Stop studying", filled = false) {
                    if (!confirmEnd) {
                        confirmEnd = true
                        (it as Button).text = "Tap again to stop"
                        return@button
                    }
                    record("lock_ended")
                    Focus.stop(this)
                    finish()
                })
            }
            else -> {
                when (mode) {
                    Mode.OPENS -> {
                        kicker("TOO MANY OPENS")
                        title(appName)
                        line1("You've opened it ${x.getIntExtra(EX_VISITS, 0)} times today. Your limit is ${x.getIntExtra(EX_MAX_VISITS, 0)}.")
                    }
                    Mode.BREAK -> {
                        kicker("TIME FOR A BREAK")
                        title(appName)
                        val m = x.getIntExtra(EX_VISIT_MIN, 0)
                        line1(if (m > 0) "You've used it for ${fmt(m)} straight. It opens again at ${clock(until)}." else "It opens again at ${clock(until)}.")
                    }
                    Mode.SCHEDULE -> {
                        kicker("BLOCKED TIME")
                        title(appName)
                        line1("${label.ifEmpty { "Blocked time" }}. It opens again at ${clock(until)}.")
                    }
                    else -> {
                        kicker("TIME'S UP")
                        title(appName)
                        line1("You've used it for ${fmt(usedMin)} today. Your limit is ${fmt(x.getIntExtra(EX_LIMIT, 0))}.")
                        line2("Was it worth it?")
                    }
                }
                futureYou(moment, appName, usedMin)
                insteadLine(cfg)
                root.addView(button("Close it", filled = true) { record("lock_kept"); goHome() })
                val left = RuleStore.snoozesLeft(cfg, pkg, now)
                if (left != 0) {
                    val more = "${Limits.SNOOZE_MIN} more minutes" + if (left > 0) "  ($left left today)" else ""
                    root.addView(button(more, filled = false) {
                        record("lock_snoozed")
                        val t = System.currentTimeMillis()
                        Limits.snooze(cfg, pkg, t)
                        RuleStore.noteSnooze(cfg, pkg, t)
                        finish()
                    })
                } else {
                    root.addView(text("No more extra time for this app today.", 13f, mute).apply {
                        setPadding(0, dp(16), 0, 0)
                    })
                }
                // Walking earns time even when the extra-time cap is used up: that's the point of it.
                val steps = RuleStore.walkSteps(cfg)
                if (steps > 0 && StepCollector.hasSensor(this) && StepCollector.hasPermission(this)) {
                    root.addView(button("Walk $steps steps for ${RuleStore.WALK_MIN} more minutes", filled = false) {
                        startWalk(steps)
                    })
                }
            }
        }
        quoteLine(moment)
        setContentView(scroller)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { back() }
        }
    }

    // ---------------------------------------------------------------- walk for extra time
    private var sensors: android.hardware.SensorManager? = null
    private var stepListener: android.hardware.SensorEventListener? = null

    /** Swap the page for a step counter; reaching [goal] unlocks the app for a few minutes. */
    private fun startWalk(goal: Int) {
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val counter = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
        val detector = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR)
        if (counter == null && detector == null) return
        col.removeAllViews()
        col.addView(text("WALK FOR IT", 12f, rust, mono = true))
        val count = text("0", 72f, paper, bold = true).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(count)
        col.addView(text("of $goal steps", 16f, mute, mono = true))
        col.addView(text("Keep this screen open and start walking. You get ${RuleStore.WALK_MIN} more minutes when you reach it.", 15f, paper).apply {
            setPadding(0, dp(18), 0, dp(10))
        })
        col.addView(button("Never mind, close it", filled = true) { stopWalk(); record("lock_kept"); goHome() })

        var base = -1f; var fromCounter = 0; var fromDetector = 0; var done = false
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                if (done) return
                if (e.sensor.type == android.hardware.Sensor.TYPE_STEP_COUNTER) {
                    val total = e.values[0]
                    if (base < 0f) base = total
                    fromCounter = (total - base).toInt()
                } else fromDetector++
                // The detector reacts at once, the counter is exact but can lag: show whichever is ahead.
                val walked = maxOf(fromCounter, fromDetector)
                count.text = walked.coerceAtMost(goal).toString()
                if (walked >= goal) {
                    done = true
                    stopWalk()
                    val t = System.currentTimeMillis()
                    Limits.snooze(Config(this@LockActivity), pkg, t, RuleStore.WALK_MIN)
                    record("lock_walked")
                    android.widget.Toast.makeText(this@LockActivity,
                        "$goal steps. ${RuleStore.WALK_MIN} minutes are yours.", android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        counter?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        detector?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        sensors = sm; stepListener = listener
    }

    private fun stopWalk() {
        stepListener?.let { sensors?.unregisterListener(it) }
        stepListener = null
    }

    override fun onDestroy() { stopWalk(); super.onDestroy() }

    /** The back button should not be a way past the screen; it behaves like "Close it". */
    @Deprecated("Kept for API 26-32; Android 13+ uses the callback registered in onCreate.")
    override fun onBackPressed() = back()

    private fun back() {
        if (mode == Mode.INTENT) closeIntent() else { record("lock_kept"); goHome() }
    }

    private fun answer(reason: Int) {
        if (answered) return
        answered = true
        record("intent", reason.toDouble())
        LockService.markHandled(pkg, intent.getLongExtra(EX_VISIT_START, 0L))
        finish()                                        // the app underneath carries on
    }

    private fun closeIntent() {
        if (answered) return
        answered = true
        record("intent", Reason.CLOSED.toDouble())
        goHome()
    }

    // ---------------------------------------------------------------- Future You
    private val hand by lazy { runCatching { resources.getFont(R.font.caveat_bold) }.getOrNull() }

    /**
     * Your own verdict, a note from past you, and what today's time on this app is worth in your
     * goal. Each part appears only when there is something true to say, and each can be switched
     * off on the dashboard's Future You section.
     */
    private fun futureYou(moment: FutureYou.Moment, appName: String, usedMin: Int) {
        val cfg = Config(this)
        val opts = Motivation.options(cfg)
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        if (opts.verdict && moment != FutureYou.Moment.BLOCKED) runCatching {
            val zone = java.util.TimeZone.getDefault()
            val answers = EventStore.get(this).answersFor(pkg).map { (start, regret) ->
                val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = start }
                FutureYou.Answer(start, cal.get(java.util.Calendar.HOUR_OF_DAY), regret)
            }
            FutureYou.verdict(answers, hour)?.let { v ->
                col.addView(text(FutureYou.verdictLine(v, appName), 16f, rust).apply { setPadding(0, dp(6), 0, dp(10)) })
            }
        }

        if (opts.notes) runCatching {
            FutureYou.pickNote(Motivation.notes(cfg), moment, hour, now)?.let { n ->
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(16))
                    background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(2), rust) }
                }
                box.addView(text(FutureYou.ago(n.createdMs, now), 11f, rust, mono = true))
                box.addView(text(n.text, 26f, paper).apply {
                    hand?.let { typeface = it }
                    setLineSpacing(0f, 0.95f)
                    setPadding(0, dp(6), 0, 0)
                })
                box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(16); bottomMargin = dp(8) }
                col.addView(box)
                Motivation.markShown(cfg, n.id, now)
            }
        }

        if (opts.cost) runCatching {
            val goal = Motivation.goal(cfg) ?: return@runCatching
            if (goal.left <= 0) return@runCatching
            val pace = FutureYou.pace(goal, Motivation.studyMinutes(this, goal))
            val lines = ArrayList<String>()
            FutureYou.costLine(usedMin, goal, pace)?.let { lines += "${FutureYou.hm(usedMin)} on $appName today. $it" }
            if (moment == FutureYou.Moment.BLOCKED || lines.isEmpty()) {
                FutureYou.deadlineLine(goal, pace, Motivation.today())?.let { lines += "${goal.name}: $it" }
            }
            if (lines.isNotEmpty()) col.addView(text(lines.joinToString("\n"), 15f, paper).apply { setPadding(0, dp(10), 0, dp(4)) })
        }
    }

    /** A quote for the moment, small, under the buttons. */
    private fun quoteLine(moment: FutureYou.Moment) {
        if (!Motivation.options(Config(this)).quotes) return
        runCatching {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val q = FutureYou.pickQuote(Motivation.quotes(this), FutureYou.tagsFor(moment, hour),
                System.currentTimeMillis() / 1000L) ?: return
            col.addView(text("\u201C${q.text}\u201D", 20f, mute).apply {
                hand?.let { typeface = it }
                setPadding(dp(8), dp(28), dp(8), 0)
            })
            if (q.by.isNotEmpty()) col.addView(text("\u2014 ${q.by.uppercase()}", 10f, mute, mono = true).apply { setPadding(0, dp(6), 0, 0) })
        }
    }

    /** One of the user's "do this instead" ideas, picked at random. */
    private fun insteadLine(cfg: Config) {
        val list = RuleStore.doInstead(cfg)
        if (list.isEmpty()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(1), line) }
        }
        box.addView(text("TRY THIS INSTEAD", 11f, mute, mono = true))
        box.addView(text(list.random(), 17f, paper).apply { setPadding(0, dp(4), 0, 0) })
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(22); bottomMargin = dp(10) }
        col.addView(box)
    }

    private fun goHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun record(type: String, value: Double = mode.toDouble()) {
        runCatching { EventStore.get(this).point(type, System.currentTimeMillis(), pkg, value) }
    }

    // ---------------------------------------------------------------- small view helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(min: Int): String {
        val h = min / 60; val m = min % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun clock(ms: Long): String = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(ms))

    private fun space(h: Int) = android.widget.Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (mono) { typeface = Typeface.MONOSPACE; letterSpacing = 0.12f }
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun button(s: String, filled: Boolean, onTap: (android.view.View) -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 16f
        setTextColor(if (filled) ink else paper)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (filled) rust else Color.TRANSPARENT)
            if (!filled) setStroke(dp(1), line)
        }
        setPadding(dp(20), dp(14), dp(20), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        setOnClickListener { onTap(it) }
    }
}
