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
 * A soft dark edge where a list scrolls under a sticky block or off the screen.
 * Drawn in Multiply straight into the parent's canvas, so it darkens what scrolls
 * beneath rather than painting over it. [fromBottom] mirrors it for the lower edge.
 * Fades in and out with [show] over 250 ms.
 */
class Vignette(ctx: Context, private val fromBottom: Boolean) : View(ctx) {

    private val paint = Paint().apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.MULTIPLY
        else xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private var on = false

    init { alpha = 0f }

    // A partly faded view normally draws into its own layer, and Multiply would then
    // blend against that empty layer instead of the list. One rect cannot overlap itself.
    override fun hasOverlappingRendering() = false

    fun show(v: Boolean) {
        if (v == on) return
        on = v
        animate().alpha(if (v) 1f else 0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val dark = 0x59000000   // 35% black at the edge, multiplied
        val (y0, y1) = if (fromBottom) h.toFloat() to 0f else 0f to h.toFloat()
        paint.shader = LinearGradient(0f, y0, 0f, y1, dark, 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
