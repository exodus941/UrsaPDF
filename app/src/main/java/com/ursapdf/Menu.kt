package com.ursapdf

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * A pop-up menu that fades in and out (250 ms, linear). It takes no focus and lets
 * touches outside it through, so the controls around it keep working; the host closes
 * it where a tap should only close it. Repaints with the theme while open.
 */
class Menu(private val a: Activity, private val host: View) {

    /**
     * [icon] leads the label; [mark] trails it and is asked again after each tap, so a
     * toggle shows its new state. A row with [stay] keeps the menu open when tapped.
     */
    class Row(val text: String, val icon: Int? = null, val mark: (() -> Int?)? = null, val stay: Boolean = false, val act: () -> Unit)

    private var pop: PopupWindow? = null
    private var box: LinearLayout? = null
    private var rows: List<Row> = emptyList()
    private val onTheme: () -> Unit = { paint() }
    val showing get() = pop != null

    fun show(rows: List<Row>, gravity: Int, x: Int, y: Int) {
        dismiss(fade = false)
        this.rows = rows
        val b = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            elevation = a.dp(8f)
            setPadding(0, a.dpi(8), 0, a.dpi(8))
            alpha = 0f
        }
        for (r in rows) b.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dpi(if (r.icon != null) 16 else 20), a.dpi(12), a.dpi(if (r.mark != null) 16 else 24), a.dpi(12))
            if (r.mark != null) minimumWidth = a.dpi(200)
            r.icon?.let { addView(ImageView(a).apply { setImageResource(it); tag = "icon" }, LinearLayout.LayoutParams(a.dpi(22), a.dpi(22))) }
            addView(a.label(r.text, 15f, Theme.text()).apply {
                tag = "text"
                if (r.icon != null) setPadding(a.dpi(16), 0, 0, 0)
            }, if (r.mark != null) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            // The mark's slot is kept when empty, so the labels of a pick list line up.
            if (r.mark != null) addView(ImageView(a).apply { tag = "mark" }, LinearLayout.LayoutParams(a.dpi(24), a.dpi(24)).apply { marginStart = a.dpi(16) })
            setOnClickListener { if (!r.stay) dismiss(); r.act(); paint() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box = b
        pop = PopupWindow(b, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            elevation = a.dp(8f)
            setBackgroundDrawable(null)
            isOutsideTouchable = false
            isTouchable = true
            animationStyle = 0
            showAtLocation(host, gravity, x, y)
        }
        Theme.listeners += onTheme
        paint()
        b.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
    }

    fun dismiss(fade: Boolean = true) {
        val p = pop ?: return
        val b = box
        pop = null; box = null
        Theme.listeners -= onTheme
        if (!fade || b == null) { p.dismiss(); return }
        b.animate().cancel()
        b.isEnabled = false
        b.animate().alpha(0f).setDuration((Motion.MS * b.alpha).toLong()).setInterpolator(Motion.FADE)
            .withEndAction { p.dismiss() }.start()
    }

    private fun paint() {
        val b = box ?: return
        b.background = a.pill(Theme.popup(), 12f)
        for (i in rows.indices) {
            val v = b.getChildAt(i) as LinearLayout
            val r = rows[i]
            if (v.background == null) v.background = a.ripple()
            (v.background as? android.graphics.drawable.RippleDrawable)?.setColor(android.content.res.ColorStateList.valueOf(Theme.ripple()))
            v.findViewWithTag<TextView>("text").setTextColor(Theme.text())
            v.findViewWithTag<ImageView>("icon")?.setColorFilter(Theme.text())
            v.findViewWithTag<ImageView>("mark")?.let { m ->
                val id = r.mark?.invoke()
                if (id == null) m.setImageDrawable(null) else m.setImageResource(id)
                m.setColorFilter(if (id == R.drawable.ic_toggle_off) Theme.sub() else Theme.accentInk())
            }
        }
    }
}
