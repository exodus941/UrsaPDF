package com.ursapdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Horizontal page previewer. Thumbnails render lazily, lowest priority. */
class ThumbStrip(ctx: Context, private val eng: PdfEngine, private val onPick: (Int) -> Unit) : View(ctx) {

    private val tw = ctx.dp(56f)
    private val maxTh = ctx.dp(80f)
    private val sp = ctx.dp(12f)
    private val item = tw + sp
    private val labelH = ctx.dp(20f)
    private val pad = ctx.dp(16f)

    var current = 0
        set(v) { field = v; invalidate() }
    private var sx = 0f
    private val scroller = OverScroller(ctx)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val white = Paint().apply { color = Color.WHITE }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2.5f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt(); textSize = ctx.dp(11f); textAlign = Paint.Align.CENTER
    }
    private val textOn = Paint(text).apply { color = ACCENT; isFakeBoldText = true }
    private val r = RectF()
    private val onRendered: () -> Unit = { invalidate() }

    init {
        eng.listeners += onRendered
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        eng.listeners.remove(onRendered)
    }

    private fun maxScroll() = max(0f, pad * 2 + eng.pageCount * item - sp - width)

    fun centerOn(i: Int) {
        scroller.forceFinished(true)
        sx = (pad + i * item + tw / 2 - width / 2f).coerceIn(0f, maxScroll())
        invalidate()
    }

    private fun thumbSize(i: Int): Pair<Float, Float> {
        val c = eng.crop
        val aw = eng.pw[i] * c.width()
        val ah = eng.ph[i] * c.height()
        val s = min(tw / aw, maxTh / ah)
        return Pair(aw * s, ah * s)
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            sx = (sx + dx).coerceIn(0f, maxScroll()); invalidate(); return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(sx.toInt(), 0, -vx.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
            postInvalidateOnAnimation(); return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = ((e.x + sx - pad) / item).toInt()
            if (i in 0 until eng.pageCount && (e.x + sx - pad) - i * item <= tw) onPick(i)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(e) || true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = eng.pageCount
        val first = max(0, ((sx - pad) / item).toInt())
        val last = min(n - 1, ((sx + width - pad) / item).toInt())
        val hh = height - paddingBottom
        val areaH = hh - labelH
        val want = ArrayList<Req>()
        for (i in first..last) {
            val (w, h) = thumbSize(i)
            val cx = pad + i * item + tw / 2 - sx
            val top = (areaH - h) / 2
            r.set(cx - w / 2, top, cx + w / 2, top + h)
            val k = PdfEngine.key(PdfEngine.THUMB, i)
            val b = eng.thumbs.get(k)
            if (b != null) canvas.drawBitmap(b, null, r, bmpPaint) else canvas.drawRect(r, white)
            if (b == null) want += thumbReq(i, w, h)
            if (i == current) canvas.drawRect(r, frame)
            canvas.drawText("${i + 1}", cx, hh - labelH * 0.3f, if (i == current) textOn else text)
        }
        // Prefetch a few on each side.
        for (i in (first - 4 until first) + (last + 1..last + 4)) {
            if (i < 0 || i >= n || eng.thumbs.get(PdfEngine.key(PdfEngine.THUMB, i)) != null) continue
            val (w, h) = thumbSize(i)
            want += thumbReq(i, w, h)
        }
        eng.submit(PdfEngine.Q_THUMB, want)
    }

    private fun thumbReq(i: Int, w: Float, h: Float): Req {
        val s = w / (eng.pw[i] * eng.crop.width())
        return eng.req(PdfEngine.THUMB, i, 0, 0, 0, ceil(w).toInt().coerceAtLeast(1), ceil(h).toInt().coerceAtLeast(1), s)
    }
}
