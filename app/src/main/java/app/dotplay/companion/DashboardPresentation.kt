package app.dotplay.companion

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Second-screen view for dual-screen handhelds (AYN Thor bottom screen):
 * hours this week, a dot bar per day and the games you played, all computed on the device.
 */
class DashboardPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
    private lateinit var ui: Ui
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(context)
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22))
        }
        setContentView(ScrollView(context).apply { background = ui.wallpaper(); addView(root) })
        render(emptyList(), "never")
    }

    fun render(items: List<PlayItem>, lastSync: String) {
        if (!::root.isInitialized) return
        root.removeAllViews()
        val total = items.sumOf { it.days.values.sum() }
        root.addView(ui.label("● This week on this device", Ui.ACCENT))

        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(ui.number(fmt(total), 54f, Ui.ACCENT))
        head.addView(ui.text("  ${items.size} games", 16f, Ui.DIM).apply { setPadding(0, ui.dp(30), 0, 0) })
        root.addView(head)

        // dot bars per day (7 columns)
        val days = (6 downTo 0).map { dayKey(System.currentTimeMillis() - it * 86_400_000L) }
        val perDay = days.map { d -> items.sumOf { it.days[d] ?: 0 } }
        val max = maxOf(1, perDay.maxOrNull() ?: 1)
        val card = ui.card(root)
        val bars = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        perDay.forEachIndexed { i, m ->
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
            val rows = 8
            val on = if (m > 0) maxOf(1, Math.round(m.toFloat() / max * rows)) else 0
            for (r in 0 until rows) {
                col.addView(android.view.View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (rows - r <= on) (if (i == 6) Ui.ACCENT else Ui.TEXT) else Ui.FAINT)
                    }
                }, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply { bottomMargin = ui.dp(4) })
            }
            col.addView(ui.label(days[i].takeLast(2)))
            bars.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(bars)

        // games
        val games = ui.card(root)
        if (items.isEmpty()) games.addView(ui.text("No playtime yet this week. Start something in Cocoon!", 15f, Ui.DIM))
        val top = items.sortedByDescending { it.days.values.sum() }.take(8)
        val best = top.firstOrNull()?.days?.values?.sum() ?: 1
        top.forEach { g ->
            val m = g.days.values.sum()
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ui.text(g.title, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.number(fmt(m), 16f))
            games.addView(row)
            games.addView(ui.label(g.platform))
            games.addView(ui.dotLine(m.toFloat() / best))
            ui.space(games, 10)
        }
        root.addView(ui.text("Last sync: $lastSync", 12f, Ui.DIM))
    }

    private fun fmt(min: Int): String = if (min < 60) "${min}m" else String.format(java.util.Locale.US, "%.1fh", min / 60f).replace(".0h", "h")
}
