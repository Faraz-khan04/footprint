package dev.farazkhan.footprint

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The building blocks of the Settings screens, in the dashboard's style: dark grid, bone cards with
 * a hard rust shadow, Montserrat Black headings, Courier Prime labels, and one segmented control
 * instead of rows of separate boxes.
 */
class SettingsUi(private val a: Activity) {

    val ink = Color.parseColor("#151515")
    val paper = Color.parseColor("#EFEBE2")
    val mute = Color.parseColor("#8D897F")
    val rust = Color.parseColor("#C7503A")
    val line = Color.parseColor("#34322F")
    val card = Color.parseColor("#1D1C1A")
    val raised = Color.parseColor("#262420")
    val green = Color.parseColor("#3FB27F")

    val heavy: Typeface = font(R.font.montserrat_black, Typeface.DEFAULT_BOLD)
    val mono: Typeface = font(R.font.courier_prime, Typeface.MONOSPACE)
    val monoBold: Typeface = font(R.font.courier_prime_bold, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    // The dashboard's body text is Courier Prime, so Settings uses it too.
    val sans: Typeface = mono
    val sansMedium: Typeface = monoBold

    private fun font(id: Int, fallback: Typeface): Typeface =
        runCatching { a.resources.getFont(id) }.getOrNull() ?: fallback

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    fun lp(top: Int = 0, bottom: Int = 0, w: Int = match) =
        LinearLayout.LayoutParams(w, wrap).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun weight(w: Float = 1f, left: Int = 0) =
        LinearLayout.LayoutParams(0, wrap, w).apply { leftMargin = dp(left) }

    fun column(pad: Int = 0) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun row() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun rect(fill: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 6, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(strokeW), stroke)
    }

    /** A card with a hard offset shadow, like the stickers and buttons on the dashboard. */
    fun shadowed(fill: Int, shadow: Int, stroke: Int = Color.TRANSPARENT, offset: Int = 4): Drawable {
        val d = LayerDrawable(arrayOf(rect(shadow, radius = 6), rect(fill, stroke, 6)))
        d.setLayerInset(0, dp(offset), dp(offset), 0, 0)
        d.setLayerInset(1, 0, 0, dp(offset), dp(offset))
        return d
    }

