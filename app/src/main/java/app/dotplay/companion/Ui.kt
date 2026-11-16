package app.dotplay.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * View kit matching the Dotplay website: pastel wallpaper, solid white cards, Poppins, moss-green pill buttons
 * and the dot-matrix play logo.
 */
class Ui(private val ctx: Context) {
    companion object {
        val ACCENT = Color.parseColor("#3D8548")
        val ACCENT_SOFT = Color.parseColor("#243D8548")
        val TEXT = Color.parseColor("#17171C")
        val DIM = Color.parseColor("#4F4F5B")
        val FAINT = Color.parseColor("#2917171C")
        val LINE = Color.parseColor("#2417171C")
        val CARD = Color.parseColor("#E6FFFFFF")
        val CARD_BORDER = Color.parseColor("#1417171C")
        val DANGER = Color.parseColor("#D9434A")
        val BG_TOP = Color.parseColor("#EEF3E6")
        val BG_MID = Color.parseColor("#ECE8F4")
        val BG_BOTTOM = Color.parseColor("#F4ECE6")
        val GREEN_BLOB = Color.parseColor("#80A6DB8C")
        val LILAC_BLOB = Color.parseColor("#66BBA8F2")
        val PEACH_BLOB = Color.parseColor("#55F6B98E")
    }

    private val regular: Typeface = ctx.resources.getFont(R.font.poppins_regular)
    private val medium: Typeface = ctx.resources.getFont(R.font.poppins_medium)
    private val semibold: Typeface = ctx.resources.getFont(R.font.poppins_semibold)
    private val bold: Typeface = ctx.resources.getFont(R.font.poppins_bold)

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * ctx.resources.displayMetrics.density

    /** Soft gradient with three colour fields, like the website background. */
    fun wallpaper(): LayerDrawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG_TOP, BG_MID, BG_BOTTOM))
        fun blob(color: Int, cx: Float, cy: Float, r: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, Color.TRANSPARENT)
            setGradientCenter(cx, cy)
            gradientRadius = dp(r).toFloat()
        }
        return LayerDrawable(arrayOf(base, blob(GREEN_BLOB, 0.9f, 0.05f, 340), blob(LILAC_BLOB, 0.05f, 0.4f, 320), blob(PEACH_BLOB, 0.7f, 0.95f, 280)))
    }

    fun space(parent: LinearLayout, h: Int) = parent.addView(View(ctx), LinearLayout.LayoutParams(1, dp(h)))

    fun text(s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = if (bold) semibold else regular
        setLineSpacing(0f, 1.25f)
        setPadding(0, dp(2), 0, dp(2))
    }

    fun title(s: String, size: Float = 30f) = text(s, size, TEXT).apply { typeface = semibold; letterSpacing = -0.01f }

    fun label(s: String, color: Int = DIM) = text(s.uppercase(), 12f, color).apply { typeface = semibold; letterSpacing = 0.05f }

    /** Big code like K7Q-4ZP, easy to read from across the room. */
    fun code(s: String) = text(s, 44f, TEXT).apply { typeface = bold; letterSpacing = 0.12f; gravity = Gravity.CENTER }

    fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dpf(26f); setStroke(dp(1), CARD_BORDER) }
            elevation = dpf(3f)
        }
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return c
    }

    fun input(value: String, hint: String, password: Boolean = false) = EditText(ctx).apply {
        setText(value); this.hint = hint; setTextColor(TEXT); setHintTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = regular
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(16f); setStroke(dp(2), LINE) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) }
    }

    /** Pill button: accent (green), primary (dark) or ghost (white with a border). At least 52dp tall. */
    fun button(s: String, accent: Boolean = false, ghost: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = s; isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = semibold
        setTextColor(if (ghost) (if (danger) DANGER else TEXT) else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dpf(999f)
            setColor(when { ghost -> Color.WHITE; accent -> ACCENT; danger -> DANGER; else -> TEXT })
            if (ghost) setStroke(dp(2), LINE)
        }
        elevation = if (ghost) 0f else dpf(2f)
        stateListAnimator = null
        gravity = Gravity.CENTER
        minHeight = dp(52)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) }
    }

    /** Small text link in the accent colour. */
    fun link(s: String, onClick: () -> Unit) = text(s, 15f, ACCENT).apply {
        typeface = semibold
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    /** "Step 2 of 5" with five dots, the current one green. */
    fun steps(current: Int, total: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (i in 1..total) {
            addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i <= current) ACCENT else FAINT) }
            }, LinearLayout.LayoutParams(dp(if (i == current) 12 else 9), dp(if (i == current) 12 else 9)).apply { marginEnd = dp(6) })
        }
        addView(label("  Step $current of $total"))
    }

    /** ✓ / • row used in the setup and on the home screen. */
    fun check(done: Boolean, s: String) = text((if (done) "✓  " else "•  ") + s, 15f, if (done) ACCENT else DIM).apply {
        typeface = if (done) semibold else regular
    }

    /** Row of dots filled up to [fraction], like the website's DotLine. */
    fun dotLine(fraction: Float, dots: Int = 24): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val on = if (fraction > 0) maxOf(1, Math.round(fraction * dots)) else 0
        for (i in 0 until dots) {
            addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i < on) ACCENT else FAINT) }
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(4) })
        }
    }

    fun number(s: String, size: Float, color: Int = TEXT) = text(s, size, color).apply { typeface = bold }

    /** The Dotplay mark: a play triangle of dots with a green tip, plus the word "Dotplay". */
    fun logo(size: Int = 30): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LogoMark(ctx), LinearLayout.LayoutParams(dp(size * 2 / 3), dp(size)))
        addView(text("Dotplay", size * 0.7f, TEXT).apply { typeface = bold; setPadding(dp(10), 0, 0, 0) })
    }

    private class LogoMark(ctx: Context) : View(ctx) {
        private val glyph = listOf("#...", "##..", "###.", "###G", "###.", "##..", "#...")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val pitch = height / 7f
            glyph.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch ->
                    if (ch == '.') return@forEachIndexed
                    paint.color = if (ch == 'G') ACCENT else TEXT
                    c.drawCircle(x * pitch + pitch / 2, y * pitch + pitch / 2, pitch * 0.4f, paint)
                }
            }
        }
    }
}
