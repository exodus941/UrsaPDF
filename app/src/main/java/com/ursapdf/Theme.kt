package com.ursapdf

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator

/**
 * App UI palette. [t] runs 0 (dark) to 1 (light); every colour is a blend at [t],
 * so a toggle animates by moving [t] and redrawing. Document invert is separate.
 */
object Theme {
    private const val DARK_BG = 0xFF121212.toInt()
    private const val LIGHT_BG = 0xFFFFFFFF.toInt()

    var light = false; private set
    var t = 0f; private set
    val listeners = ArrayList<() -> Unit>()
    private var anim: ValueAnimator? = null
    private var loaded = false
    private val argb = ArgbEvaluator()

    private fun mix(dark: Int, light: Int) = argb.evaluate(t, dark, light) as Int

    fun bg() = mix(DARK_BG, LIGHT_BG)
    fun text() = mix(0xFFFFFFFF.toInt(), 0xFF111111.toInt())
    fun sub() = mix(0xFF999999.toInt(), 0xFF555555.toInt())
    // Opaque: a see-through panel lets page text show behind the thumbnails.
    fun panel() = mix(0xFF181818.toInt(), 0xFFF2F2F2.toInt())
    fun line() = mix(0xFF333333.toInt(), 0xFFD0D0D0.toInt())
    fun button() = mix(0xFF333333.toInt(), 0xFFE4E4E4.toInt())
    fun ripple() = mix(0x33FFFFFF, 0x22000000)
    /** Accent as text or stroke: amber on dark, deep orange on light (3.67:1 on the panel). */
    fun accentInk() = mix(ACCENT, 0xFFC26400.toInt())
    /** Reader backdrop around pages. */
    fun backdrop() = mix(0xFF2A2A2A.toInt(), 0xFFDDDDDD.toInt())

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences("ursa", Context.MODE_PRIVATE)
        light = if (p.contains("light")) p.getBoolean("light", false) else isEink()
        t = if (light) 1f else 0f
    }

    fun toggle(c: Context) {
        light = !light
        c.getSharedPreferences("ursa", Context.MODE_PRIVATE).edit().putBoolean("light", light).apply()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(t, if (light) 1f else 0f).apply {
            duration = 250
            interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener { a ->
                t = a.animatedValue as Float
                for (l in Theme.listeners.toList()) l()  // bare `listeners` is the animator's own
            }
            start()
        }
    }

    /**
     * Android has no display-type API, so e-ink is detected by maker. The listed
     * brands sell only or mainly e-ink Android devices.
     */
    fun isEink(): Boolean {
        val id = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} ${Build.PRODUCT}".lowercase()
        val makers = listOf("onyx", "boox", "boyue", "likebook", "meebook", "bigme", "pocketbook",
            "dasung", "hisense a5", "hisense a7", "hisense a9", "moaan", "inkpalm", "xiaomi moaan",
            "remarkable", "kobo", "tolino", "supernote", "ratta", "eink", "e-ink", "hanvon", "iflytek")
        return makers.any { it in id }
    }

    /** Dark status and nav icons in light mode. */
    @Suppress("DEPRECATION")
    fun applyBars(w: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (t > 0.5f) m else 0, m)
        } else {
            val f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val v = w.decorView
            v.systemUiVisibility = if (t > 0.5f) v.systemUiVisibility or f else v.systemUiVisibility and f.inv()
        }
    }

    fun dialogTheme() = if (light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
}