    // ---------------------------------------------------------------- text
    fun text(s: CharSequence, size: Float, color: Int = paper, face: Typeface = sans, spacing: Float = 0f) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color); typeface = face
        letterSpacing = spacing; setLineSpacing(0f, 1.2f)
    }

    fun title(s: String) = text(s, 16f, paper, monoBold)
    fun note(s: String, color: Int = mute) = text(s, 13.5f, color, mono).apply { setPadding(0, dp(4), 0, 0) }

    /** Small spaced caps above a headline, e.g. "EVERY APP, RANKED". */
    fun kicker(s: String, color: Int = mute) = text(s.uppercase(), 13f, color, monoBold, 0.16f)

    /** Stroke-only lettering, like ".outline" on the dashboard. */
    fun outlined(s: String, size: Float, color: Int = paper) = headline(s, size, color).apply {
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat() * 1.6f
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        // the stroke sits half outside each letter; keep it from being clipped at the edges
        setPadding(dp(2), dp(2), dp(4), dp(4))
    }

    /** The dashboard's section title: a solid line, then an outlined one ("Where it / went."). */
    fun bigTitle(solid: String, outline: String, size: Float = 50f) = column().apply {
        addView(headline(solid, size, paper))
        addView(outlined(outline, size), lp(top = -4))
    }

    /** The dashboard's background: dark, with a faint 28dp grid. */
    fun gridBackground(): Drawable = object : Drawable() {
        private val p = android.graphics.Paint().apply { color = Color.argb(10, 239, 235, 226); strokeWidth = 1f }
        private val step = dp(28).toFloat()
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            c.drawColor(ink)
            var x = b.left.toFloat(); while (x < b.right) { c.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), p); x += step }
            var y = b.top.toFloat(); while (y < b.bottom) { c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, p); y += step }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Required by Drawable") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /** Small caps label above a group of settings. */
    fun groupLabel(s: String) = text(s.uppercase(), 11f, mute, monoBold, 0.14f).apply {
        setPadding(dp(2), dp(26), 0, dp(10))
    }

    /** A paper tag, like "02 / NUDGES" on the dashboard sections. */
    fun tag(s: String) = text(s.uppercase(), 11f, ink, monoBold, 0.12f).apply {
        background = shadowed(paper, Color.parseColor("#3F6FE0"), offset = 3)
        setPadding(dp(10), dp(5), dp(13), dp(8))
    }

    fun headline(s: String, size: Float = 40f, color: Int = rust) = text(s, size, color, heavy, -0.05f).apply {
        setLineSpacing(0f, 0.85f)
        includeFontPadding = false
    }

    // ---------------------------------------------------------------- containers
    /** A group of rows on one card, separated by thin lines. */
    fun group(vararg rows: View?): LinearLayout = column().apply {
        background = rect(card, line)
        setPadding(dp(16), dp(4), dp(16), dp(4))
        var first = true
        for (r in rows) {
            if (r == null) continue
            if (!first) addView(divider())
            addView(r, lp())
            first = false
        }
    }

    fun divider() = View(a).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(match, 1)
    }

    // ---------------------------------------------------------------- rows
    private fun labels(t: String, n: String?, noteColor: Int = mute) = column().apply {
        addView(title(t))
        if (!n.isNullOrEmpty()) addView(note(n, noteColor))
    }

    private fun rowPad(v: LinearLayout) = v.apply { setPadding(0, dp(14), 0, dp(14)) }

    fun tint(s: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(paper, mute))
        s.trackTintList = ColorStateList(states, intArrayOf(rust, line))
    }

    fun switchRow(title: String, note: String?, checked: Boolean, noteColor: Int = mute, onChange: (Boolean) -> Unit): View =
        rowPad(row()).apply {
            addView(labels(title, note, noteColor), weight())
            addView(Switch(a).apply {
                isChecked = checked; tint(this)
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(12) })
        }

    /** One joined control: tap a part to choose it. */
    fun segments(options: List<Pair<String, Int>>, value: Int, onPick: (Int) -> Unit): View {
        val strip = row().apply { background = rect(Color.TRANSPARENT, line) }
        options.forEachIndexed { i, (label, v) ->
            val on = v == value
            val cell = text(label, 13f, if (on) ink else paper, if (on) monoBold else mono).apply {
                gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                minHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
                background = if (on) rect(paper, radius = 5) else null
                isClickable = true; isFocusable = true
                contentDescription = "$label${if (on) ", selected" else ""}"
                setOnClickListener { if (v != value) onPick(v) }
            }
            if (i > 0) strip.addView(View(a).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(1, dp(24)))
            strip.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        return strip
    }

    fun segmentRow(title: String, note: String?, options: List<Pair<String, Int>>, value: Int,
                   noteColor: Int = mute, onPick: (Int) -> Unit): View = column().apply {
        setPadding(0, dp(14), 0, dp(14))
        addView(labels(title, note, noteColor))
        addView(segments(options, value, onPick), lp(top = 10))
    }

    /** A small bordered button, e.g. a time or "Allow". */
    fun pill(label: String, filled: Boolean = false, onClick: () -> Unit) = text(label, 13f, if (filled) ink else paper, monoBold).apply {
        gravity = Gravity.CENTER
        minHeight = dp(40); minWidth = dp(64)
        setPadding(dp(14), 0, dp(14), 0)
        background = if (filled) rect(paper) else rect(Color.TRANSPARENT, line)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun valueRow(title: String, note: String?, value: String, onClick: () -> Unit, onLong: (() -> Unit)? = null): View =
        rowPad(row()).apply {
            addView(labels(title, note), weight())
            addView(pill(value, filled = true, onClick = onClick).apply {
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(12) })
        }

    /** A permission or health line: a coloured mark, a title, and a fix button when needed. */
    fun statusRow(ok: Boolean, title: String, note: String?, action: String? = null, onAction: (() -> Unit)? = null): View =
        rowPad(row()).apply {
            addView(text(if (ok) "✓" else "!", 14f, if (ok) ink else paper, monoBold).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ok) green else rust) }
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
            addView(labels(title, note), weight())
            if (!ok && action != null && onAction != null)
                addView(pill(action, filled = true, onClick = onAction), LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(10) })
        }

    /** A row that opens something else. */
    fun navRow(title: String, note: String?, icon: Drawable? = null, trailing: View? = null, below: View? = null, onClick: () -> Unit): View =
        rowPad(row()).apply {
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            if (icon != null) addView(iconView(icon), LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(14) })
            addView(labels(title, note).apply { if (below != null) addView(below) }, weight())
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(8) })
            addView(text("›", 22f, mute, sans), LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(10) })
        }

    fun iconView(d: Drawable) = ImageView(a).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER }

    fun appIcon(pkg: String): Drawable? = runCatching { a.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** A small rust label for a rule that is set, e.g. "30m a day". */
    fun miniChip(s: String, color: Int = rust) = text(s, 11f, color, monoBold).apply {
        background = rect(Color.TRANSPARENT, color, radius = 4)
        setPadding(dp(7), dp(2), dp(7), dp(3))
    }

    fun chipsRow(chips: List<String>): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEachIndexed { i, c -> addView(miniChip(c), LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dp(6); topMargin = dp(6) }) }
    }

    /** An app you can switch in or out of a list: icon, name and a tick. */
    fun appToggle(pkg: String, name: String, on: Boolean, note: String? = null, onChange: (Boolean) -> Unit): View =
        rowPad(row()).apply {
            appIcon(pkg)?.let { addView(iconView(it), LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(14) }) }
            addView(labels(name, note, if (note?.contains("tomorrow") == true) rust else mute), weight())
            addView(CheckBox(a).apply {
                isChecked = on
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(rust, mute))
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }

    // ---------------------------------------------------------------- buttons
    fun button(label: String, style: String = "filled", onClick: () -> Unit) = text(label, 15f, paper, monoBold).apply {
        gravity = Gravity.CENTER
        minHeight = dp(52)
        setPadding(dp(16), 0, dp(16), 0)
        when (style) {
            "filled" -> { setTextColor(Color.WHITE); background = shadowed(rust, paper, offset = 3) }
            "ghost" -> { setTextColor(paper); background = rect(Color.TRANSPARENT, line) }
            "danger" -> { setTextColor(rust); background = rect(Color.TRANSPARENT, rust) }
            else -> { setTextColor(ink); background = shadowed(paper, rust, offset = 3) }
        }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A card on the Settings home screen that opens one section. */
    fun sectionCard(number: String, title: String, summary: String, alert: Boolean, onClick: () -> Unit): View {
        val c = column().apply {
            background = shadowed(raised, if (alert) rust else Color.parseColor("#3A3834"), line)
            setPadding(dp(18), dp(16), dp(22), dp(20))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val top = row()
        top.addView(text(number, 11f, if (alert) rust else mute, monoBold, 0.12f), weight())
        if (alert) top.addView(text("NEEDS YOU", 10f, rust, monoBold, 0.12f))
        top.addView(text("  ›", 20f, mute, sans))
        c.addView(top)
        c.addView(headline(title, 24f, paper).apply { setPadding(0, dp(6), 0, dp(4)) })
        c.addView(note(summary))
        return c
    }

    fun frame(child: View) = FrameLayout(a).apply { addView(child) }
}
