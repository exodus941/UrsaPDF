package com.ursapdf

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * A pop-up menu that fades in and out (250 ms, linear). It takes no focus and lets
 * touches outside it through, so the controls around it keep working; the host closes
 * it where a tap should only close it. Repaints with the theme while open. Scrolls once
 * taller than [show]'s maxHeight.
 */
class Menu(private val a: Activity, private val host: View) {

    /**
     * [icon] leads the label; [mark] trails it and is asked again after each tap, so a
     * toggle shows its new state. A row with [stay] keeps the menu open when tapped.
     * [remove] adds a trailing minus button that drops the row at once, no confirmation.
     */
    class Row(
        val text: String, val icon: Int? = null, val mark: (() -> Int?)? = null, val stay: Boolean = false,
        val remove: (() -> Unit)? = null, val act: () -> Unit,
    )

    /** Caps its height; a ScrollView alone wraps to everything inside it. */
    private class Capped(a: Activity, val max: Int) : ScrollView(a) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, if (max > 0) MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST) else h)
    }

    private var pop: PopupWindow? = null
    private var outer: View? = null
    private var box: LinearLayout? = null
    private var empty: String? = null
    private val onTheme: () -> Unit = { paint() }
    val showing get() = pop != null

    /** [empty] replaces the rows once the last is removed. */
    fun show(rows: List<Row>, gravity: Int, x: Int, y: Int, maxHeight: Int = 0, empty: String? = null) {
        dismiss(fade = false)
        this.empty = empty
        val b = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, a.dpi(8), 0, a.dpi(8))
        }
        for (r in rows) b.addView(rowView(r), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (rows.isEmpty() && empty != null) b.addView(emptyView(empty))
        val o = Capped(a, maxHeight).apply {
            elevation = a.dp(8f)
            alpha = 0f
            isVerticalScrollBarEnabled = true
            addView(b, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        box = b; outer = o
        pop = PopupWindow(o, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            elevation = a.dp(8f)
            setBackgroundDrawable(null)
            isOutsideTouchable = false
            isTouchable = true
            animationStyle = 0
            showAtLocation(host, gravity, x, y)
        }
        Theme.listeners += onTheme
        paint()
        o.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
    }

    private fun rowView(r: Row): LinearLayout = LinearLayout(a).apply row@{
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = r
        val trails = r.mark != null || r.remove != null
        setPadding(a.dpi(if (r.icon != null) 16 else 20), a.dpi(if (r.remove != null) 0 else 12), a.dpi(if (r.remove != null) 4 else if (r.mark != null) 16 else 24), a.dpi(if (r.remove != null) 0 else 12))
        if (trails) minimumWidth = a.dpi(200)
        r.icon?.let { addView(ImageView(a).apply { setImageResource(it); tag = "icon" }, LinearLayout.LayoutParams(a.dpi(22), a.dpi(22))) }
        addView(a.label(r.text, 15f, Theme.text()).apply {
            tag = "text"
            if (r.icon != null) setPadding(a.dpi(16), 0, 0, 0)
        }, if (trails) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // The mark's slot is kept when empty, so the labels of a pick list line up.
        if (r.mark != null) addView(ImageView(a).apply { tag = "mark" }, LinearLayout.LayoutParams(a.dpi(24), a.dpi(24)).apply { marginStart = a.dpi(16) })
        r.remove?.let { rm ->
            addView(ImageView(a).apply {
                tag = "remove"
                setImageResource(R.drawable.ic_remove)
                setPadding(a.dpi(12), a.dpi(12), a.dpi(12), a.dpi(12))
                contentDescription = "Remove ${r.text}"
                setOnClickListener { rm(); drop(this@row) }
            }, LinearLayout.LayoutParams(a.dpi(48), a.dpi(48)).apply { marginStart = a.dpi(8) })
        }
        setOnClickListener { if (!r.stay) dismiss(); r.act(); paint() }
    }

    private fun emptyView(text: String) = a.label(text, 15f, Theme.sub()).apply {
        tag = "empty"
        setPadding(a.dpi(20), a.dpi(12), a.dpi(24), a.dpi(12))
        minimumWidth = a.dpi(200)
    }

    /** A removed row fades out and its height closes, 250 ms; the last one leaves [empty]. */
    private fun drop(row: View) {
        val b = box ?: return
        row.isEnabled = false
        val h = row.height
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = Motion.MS
            interpolator = Motion.OUT
            addUpdateListener {
                val t = it.animatedValue as Float
                row.alpha = t
                row.layoutParams.height = (h * t).toInt(); row.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) {
                    b.removeView(row)
                    val left = (0 until b.childCount).any { b.getChildAt(it).tag is Row }
                    val e = empty
                    if (!left && e != null && box === b) b.addView(emptyView(e).also { v -> v.alpha = 0f; v.fade(true); paint() })
                }
            })
            start()
        }
    }

    fun dismiss(fade: Boolean = true) {
        val p = pop ?: return
        val o = outer
        pop = null; box = null; outer = null
        Theme.listeners -= onTheme
        if (!fade || o == null) { p.dismiss(); return }
        o.animate().cancel()
        o.isEnabled = false
        o.animate().alpha(0f).setDuration((Motion.MS * o.alpha).toLong()).setInterpolator(Motion.FADE)
            .withEndAction { p.dismiss() }.start()
    }

    private fun paint() {
        val b = box ?: return
        outer?.background = a.pill(Theme.popup(), 12f).apply { setStroke(a.dpi(1), Theme.outline()) }
        for (i in 0 until b.childCount) {
            val v = b.getChildAt(i)
            if (v.tag == "empty") { (v as TextView).setTextColor(Theme.sub()); continue }
            val r = v.tag as? Row ?: continue
            v as LinearLayout
            if (v.background == null) v.background = a.ripple()
            (v.background as? android.graphics.drawable.RippleDrawable)?.setColor(android.content.res.ColorStateList.valueOf(Theme.ripple()))
            v.findViewWithTag<TextView>("text").setTextColor(Theme.text())
            v.findViewWithTag<ImageView>("icon")?.setColorFilter(Theme.text())
            v.findViewWithTag<ImageView>("remove")?.let { m ->
                m.setColorFilter(Theme.sub())
                if (m.background == null) m.background = a.ripple()
            }
            v.findViewWithTag<ImageView>("mark")?.let { m ->
                val id = r.mark?.invoke()
                if (id == null) m.setImageDrawable(null) else m.setImageResource(id)
                m.setColorFilter(if (id == R.drawable.ic_toggle_off) Theme.sub() else Theme.accentInk())
            }
        }
    }
}
