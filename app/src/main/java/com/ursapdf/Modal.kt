package com.ursapdf

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A card over the screen. Opening blurs and darkens [behind] over 250 ms; closing
 * undoes both over 250 ms. Blur needs Android 12; older phones only darken.
 * Tapping outside the card or pressing Back cancels.
 */
class Modal(private val a: Activity, private val host: FrameLayout, private val behind: View) {

    private var overlay: FrameLayout? = null
    private var anim: ValueAnimator? = null
    val showing get() = overlay != null

    fun confirm(title: String, message: String, action: String, onOk: () -> Unit) =
        open(title, message, null, action, onOk = { onOk() })

    /** A text field prefilled with [initial]; [onOk] gets the trimmed text. */
    fun input(title: String, initial: String, action: String, onOk: (String) -> Unit) =
        open(title, null, initial, action, onOk)

    /** A multi-line text box, for form fields that hold paragraphs. */
    fun inputLines(title: String, initial: String, action: String, onOk: (String) -> Unit) =
        open(title, null, initial, action, onOk, multiline = true)

    /**
     * Stacked full-width choices; tapping outside or Back picks none. The first choice
     * is drawn as the primary one.
     */
    fun choose(title: String, message: String?, choices: List<Pair<String, () -> Unit>>) =
        open(title, message, null, "", {}, choices = choices)

    fun cancel() = close()

    private fun open(
        title: String, message: String?, initial: String?, action: String, onOk: (String) -> Unit,
        choices: List<Pair<String, () -> Unit>>? = null, multiline: Boolean = false,
    ) {
        if (showing) return
        val scrim = View(a).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = a.pill(Theme.panel(), 16f)
            elevation = a.dp(8f)
            setPadding(a.dpi(24), a.dpi(20), a.dpi(24), a.dpi(12))
            isClickable = true
            alpha = 0f; scaleX = 0.96f; scaleY = 0.96f
        }
        card.addView(a.label(title, 18f, Theme.text(), bold = true))
        if (message != null) card.addView(a.label(message, 15f, Theme.sub()).apply { setPadding(0, a.dpi(8), 0, 0) })
        val field = initial?.let {
            EditText(a).apply {
                setText(it)
                setTextColor(Theme.text())
                textSize = 16f
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3; maxLines = 8
                    setSelection(it.length)
                } else {
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    // Select the name without its extension, the part a person usually changes.
                    setSelection(0, it.lastIndexOf('.').takeIf { i -> i > 0 } ?: it.length)
                }
            }.also { f -> card.addView(f, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dpi(12) }) }
        }
        val buttons = LinearLayout(a).apply { gravity = Gravity.END; setPadding(0, a.dpi(16), 0, 0) }
        fun button(t: String, color: Int, act: () -> Unit) = a.label(t, 15f, color, bold = true).apply {
            background = a.ripple()
            setPadding(a.dpi(16), a.dpi(12), a.dpi(16), a.dpi(12))
            setOnClickListener { act() }
        }
        if (choices != null) {
            val stack = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dpi(16), 0, a.dpi(4)) }
            choices.forEachIndexed { k, (label, act) ->
                stack.addView(a.label(label, 15f, if (k == 0) BG else Theme.text(), bold = true).apply {
                    gravity = Gravity.CENTER
                    background = a.pill(if (k == 0) ACCENT else Theme.button(), 14f)
                    foreground = a.ripple()
                    setPadding(a.dpi(16), a.dpi(12), a.dpi(16), a.dpi(12))
                    setOnClickListener { close(); act() }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (k > 0) topMargin = a.dpi(8) })
            }
            card.addView(stack)
        } else {
            buttons.addView(button("Cancel", Theme.sub()) { close() })
            val ok = { close(); onOk(field?.text?.toString()?.let { if (multiline) it else it.trim() } ?: "") }
            buttons.addView(button(action, Theme.accentInk()) { ok() })
            field?.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { ok(); true } else false }
            card.addView(buttons)
        }

        val o = FrameLayout(a).apply { isClickable = true; setOnClickListener { close() } }
        o.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        o.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = a.dpi(32); rightMargin = a.dpi(32)
        })
        host.addView(o, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = o
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
        run(0f, 1f, scrim)
        field?.let { f ->
            f.requestFocus()
            f.post { (a.getSystemService(InputMethodManager::class.java)).showSoftInput(f, 0) }
        }
    }

    private fun close() {
        val o = overlay ?: return
        overlay = null
        o.isClickable = false
        a.currentFocus?.let { (a.getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(it.windowToken, 0) }
        val scrim = o.getChildAt(0)
        o.getChildAt(1).animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
        run(1f, 0f, scrim) { host.removeView(o) }
    }

    /** Dim and blur together; [t] 0 is clear, 1 is fully dimmed and blurred. */
    private fun run(from: Float, to: Float, scrim: View, end: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = Motion.MS
            interpolator = Motion.FADE
            addUpdateListener {
                val t = it.animatedValue as Float
                scrim.alpha = 0.5f * t
                if (Build.VERSION.SDK_INT >= 31) {
                    val r = a.dp(20f) * t
                    behind.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                }
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) = end()
            })
            start()
        }
    }
}
