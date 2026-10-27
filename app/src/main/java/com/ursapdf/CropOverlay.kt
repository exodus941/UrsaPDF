package com.ursapdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Shows one whole page, uncropped and fixed, with a crop frame the user drags by its
 * edges, corners or middle. [onDone] gets the new crop (0..1), or null on cancel.
 */
class CropOverlay(
    ctx: Context, private val eng: PdfEngine, private val page: Int,
    insets: IntArray, private val onDone: (RectF?) -> Unit,
) : FrameLayout(ctx) {

    private val crop = RectF(eng.crop)
    private val canvasView = Sheet(ctx, insets)

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10) + insets[3])
            setBackgroundColor(PANEL)
        }
        fun button(t: String, primary: Boolean, act: () -> Unit) = TextView(ctx).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (primary) BG else Color.WHITE)
            background = ctx.pill(if (primary) ACCENT else 0xFF333333.toInt(), 22f)
            setPadding(ctx.dpi(20), ctx.dpi(11), ctx.dpi(20), ctx.dpi(11))
            setOnClickListener { act() }
        }
        val lp = { LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dpi(6); marginEnd = ctx.dpi(6) } }
        bar.addView(button("Reset", false) { crop.set(0f, 0f, 1f, 1f); canvasView.invalidate() }, lp())
        bar.addView(button("Cancel", false) { onDone(null) }, lp())
        bar.addView(button("Apply", true) { onDone(RectF(crop)) }, lp())
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        addView(TextView(ctx).apply {
            text = "Drag the edges to cut margins. Applies to every page."
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(16), ctx.dpi(12) + insets[1], ctx.dpi(16), ctx.dpi(8))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private inner class Sheet(ctx: Context, private val insets: IntArray) : View(ctx) {
        private val page = RectF()   // where the whole page sits on screen
        private val dim = Paint().apply { color = 0xB0000000.toInt() }
        private val white = Paint().apply { color = Color.WHITE }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f) }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val slop = ctx.dp(28f)
        private val minFrac = 0.1f
        private val onRendered: () -> Unit = { invalidate() }

        // Which parts the finger moves.
        private var dl = false; private var dt = false; private var dr = false; private var db = false
        private var lastX = 0f; private var lastY = 0f

        init { eng.listeners += onRendered }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            eng.listeners.remove(onRendered)
            eng.submit(PdfEngine.Q_URGENT, emptyList())
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val top = context.dp(56f) + insets[1]
            val bottom = context.dp(84f) + insets[3]
            val side = context.dp(28f)
            val aw = w - 2 * side
            val ah = h - top - bottom
            val pw = eng.pw[this@CropOverlay.page].toFloat()
            val ph = eng.ph[this@CropOverlay.page].toFloat()
            val s = min(aw / pw, ah / ph)
            val left = (w - pw * s) / 2
            val t = top + (ah - ph * s) / 2
            page.set(left, t, left + pw * s, t + ph * s)
        }

        override fun onDraw(c: Canvas) {
            if (page.isEmpty) return
            val k = PdfEngine.key(PdfEngine.FULL, this@CropOverlay.page)
            val b = eng.base.get(k)
            if (b != null) c.drawBitmap(b, null, page, bmpPaint) else {
                c.drawRect(page, white)
                val s = page.width() / eng.pw[this@CropOverlay.page]
                eng.submit(PdfEngine.Q_URGENT, listOf(eng.req(PdfEngine.FULL, this@CropOverlay.page, 0, 0, 0,
                    ceil(page.width()).toInt(), ceil(page.height()).toInt(), s)))
            }
            val l = page.left + crop.left * page.width()
            val t = page.top + crop.top * page.height()
            val r = page.left + crop.right * page.width()
            val bt = page.top + crop.bottom * page.height()
            // One dim pass outside the frame; four abutting rects leave seams.
            c.save()
            c.clipOutRect(l, t, r, bt)
            c.drawRect(page, dim)
            c.restore()
            c.drawRect(l, t, r, bt, line)
            val kw = context.dp(16f); val kh = context.dp(5f)
            val mx = (l + r) / 2; val my = (t + bt) / 2
            c.drawRoundRect(mx - kw, t - kh, mx + kw, t + kh, kh, kh, knob)
            c.drawRoundRect(mx - kw, bt - kh, mx + kw, bt + kh, kh, kh, knob)
            c.drawRoundRect(l - kh, my - kw, l + kh, my + kw, kh, kh, knob)
            c.drawRoundRect(r - kh, my - kw, r + kh, my + kw, kh, kh, knob)
            val cr = context.dp(6f)
            for (x in floatArrayOf(l, r)) for (y in floatArrayOf(t, bt)) c.drawCircle(x, y, cr, knob)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val l = page.left + crop.left * page.width()
            val t = page.top + crop.top * page.height()
            val r = page.left + crop.right * page.width()
            val b = page.top + crop.bottom * page.height()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val inY = e.y > t - slop && e.y < b + slop
                    val inX = e.x > l - slop && e.x < r + slop
                    // The nearer side wins when the frame is narrow.
                    dl = inY && abs(e.x - l) < slop && abs(e.x - l) <= abs(e.x - r)
                    dr = inY && abs(e.x - r) < slop && !dl
                    dt = inX && abs(e.y - t) < slop && abs(e.y - t) <= abs(e.y - b)
                    db = inX && abs(e.y - b) < slop && !dt
                    if (!dl && !dr && !dt && !db && e.x in l..r && e.y in t..b) { dl = true; dr = true; dt = true; db = true }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - lastX) / page.width()
                    val dy = (e.y - lastY) / page.height()
                    lastX = e.x; lastY = e.y
                    if (dl && dr) {
                        val mx = dx.coerceIn(-crop.left, 1f - crop.right)
                        crop.left += mx; crop.right += mx
                    } else {
                        if (dl) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minFrac)
                        if (dr) crop.right = (crop.right + dx).coerceIn(crop.left + minFrac, 1f)
                    }
                    if (dt && db) {
                        val my = dy.coerceIn(-crop.top, 1f - crop.bottom)
                        crop.top += my; crop.bottom += my
                    } else {
                        if (dt) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minFrac)
                        if (db) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minFrac, 1f)
                    }
                    invalidate()
                }
            }
            return true
        }
    }
}
