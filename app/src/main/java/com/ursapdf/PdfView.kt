package com.ursapdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Continuous vertical page view. At zoom 1 every page fills the view width (after crop).
 *
 * Rendering is two-level: one base bitmap per page at fit width, drawn scaled at any
 * zoom so a page is never blank, and above zoom 1 a grid of 512 px tiles rendered at
 * the zoom, in quarter-octave steps. Tiles wait until a pinch ends.
 */
class PdfView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onPageChanged(page: Int)
        fun onTap(x: Float, y: Float)
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 8f
        const val TILE = 512
        const val BASE_MAX_PIXELS = 6_000_000f
    }

    var listener: Listener? = null
    private var eng: PdfEngine? = null

    // Layout in doc units: pixels at zoom 1.
    private var tops = FloatArray(0)
    private var hs = FloatArray(0)
    private var fitScale = FloatArray(0)  // px per point at zoom 1
    private var baseF = FloatArray(0)     // base bitmap size as a fraction of zoom-1 size
    private var docH = 0f
    private val gap = ctx.dp(6f)
    private var laidW = 0

    var zoom = 1f; private set
    private var sx = 0f
    private var sy = 0f
    var currentPage = 0; private set
    private var pending: FloatArray? = null  // page, offset, x, zoom to apply after layout

    private val scroller = OverScroller(ctx)
    private var scaling = false
    private var stoppedFling = false
    private var lastFx = 0f
    private var lastFy = 0f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val white = Paint().apply { color = Color.WHITE }
    private val dst = RectF()
    private val tdst = RectF()
    private val onRendered: () -> Unit = { invalidate() }

    fun setDocument(e: PdfEngine, page: Int, offset: Float, x: Float, z: Float) {
        eng = e
        e.listeners += onRendered
        pending = floatArrayOf(page.toFloat(), offset, x, z)
        relayout()
    }

    /** Crop changed: fit the new width at zoom 1, keep the current page at the top. */
    fun onCropChanged() {
        pending = floatArrayOf(currentPage.toFloat(), 0f, 0f, 1f)
        laidW = 0
        relayout()
    }

    fun jumpTo(page: Int) {
        if (tops.isEmpty()) return
        scroller.forceFinished(true)
        sy = tops[page.coerceIn(0, tops.size - 1)] * zoom
        changed()
    }

    /** page, offset of screen top within page (0..1), x fraction. */
    fun position(): FloatArray {
        if (tops.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        val docY = -offY() / zoom
        val p = pageAt(docY)
        val off = ((docY - tops[p]) / hs[p]).coerceIn(0f, 1f)
        val cw = width * zoom
        return floatArrayOf(p.toFloat(), off, if (cw > width) sx / cw else 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (pending == null && tops.isNotEmpty()) {
            val p = position()
            pending = floatArrayOf(p[0], p[1], p[2], zoom)
        }
        if (w != ow) eng?.reset()
        relayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        eng?.listeners?.remove(onRendered)
    }

    private fun relayout() {
        val e = eng ?: return
        val w = width
        if (w <= 0 || height <= 0) return
        if (w != laidW) {
            val n = e.pageCount
            tops = FloatArray(n); hs = FloatArray(n); fitScale = FloatArray(n); baseF = FloatArray(n)
            val c = e.crop
            var y = 0f
            for (i in 0 until n) {
                val s = w / (e.pw[i] * c.width())
                fitScale[i] = s
                hs[i] = e.ph[i] * c.height() * s
                baseF[i] = min(1f, sqrt(BASE_MAX_PIXELS / (w * hs[i])))
                tops[i] = y
                y += hs[i] + gap
            }
            docH = max(0f, y - gap)
            laidW = w
        }
        pending?.let { p ->
            pending = null
            zoom = p[3].coerceIn(MIN_ZOOM, MAX_ZOOM)
            val page = p[0].toInt().coerceIn(0, tops.size - 1)
            sy = (tops[page] + p[1] * hs[page]) * zoom
            sx = p[2] * width * zoom
        }
        changed()
    }

    private fun clamp() {
        val cw = width * zoom
        val ch = docH * zoom
        sx = if (cw <= width) 0f else sx.coerceIn(0f, cw - width)
        sy = if (ch <= height) 0f else sy.coerceIn(0f, ch - height)
    }

    private fun offX(): Float { val cw = width * zoom; return if (cw < width) (width - cw) / 2 else -sx }
    private fun offY(): Float { val ch = docH * zoom; return if (ch < height) (height - ch) / 2 else -sy }

    /** Last page whose top is at or above [docY]. */
    private fun pageAt(docY: Float): Int {
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= docY) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun changed() {
        clamp()
        invalidate()
        if (tops.isEmpty()) return
        val p = pageAt((height / 2f - offY()) / zoom)
        if (p != currentPage) {
            currentPage = p
            listener?.onPageChanged(p)
        }
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val z2 = (zoom * f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = z2 / zoom
        val cx = fx - offX()
        val cy = fy - offY()
        zoom = z2
        sx = cx * k - fx
        sy = cy * k - fy
        changed()
    }

    // ---- gestures ----

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true
            scroller.forceFinished(true)
            lastFx = d.focusX; lastFy = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Pan with the moving focus, then zoom around it.
            sx -= d.focusX - lastFx
            sy -= d.focusY - lastFy
            lastFx = d.focusX; lastFy = d.focusY
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
            invalidate()
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stoppedFling = !scroller.isFinished
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaling && e2.pointerCount == 1) {
                sx += dx; sy += dy
                changed()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return true
            val maxX = max(0f, width * zoom - width).toInt()
            val maxY = max(0f, docH * zoom - height).toInt()
            scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX, 0, maxY)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!stoppedFling) listener?.onTap(e.x, e.y)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP && scroller.isFinished) invalidate()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            sy = scroller.currY.toFloat()
            changed()
            postInvalidateOnAnimation()
        }
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF2A2A2A.toInt())
        val e = eng ?: return
        if (tops.isEmpty() || laidW != width) return
        val w = width.toFloat()
        val h = height.toFloat()
        val ox = offX()
        val oy = offY()
        val cw = w * zoom
        val baseLvl = if (zoom < 0.75f) 1 else 0
        val wantTiles = !scaling && scroller.isFinished && zoom > 1.05f
        val li = ceil(ln(zoom) / ln(2f) * 4f - 0.01f).toInt().coerceAtLeast(1)
        val zq = 2f.pow(li / 4f)
        val k = zoom / zq  // screen px per tile px

        val now = ArrayList<Req>()
        val tilesWanted = ArrayList<Req>()
        val first = pageAt(-oy / zoom)
        var last = first
        var i = first
        while (i < tops.size && tops[i] * zoom + oy < h) {
            dst.set(ox, tops[i] * zoom + oy, ox + cw, (tops[i] + hs[i]) * zoom + oy)
            val bk = PdfEngine.key(PdfEngine.BASE, i, baseLvl)
            val b = e.base.get(bk)
            val shown = b ?: e.base.get(PdfEngine.key(PdfEngine.BASE, i, 1 - baseLvl))
            if (shown != null) canvas.drawBitmap(shown, null, dst, bmpPaint) else canvas.drawRect(dst, white)
            if (b == null) now += baseReq(e, i, baseLvl)

            if (wantTiles) {
                val vx0 = max(dst.left, 0f) - dst.left
                val vx1 = min(dst.right, w) - dst.left
                val vy0 = max(dst.top, 0f) - dst.top
                val vy1 = min(dst.bottom, h) - dst.top
                val span = TILE * k
                val s = fitScale[i] * zq
                canvas.save()
                canvas.clipRect(dst)
                for (ty in floor(vy0 / span).toInt()..floor((vy1 - 0.5f) / span).toInt()) {
                    for (tx in floor(vx0 / span).toInt()..floor((vx1 - 0.5f) / span).toInt()) {
                        val tk = PdfEngine.key(PdfEngine.TILE, i, li, tx, ty)
                        val t = e.tiles.get(tk)
                        if (t != null) {
                            tdst.set(dst.left + tx * span, dst.top + ty * span, dst.left + (tx + 1) * span, dst.top + (ty + 1) * span)
                            canvas.drawBitmap(t, null, tdst, bmpPaint)
                        } else {
                            tilesWanted += e.req(PdfEngine.TILE, i, li, tx, ty, TILE, TILE, s, tx * TILE.toFloat(), ty * TILE.toFloat())
                        }
                    }
                }
                canvas.restore()
            }
            last = i
            i++
        }
        now += tilesWanted
        for (p in listOf(last + 1, first - 1, last + 2, first - 2)) {
            if (p < 0 || p >= tops.size) continue
            if (e.base.get(PdfEngine.key(PdfEngine.BASE, p, baseLvl)) == null) now += baseReq(e, p, baseLvl)
        }
        e.submit(PdfEngine.Q_VIEW, now)
    }

    private fun baseReq(e: PdfEngine, i: Int, lvl: Int): Req {
        val f = baseF[i] * (if (lvl == 1) 0.5f else 1f)
        val bw = ceil(width * f).toInt().coerceAtLeast(1)
        val bh = ceil(hs[i] * f).toInt().coerceAtLeast(1)
        return e.req(PdfEngine.BASE, i, lvl, 0, 0, bw, bh, fitScale[i] * f)
    }
}
