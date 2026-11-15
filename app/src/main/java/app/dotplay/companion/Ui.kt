package app.dotplay.companion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny view kit matching the Dotplay look: pastel wallpaper, frosted white cards, moss-green accent. */
class Ui(private val ctx: Context) {
    companion object {
        val ACCENT = Color.parseColor("#3D8548")
        val TEXT = Color.parseColor("#17171C")
        val DIM = Color.parseColor("#6A6A75")
        val FAINT = Color.parseColor("#2417171C")
        val CARD = Color.parseColor("#A6FFFFFF")
        val CARD_BORDER = Color.parseColor("#E6FFFFFF")
        val BG_TOP = Color.parseColor("#EEF3E6")
        val BG_MID = Color.parseColor("#ECE8F4")
        val BG_BOTTOM = Color.parseColor("#F4ECE6")
        val GREEN_BLOB = Color.parseColor("#66A6DB8C")
        val LILAC_BLOB = Color.parseColor("#55BBA8F2")
        val PEACH_BLOB = Color.parseColor("#44F6B98E")
    }

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Soft gradient with three radial colour fields, like a Nothing OS 5 wallpaper. */
    fun wallpaper(): LayerDrawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG_TOP, BG_MID, BG_BOTTOM))
        fun blob(color: Int, cx: Float, cy: Float, r: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, Color.TRANSPARENT)
            setGradientCenter(cx, cy)
            gradientRadius = dp(r.toInt()).toFloat()
        }
        return LayerDrawable(arrayOf(base, blob(GREEN_BLOB, 0.9f, 0.05f, 320f), blob(LILAC_BLOB, 0.05f, 0.4f, 300f), blob(PEACH_BLOB, 0.7f, 0.9f, 260f)))
    }

    fun space(parent: LinearLayout, h: Int) = parent.addView(View(ctx), LinearLayout.LayoutParams(1, dp(h)))

    fun text(s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(4))
        setLineSpacing(0f, 1.15f)
    }

    fun title(s: String, size: Float = 38f) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(TEXT)
        typeface = Typeface.create("serif", Typeface.NORMAL)
        letterSpacing = -0.01f
    }

    fun label(s: String, color: Int = DIM) = text(s.uppercase(), 11f, color).apply {
        typeface = Typeface.MONOSPACE; letterSpacing = 0.12f
    }

    /** Big dot-ish number: monospace bold stands in for the dot-matrix font. */
    fun number(s: String, size: Float, color: Int = TEXT) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(26).toFloat(); setStroke(dp(1), CARD_BORDER) }
            elevation = dp(2).toFloat()
        }
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return c
    }

    fun input(value: String, hint: String, password: Boolean = false) = EditText(ctx).apply {
        setText(value); this.hint = hint; setTextColor(TEXT); setHintTextColor(DIM); textSize = 15f
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), FAINT) }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) }
    }

    fun button(s: String, accent: Boolean = false, ghost: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = s; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (ghost) TEXT else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(if (ghost) Color.parseColor("#80FFFFFF") else if (accent) ACCENT else TEXT)
            if (ghost) setStroke(dp(1), FAINT)
        }
        stateListAnimator = null
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
    }

    /** Row of dots filled up to [fraction], like the web app's DotLine. */
    fun dotLine(fraction: Float, dots: Int = 24): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val on = if (fraction > 0) maxOf(1, Math.round(fraction * dots)) else 0
        for (i in 0 until dots) {
            addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i < on) ACCENT else FAINT) }
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(4) })
        }
    }
}
