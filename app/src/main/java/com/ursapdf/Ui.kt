package com.ursapdf

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView

const val ACCENT = 0xFFFFB300.toInt()
const val BG = 0xFF121212.toInt()

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Touch ripple in the current theme's ink. Call [android.graphics.drawable.RippleDrawable.setColor] to retint. */
@Suppress("UnusedReceiverParameter")
fun Context.ripple() = RippleDrawable(ColorStateList.valueOf(Theme.ripple()), null, ColorDrawable(Color.WHITE))

fun Context.pill(color: Int, radiusDp: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp)
}

fun Context.label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

/**
 * Show or hide with a 250 ms linear fade instead of in one frame. [gone] picks GONE or
 * INVISIBLE once faded out. Does nothing when already in that state.
 */
fun View.fade(show: Boolean, gone: Boolean = true) {
    val hidden = if (gone) View.GONE else View.INVISIBLE
    val want = if (show) 1f else 0f
    if (show && visibility == View.VISIBLE && alpha == 1f) return
    if (!show && visibility != View.VISIBLE) return
    animate().cancel()
    if (show && visibility != View.VISIBLE) { alpha = 0f; visibility = View.VISIBLE }
    animate().alpha(want).setDuration((com.ursapdf.Motion.MS * kotlin.math.abs(want - alpha)).toLong())
        .setInterpolator(com.ursapdf.Motion.FADE)
        .withEndAction { if (!show) visibility = hidden }.start()
}

/**
 * A short message in the app's own pill, near the bottom: fades in over 250 ms, holds
 * 2 s, fades out. Used instead of a system toast, whose Android 12+ layout is a narrow
 * box with the app icon pressed against the text, and wraps early.
 */
fun android.app.Activity.note(text: String) {
    val host = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
    host.findViewWithTag<View>("note")?.let { host.removeView(it) }
    val bar = window.decorView.rootWindowInsets?.safe()?.get(3) ?: 0
    val v = label(text, 14f, Theme.text(), bold = true).apply {
        tag = "note"
        background = pill(Theme.popup(), 14f).apply { setStroke(dpi(1), Theme.outline()) }
        setPadding(dpi(16), dpi(10), dpi(16), dpi(10))
        gravity = android.view.Gravity.CENTER
        elevation = dp(8f)
        alpha = 0f
    }
    host.addView(v, android.widget.FrameLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
    ).apply { bottomMargin = dpi(32) + bar; leftMargin = dpi(16); rightMargin = dpi(16) })
    v.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).withEndAction {
        v.animate().alpha(0f).setStartDelay(2000).setDuration(Motion.MS).setInterpolator(Motion.FADE)
            .withEndAction { host.removeView(v) }.start()
    }.start()
}

/** Draw behind the system bars on every API level; views pad themselves by insets. */
@Suppress("DEPRECATION")
fun Window.edgeToEdge() {
    if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
    else decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    if (Build.VERSION.SDK_INT >= 28) attributes = attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

/** Hide the status bar only; the navigation bar always stays up. */
@Suppress("DEPRECATION")
fun Window.immersive() {
    edgeToEdge()
    if (Build.VERSION.SDK_INT >= 30) insetsController?.let {
        it.hide(WindowInsets.Type.statusBars())
        it.show(WindowInsets.Type.navigationBars())
        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}

/** Bar and cutout insets, counted even while the bars are hidden: left, top, right, bottom. */
@Suppress("DEPRECATION")
fun WindowInsets.safe(): IntArray = if (Build.VERSION.SDK_INT >= 30) {
    getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
} else {
    // A fullscreen window on Android 10 and older reports no stable inset for the
    // navigation bar, which stays up. Its window inset does, but that also counts the
    // keyboard, so the bottom is capped at the bar's own height.
    val bar = android.content.res.Resources.getSystem().let { r ->
        r.getIdentifier("navigation_bar_height", "dimen", "android").takeIf { it != 0 }?.let { r.getDimensionPixelSize(it) } ?: 0
    }
    intArrayOf(
        maxOf(stableInsetLeft, systemWindowInsetLeft), stableInsetTop,
        maxOf(stableInsetRight, systemWindowInsetRight),
        maxOf(stableInsetBottom, minOf(systemWindowInsetBottom, bar)),
    )
}
