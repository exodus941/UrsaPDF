package com.ursapdf

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * A soft edge where a list scrolls under a sticky block or off the screen.
 * Dark mode: black in Multiply, drawn straight into the parent's canvas, so it darkens
 * what scrolls beneath rather than painting over it. Light mode: a fade to the
 * background colour, since Multiply can only darken. During a theme change the two
 * cross-blend with [Theme.t]. [fromBottom] mirrors it for the lower edge. Fades in
 * and out with [show] over 250 ms.
 */
class Vignette(ctx: Context, private val fromBottom: Boolean) : View(ctx) {

    private val shade = Paint().apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.MULTIPLY
        else xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val wash = Paint()
    private var washColor = 0
    private var washT = -1f
    private var on = false

    init { alpha = 0f }

    // A partly faded view normally draws into its own layer, and Multiply would then
    // blend against that empty layer instead of the list.
    override fun hasOverlappingRendering() = false

    fun show(v: Boolean) {
        if (v == on) return
        on = v
        animate().alpha(if (v) 1f else 0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
    }

    private fun ends() = if (fromBottom) height.toFloat() to 0f else 0f to height.toFloat()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val (y0, y1) = ends()
        shade.shader = LinearGradient(0f, y0, 0f, y1, 0x59000000, 0x00000000, Shader.TileMode.CLAMP)  // 35% black
        washColor = 0
    }

    override fun onDraw(c: Canvas) {
        val t = Theme.t
        val w = width.toFloat(); val h = height.toFloat()
        if (t < 1f) {
            shade.alpha = (255 * (1 - t)).toInt()
            c.drawRect(0f, 0f, w, h, shade)
        }
        // Over the shade, the background colour at full strength at the edge, so rows
        // dissolve into the sticky block or the screen edge instead of being cut there.
        // Dark mode keeps it to the outer half, leaving the shade to do the rest.
        val bg = Theme.bg()
        if (bg != washColor || t != washT) {
            washColor = bg; washT = t
            val (y0, y1) = ends()
            val colors = IntArray(STOPS.size) { k ->
                val a = DARK[k] + (LIGHT[k] - DARK[k]) * t
                (bg and 0xFFFFFF) or ((255 * a).toInt() shl 24)
            }
            wash.shader = LinearGradient(0f, y0, 0f, y1, colors, STOPS, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, wash)
    }

    private companion object {
        // Background opacity from the edge (0) to the inner end (1).
        val STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val DARK = floatArrayOf(1f, 0.45f, 0.12f, 0f, 0f)
        val LIGHT = floatArrayOf(1f, 0.62f, 0.32f, 0.1f, 0f)
    }
}
