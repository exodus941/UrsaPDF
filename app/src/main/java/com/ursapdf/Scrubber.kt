package com.ursapdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Progress bar under the page strip: filled up to the current page, no handle.
 * Press or drag anywhere on it to jump to the matching page. The whole view
 * height takes the touch; the bar itself is thin.
 */
class Scrubber(ctx: Context, private val pages: Int, private val onSeek: (Int) -> Unit) : View(ctx) {

    var current = 0
        set(v) { field = v; invalidate() }

    private val pad = ctx.dp(16f)
    private val bar = ctx.dp(4f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.line() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.accentInk() }
    private var last = -1

    private fun left() = paddingLeft + pad
    private fun right() = width - paddingRight - pad

    override fun onDraw(c: Canvas) {
        val y = (height - paddingBottom) / 2f
        val l = left(); val r = right()
        val x = l + (r - l) * (current + 1) / pages
        c.drawRoundRect(l, y - bar / 2, r, y + bar / 2, bar / 2, bar / 2, track)
        c.drawRoundRect(l, y - bar / 2, x, y + bar / 2, bar / 2, bar / 2, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = (e.x - left()) / (right() - left())
                // Segment i covers [i/n, (i+1)/n), so the fill ends where the finger is.
                val p = (f * pages).toInt().coerceIn(0, pages - 1)
                if (p != last) { last = p; current = p; onSeek(p) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> last = -1
        }
        return true
    }
}
