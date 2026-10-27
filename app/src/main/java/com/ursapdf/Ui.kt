package com.ursapdf

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView

const val ACCENT = 0xFFFFB300.toInt()
const val BG = 0xFF121212.toInt()
const val PANEL = 0xE0181818.toInt()

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.ripple(): android.graphics.drawable.Drawable? {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return getDrawable(tv.resourceId)
}

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

@Suppress("DEPRECATION")
fun Window.immersive() {
    edgeToEdge()
    if (Build.VERSION.SDK_INT >= 30) insetsController?.let {
        it.hide(WindowInsets.Type.systemBars())
        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}

/** Bar and cutout insets, counted even while the bars are hidden: left, top, right, bottom. */
@Suppress("DEPRECATION")
fun WindowInsets.safe(): IntArray = if (Build.VERSION.SDK_INT >= 30) {
    getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
} else intArrayOf(stableInsetLeft, stableInsetTop, stableInsetRight, stableInsetBottom)
