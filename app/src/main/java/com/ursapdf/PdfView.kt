package com.ursapdf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One page at a time, fitted to the view (after crop). Swipe sideways to turn: the page
 * follows the finger and the next one slides in beside it. Swipe up or down to call
 * [Listener.onVerticalSwipe]. Pinch zooms the current page; while zoomed, drags pan.
 *
 * Rendering is two-level: one base bitmap per page at fit size, drawn scaled at any
 * zoom so a page is never blank, and above zoom 1 a grid of 512 px tiles rendered at
 * the zoom, in quarter-octave steps. Tiles wait until a pinch ends.
 */
class PdfView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onPageChanged(page: Int)
        fun onTurned(page: Int)
        fun onTap(x: Float, y: Float)
        fun onVerticalSwipe()
        fun onFieldTap(page: Int, f: Field) {}
    }

    companion object {
        const val MAX_ZOOM = 8f
        const val TILE = 512
        const val BASE_MAX_PIXELS = 6_000_000f
        const val TURN_MS = 250L
        const val DOUBLE_TAP_ZOOM = 2.5f
    }

    private enum class Drag { NONE, TURN, VERTICAL, PAN }

    var listener: Listener? = null
    private var eng: PdfEngine? = null

    private var fit = FloatArray(0)    // px per point at zoom 1
    private var dw = FloatArray(0)     // page size at zoom 1
    private var dh = FloatArray(0)
    private var baseF = FloatArray(0)  // base bitmap size as a fraction of zoom-1 size
    private var laidW = 0
    private var laidH = 0
    private val gap = ctx.dp(16f)

    var currentPage = 0; private set
    var zoom = 1f; private set
    private var sx = 0f                // pan of the zoomed page
    private var sy = 0f
    private var shift = 0f             // horizontal offset of the current page while turning
    private var pending: FloatArray? = null  // page, y, x, zoom to apply after layout

    private val scroller = OverScroller(ctx)
    private var turnAnim: ValueAnimator? = null
    private var scaling = false
    private var drag = Drag.NONE
    private var vDist = 0f
    private var flung = false
    private var lastFx = 0f
    private var lastFy = 0f
    private val swipeMin = ctx.dp(60f)
    private val flingMin = ctx.dp(400f)

    private var zoomAnim: ValueAnimator? = null
    private var edgeDir = 0                  // +1 next, -1 previous: a push past a zoomed page's edge
    private var dragZoomed = false           // this touch zoomed by pinch or quick scale
    private var qsX = 0f                     // quick scale: anchor, start zoom, active
    private var qsY = 0f
    private var qsZoom = 1f
    private var qsActive = false
    private val quickSpan = ctx.dp(90f)      // drag per doubling or halving
    private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downAtRight = false
    private var downAtLeft = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val white = Paint().apply { color = Color.WHITE }
    private val dst = RectF()
    private val tdst = RectF()
    private val onRendered: () -> Unit = { invalidate() }

    private val n get() = eng?.pageCount ?: 0

    fun setDocument(e: PdfEngine, page: Int, y: Float, x: Float, z: Float) {
        eng = e
        e.listeners += onRendered
        pending = floatArrayOf(page.toFloat(), y, x, z)
        relayout()
    }

    /** Crop changed: refit the current page at zoom 1. */
    fun onCropChanged() {
        pending = floatArrayOf(currentPage.toFloat(), 0f, 0f, 1f)
        laidW = 0
        relayout()
    }

    fun jumpTo(page: Int) {
        if (n == 0) return
        turnAnim?.cancel()
        setPage(page.coerceIn(0, n - 1))
    }

    /** Where the current page sits on screen. */
    fun pageRect() = RectF(left() + shift, top(), left() + shift + pageW(), top() + pageH())

    /** page, pan y fraction, pan x fraction. */
    fun position(): FloatArray {
        val w = pageW(); val h = pageH()
        return floatArrayOf(currentPage.toFloat(), if (h > height) sy / h else 0f, if (w > width) sx / w else 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // A swipe started at a side edge would be taken as the system Back gesture.
        // Android lets an app keep up to 200 dp of height per edge; keep the middle.
        val band = context.dpi(200); val edge = context.dpi(48); val top = (h - band) / 2
        systemGestureExclusionRects = listOf(
            android.graphics.Rect(0, top, edge, top + band),
            android.graphics.Rect(w - edge, top, w, top + band),
        )
        if (pending == null && fit.isNotEmpty()) {
            val p = position()
            pending = floatArrayOf(p[0], p[1], p[2], zoom)
        }
        if (ow != 0) eng?.reset()
        relayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        turnAnim?.cancel()
        zoomAnim?.cancel()
        eng?.listeners?.remove(onRendered)
    }

    private fun relayout() {
        val e = eng ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        if (w != laidW || h != laidH) {
            val c = e.crop
            fit = FloatArray(n); dw = FloatArray(n); dh = FloatArray(n); baseF = FloatArray(n)
            for (i in 0 until n) {
                val cw = e.pw[i] * c.width()
                val ch = e.ph[i] * c.height()
                val s = min(w / cw, h / ch)
                fit[i] = s; dw[i] = cw * s; dh[i] = ch * s
                baseF[i] = min(1f, sqrt(BASE_MAX_PIXELS / (dw[i] * dh[i])))
            }
            laidW = w; laidH = h
        }
        pending?.let { p ->
            pending = null
            currentPage = p[0].toInt().coerceIn(0, n - 1)
            zoom = p[3].coerceIn(1f, MAX_ZOOM)
            sy = p[1] * pageH()
            sx = p[2] * pageW()
            shift = 0f
            listener?.onPageChanged(currentPage)
        }
        changed()
    }

    private fun pageW() = if (fit.isEmpty()) 0f else dw[currentPage] * zoom
    private fun pageH() = if (fit.isEmpty()) 0f else dh[currentPage] * zoom

    /**
     * Screen rows that chrome covers, top and bottom. While search is up the page may
     * scroll this far past its edges, so a match at the foot of a page can rise above
     * the search strip; a short page centres in the uncovered band.
     */
    private var bandTop = 0f
    private var bandBottom = 0f

    fun setBand(top: Float, bottom: Float) {
        bandTop = top; bandBottom = bottom
        changed()
    }

    private fun clamp() {
        val w = pageW(); val h = pageH()
        sx = if (w <= width) 0f else sx.coerceIn(0f, w - width)
        val band = height - bandTop - bandBottom
        sy = if (h <= band) -(bandTop + (band - h) / 2) else sy.coerceIn(-bandTop, h - height + bandBottom)
    }

    private fun left() = pageW().let { if (it < width) (width - it) / 2 else -sx }
    private fun top() = -sy  // clamp() centres a short page by making sy negative

    private fun changed() {
        clamp()
        invalidate()
    }

    /** [turned]: the page moved under a swipe, not a jump from the strip. */
    private fun setPage(p: Int, turned: Boolean = false) {
        shift = 0f; zoom = 1f; sx = 0f; sy = 0f
        scroller.forceFinished(true)
        if (p != currentPage) {
            currentPage = p
            listener?.onPageChanged(p)
            if (turned) listener?.onTurned(p)
        }
        changed()
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val z2 = (zoom * f).coerceIn(1f, MAX_ZOOM)
        val k = z2 / zoom
        val cx = fx - left()
        val cy = fy - top()
        zoom = z2
        sx = cx * k - fx
        sy = cy * k - fy
        changed()
    }

    // ---- page turning ----

    private fun finishTurn(v: Float) {
        val w = width + gap
        val target = when {
            (shift < -width * 0.25f || v < -flingMin) && currentPage < n - 1 -> -w
            (shift > width * 0.25f || v > flingMin) && currentPage > 0 -> w
            else -> 0f
        }
        val ms = (TURN_MS * abs(target - shift) / w).toLong().coerceIn(120L, TURN_MS)
        turnAnim = ValueAnimator.ofFloat(shift, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener { shift = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    when {
                        target < 0 -> setPage(currentPage + 1, turned = true)
                        target > 0 -> setPage(currentPage - 1, turned = true)
                        else -> { shift = 0f; invalidate() }
                    }
                }
            })
            start()
        }
    }

    /** Finger moves the page; past the first or last page it moves at a third of the speed. */
    private fun dragTurn(dx: Float) {
        val next = shift - dx
        val atEdge = (next > 0 && currentPage == 0) || (next < 0 && currentPage == n - 1)
        shift = if (atEdge) shift - dx / 3 else next
        invalidate()
    }

    // ---- gestures ----

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true
            dragZoomed = true  // also true for a two-finger pinch; only read on a double-tap
            zoomAnim?.cancel()
            scroller.forceFinished(true)
            if (shift != 0f) { shift = 0f; drag = Drag.NONE }
            lastFx = d.focusX; lastFy = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            sx -= d.focusX - lastFx
            sy -= d.focusY - lastFy
            lastFx = d.focusX; lastFy = d.focusY
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
            drag = Drag.PAN  // the leftover finger pans; it must not start a turn
            invalidate()
        }
    // Quick scale is ours (see onDoubleTapEvent): the platform's zooms in on a drag
    // down and cannot be made faster.
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling || e2.pointerCount > 1) return true
            if (drag == Drag.NONE) {
                val tx = if (e1 != null) e2.x - e1.x else -dx
                val ty = if (e1 != null) e2.y - e1.y else -dy
                if (zoom > 1.01f && abs(tx) >= abs(ty)) {
                    // Pushing outward from the edge the page already rested on turns it.
                    val maxX = max(0f, pageW() - width)
                    edgeDir = when {
                        tx < 0 && downAtRight && sx >= maxX - 1 -> 1
                        tx > 0 && downAtLeft && sx <= 1 -> -1
                        else -> 0
                    }
                }
                drag = when {
                    zoom > 1.01f -> Drag.PAN
                    // Sideways wins up to 45 degrees, as in 1.2.0.
                    abs(tx) >= abs(ty) -> Drag.TURN
                    else -> Drag.VERTICAL
                }
            }
            when (drag) {
                Drag.PAN -> { sx += dx; sy += dy; changed() }
                Drag.TURN -> dragTurn(dx)
                Drag.VERTICAL -> vDist -= dy
                Drag.NONE -> {}
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return true
            flung = true
            when (drag) {
                Drag.TURN -> finishTurn(vx)
                Drag.VERTICAL -> if (abs(vy) > flingMin || abs(vDist) > swipeMin) listener?.onVerticalSwipe()
                // A fling from the edge turns one page.
                Drag.PAN -> if (edgeDir != 0 && abs(vx) > abs(vy) && abs(vx) > flingMin && (vx < 0) == (edgeDir > 0)) edgeTurn(edgeDir) else {
                    val maxX = max(0f, pageW() - width).toInt()
                    val maxY = max(0f, pageH() - height).toInt()
                    val (fx, fy) = snapFlick(vx, vy)
                    scroller.fling(sx.toInt(), sy.toInt(), -fx.toInt(), -fy.toInt(), 0, maxX, 0, maxY)
                    postInvalidateOnAnimation()
                }
                Drag.NONE -> flung = false
            }
            return true
        }

        // A tap waits out the double-tap window (300 ms): a double-tap zooms in at fit
        // and out to fit when zoomed. It acts when the second tap lifts, and only if
        // the finger did not drag: a held second tap that drags is quick scale.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            tap(e)
            return true
        }

        override fun onDoubleTap(e: MotionEvent) = true

        /**
         * The second tap of a double-tap. Held and dragged, it zooms about the tapped
         * point: up zooms in, down zooms out, doubling or halving every 90 dp. Lifted
         * without a drag, it toggles between fit and 2.5x.
         */
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    qsX = e.x; qsY = e.y; qsZoom = zoom; qsActive = false
                    zoomAnim?.cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - qsY
                    if (!qsActive && abs(dy) > touchSlop) {
                        qsActive = true; scaling = true
                        scroller.forceFinished(true)
                    }
                    if (qsActive) {
                        val target = (qsZoom * 2f.pow(-dy / quickSpan)).coerceIn(1f, MAX_ZOOM)
                        zoomBy(target / zoom, qsX, qsY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (qsActive) {
                        qsActive = false; scaling = false; dragZoomed = true
                        drag = Drag.PAN
                        invalidate()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && !dragZoomed) {
                        doubleTap(e.x, e.y)
                    }
                }
            }
            return true
        }

        private fun tap(e: MotionEvent) {
            if (drag != Drag.NONE || shift != 0f) return
            if (formMode) fieldAt(e.x, e.y)?.let { listener?.onFieldTap(currentPage, it); return }
            listener?.onTap(e.x, e.y)
        }
    // No long-press action exists, and a detected long press drops every later move:
    // a held quick-scale drag froze after 400 ms.
    }).apply { setIsLongpressEnabled(false) }

    /**
     * A flick within 11.25° of a multiple of 45° travels along that line instead, at the
     * same speed; any other flick keeps its own direction.
     */
    private fun snapFlick(vx: Float, vy: Float): Pair<Float, Float> {
        val speed = kotlin.math.hypot(vx, vy)
        if (speed == 0f) return vx to vy
        val deg = Math.toDegrees(kotlin.math.atan2(vy.toDouble(), vx.toDouble()))
        val near = Math.round(deg / 45.0) * 45.0
        if (abs(deg - near) > 11.25) return vx to vy
        val r = Math.toRadians(near)
        return (speed * kotlin.math.cos(r)).toFloat() to (speed * kotlin.math.sin(r)).toFloat()
    }

    /**
     * The whole page fits one screen axis and leaves a margin on the other; this is the
     * zoom at which it fills that other axis too. A4 upright on a phone upright: its
     * height. Turn either one sideways and it flips.
     */
    private fun sideFitZoom(): Float {
        if (fit.isEmpty()) return 1f
        val i = currentPage
        return max(width / dw[i], height / dh[i])
    }

    /**
     * Double-tap steps: whole page, then filling the screen's other axis, then the close
     * zoom, then back to whole page. A page that already fills both skips the middle step.
     */
    private fun doubleTap(fx: Float, fy: Float) {
        val side = sideFitZoom()
        val close = max(DOUBLE_TAP_ZOOM, side * 1.5f).coerceAtMost(MAX_ZOOM)
        when {
            zoom < 1.01f && side > 1.05f -> zoomInAt(fx, fy, side)
            zoom < 1.01f -> zoomInAt(fx, fy, close)
            abs(zoom - side) / side < 0.01f -> zoomInAt(fx, fy, close)
            else -> zoomToFit(fx, fy)
        }
    }

    /**
     * Zoom to [target] over 250 ms, ease-out, carrying the tapped point to the
     * screen centre (as near as the page edges allow).
     */
    private fun zoomInAt(fx: Float, fy: Float, target: Float = DOUBLE_TAP_ZOOM) {
        if (fit.isEmpty()) return
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        val cx = fx - left()   // tapped point in page pixels at zoom 1
        val cy = fy - top()
        val z0 = zoom
        scaling = true
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                zoom = z0 + (target - z0) * t
                val px = fx + (width / 2f - fx) * t    // where the point sits on screen now
                val py = fy + (height / 2f - fy) * t
                sx = cx / z0 * zoom - px
                sy = cy / z0 * zoom - py
                changed()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { scaling = false; invalidate() }
            })
            start()
        }
    }

    /** Zoom back to fit over 250 ms, ease-out, about the tapped point. */
    private fun zoomToFit(fx: Float, fy: Float) {
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        scaling = true  // no tiles for the zoom levels passed on the way
        zoomAnim = ValueAnimator.ofFloat(zoom, 1f).apply {
            duration = 250
            interpolator = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)
            addUpdateListener { zoomBy((it.animatedValue as Float) / zoom, fx, fy) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { scaling = false; invalidate() }
            })
            start()
        }
    }

    /** From a zoomed page's edge: the neighbour arrives at fit, sliding in like a turn. */
    private fun edgeTurn(dir: Int) {
        val target = currentPage + dir
        if (target < 0 || target >= n) return
        setPage(target, turned = true)
        val step = width + gap
        shift = dir * step
        turnAnim = ValueAnimator.ofFloat(shift, 0f).apply {
            duration = TURN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { shift = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // A touch during a turn finishes it at once, so the next drag starts clean.
            turnAnim?.let { if (it.isRunning) it.end() }
            scroller.forceFinished(true)
            drag = Drag.NONE; vDist = 0f; flung = false; edgeDir = 0; dragZoomed = false
            val maxX = max(0f, pageW() - width)
            downX = e.x
            downAtRight = zoom > 1.01f && sx >= maxX - 1
            downAtLeft = zoom > 1.01f && sx <= 1
        }
        scaleDet.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!flung) when (drag) {
                Drag.TURN -> finishTurn(0f)
                Drag.PAN -> if (edgeDir != 0 && (downX - e.x) * edgeDir > swipeMin) edgeTurn(edgeDir)
                Drag.VERTICAL -> if (abs(vDist) > swipeMin) listener?.onVerticalSwipe()
                else -> {}
            }
            if (scroller.isFinished) invalidate()
        }
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
        canvas.drawColor(Theme.backdrop())
        val e = eng ?: return
        if (fit.isEmpty() || laidW != width || laidH != height) return
        bmpPaint.colorFilter = e.filter
        white.colorFilter = e.filter
        val want = ArrayList<Req>()
        val tiles = ArrayList<Req>()
        val cur = currentPage

        drawPage(canvas, e, cur, left() + shift, top(), zoom, want, tiles)
        drawHits(canvas, e, cur, left() + shift, top(), zoom)
        drawFields(canvas, e, cur, left() + shift, top(), zoom)
        val step = width + gap
        if (shift < 0 && cur + 1 < n) { drawPage(canvas, e, cur + 1, fitLeft(cur + 1) + shift + step, fitTop(cur + 1), 1f, want, null); drawHits(canvas, e, cur + 1, fitLeft(cur + 1) + shift + step, fitTop(cur + 1), 1f) }
        if (shift > 0 && cur > 0) { drawPage(canvas, e, cur - 1, fitLeft(cur - 1) + shift - step, fitTop(cur - 1), 1f, want, null); drawHits(canvas, e, cur - 1, fitLeft(cur - 1) + shift - step, fitTop(cur - 1), 1f) }

        want += tiles
        for (p in intArrayOf(cur + 1, cur - 1, cur + 2, cur - 2)) {
            if (p < 0 || p >= n) continue
            if (e.needs(PdfEngine.key(PdfEngine.BASE, p))) want += baseReq(e, p)
        }
        e.submit(PdfEngine.Q_VIEW, want)
    }

    // ---- search hits ----

    /** Matches to paint: page to matches, each match a list of page-point rects. */
    var hits: Map<Int, List<List<RectF>>> = emptyMap()
        set(v) {
            // Highlights fade in when the first arrive and out when cleared, over 250 ms.
            if (field.isEmpty() && v.isNotEmpty()) { fadeHits(1f); shownHits = v }
            else if (v.isEmpty() && field.isNotEmpty()) { shownHits = field; fadeHits(0f) }
            else shownHits = v
            field = v; invalidate()
        }
    private var shownHits: Map<Int, List<List<RectF>>> = emptyMap()
    private var hitFade = 0f
    private var hitAnim: android.animation.ValueAnimator? = null
    private fun fadeHits(to: Float) {
        hitAnim?.cancel()
        hitAnim = android.animation.ValueAnimator.ofFloat(hitFade, to).apply {
            duration = (Motion.MS * kotlin.math.abs(to - hitFade)).toLong()
            interpolator = Motion.FADE
            addUpdateListener { hitFade = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    /** The match being viewed: page and index within that page. */
    var focusHit: Pair<Int, Int>? = null
        set(v) { field = v; invalidate() }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f) }
    private val hr = RectF()

    /**
     * Bring a match into the band of screen between [visTop] and [visBottom] (the part no
     * chrome covers): zoom to at least 2x, the match centred in the band.
     */
    fun reveal(page: Int, rect: RectF, visTop: Float, visBottom: Float) {
        val e = eng ?: return
        if (page != currentPage) setPage(page)
        scroller.forceFinished(true)
        bandTop = visTop; bandBottom = height - visBottom
        zoom = max(zoom, 2f)
        val s = fit[page] * zoom
        val cx = (rect.centerX() - e.crop.left * e.pw[page]) * s
        val cy = (rect.centerY() - e.crop.top * e.ph[page]) * s
        sx = cx - width / 2f
        sy = cy - (visTop + visBottom) / 2f
        changed()
    }

    private fun drawHits(c: Canvas, e: PdfEngine, i: Int, x: Float, y: Float, z: Float) {
        if (hitFade <= 0f) return
        val list = shownHits[i] ?: return
        val s = fit[i] * z  // screen px per point
        val ox = x - e.crop.left * e.pw[i] * s
        val oy = y - e.crop.top * e.ph[i] * s
        val ink = Theme.accentInk()
        val pad = context.dp(2f)
        list.forEachIndexed { k, rects ->
            val focused = focusHit?.let { it.first == i && it.second == k } == true
            hitPaint.color = ink; hitPaint.alpha = ((if (focused) 140 else 70) * hitFade).toInt()
            focusLine.color = ink; focusLine.alpha = (255 * hitFade).toInt()
            for (r in rects) {
                hr.set(ox + r.left * s - pad, oy + r.top * s - pad, ox + r.right * s + pad, oy + r.bottom * s + pad)
                c.drawRoundRect(hr, pad, pad, hitPaint)
                if (focused) c.drawRoundRect(hr, pad, pad, focusLine)
            }
        }
    }

    // ---- form fields ----

    /** Edit mode: fields are outlined and taps on them go to [Listener.onFieldTap]. */
    var formMode = false
        set(v) {
            if (v == field) return
            field = v
            // The outlines fade in and out over 250 ms.
            fieldAnim?.cancel()
            fieldAnim = android.animation.ValueAnimator.ofFloat(fieldFade, if (v) 1f else 0f).apply {
                duration = (Motion.MS * kotlin.math.abs((if (v) 1f else 0f) - fieldFade)).toLong()
                interpolator = Motion.FADE
                addUpdateListener { fieldFade = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var fieldFade = 0f
    private var fieldAnim: android.animation.ValueAnimator? = null
    /** Fields of the pages loaded so far. */
    var fields: Map<Int, List<Field>> = emptyMap()
        set(v) { field = v; invalidate() }
    /**
     * Fields whose value changed, painted over the page by the view itself. Only for an
     * engine whose page renders cannot show edits (the downloaded one).
     */
    var paintEdits: Map<Int, List<Field>> = emptyMap()
        set(v) { field = v; invalidate() }
    private val fieldTint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fieldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f) }
    private val editFill = Paint().apply { color = Color.WHITE }
    private val editInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val editTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fr = RectF()

    private fun pageScale(i: Int, z: Float) = fit[i] * z
    private fun toScreen(e: PdfEngine, i: Int, x: Float, y: Float, z: Float, r: RectF, out: RectF) {
        val s = pageScale(i, z)
        val ox = x - e.crop.left * e.pw[i] * s
        val oy = y - e.crop.top * e.ph[i] * s
        out.set(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s)
    }

    /** The editable field under screen point (x, y) on the current page. */
    private fun fieldAt(x: Float, y: Float): Field? {
        val e = eng ?: return null
        val list = fields[currentPage] ?: return null
        val slop = context.dp(6f)
        return list.lastOrNull { f ->
            toScreen(e, currentPage, left() + shift, top(), zoom, f.rect, fr)
            fr.inset(-slop, -slop)
            fr.contains(x, y)
        }
    }

    private fun drawFields(c: Canvas, e: PdfEngine, i: Int, x: Float, y: Float, z: Float) {
        editFill.colorFilter = e.filter; editInk.colorFilter = e.filter; editTick.colorFilter = e.filter
        paintEdits[i]?.forEach { f ->
            if (f.type == Pdfium.PUSHBUTTON || f.type == Pdfium.SIGNATURE || f.type == 0) return@forEach
            toScreen(e, i, x, y, z, f.rect, fr)
            // Inside the field's own border (about 1.5 pt), which the page already drew.
            val inset = pageScale(i, z) * 1.5f
            fr.inset(inset, inset)
            // Boxes and circles keep their drawn outline; only text-bearing fields are cleared.
            if (f.type != Pdfium.CHECKBOX && f.type != Pdfium.RADIOBUTTON) c.drawRect(fr, editFill)
            when (f.type) {
                Pdfium.CHECKBOX, Pdfium.RADIOBUTTON -> if (f.checked) {
                    val m = fr.width().coerceAtMost(fr.height())
                    editTick.strokeWidth = m * 0.14f
                    if (f.type == Pdfium.RADIOBUTTON) { editInk.style = Paint.Style.FILL; c.drawCircle(fr.centerX(), fr.centerY(), m * 0.28f, editInk) }
                    else {
                        val cx = fr.centerX(); val cy = fr.centerY(); val h = m * 0.32f
                        c.drawLine(cx - h, cy, cx - h * 0.3f, cy + h * 0.7f, editTick)
                        c.drawLine(cx - h * 0.3f, cy + h * 0.7f, cx + h, cy - h * 0.7f, editTick)
                    }
                }
                else -> {
                    val text = if (f.type == Pdfium.TEXTFIELD) f.value else f.selected.firstOrNull()?.let { f.options.getOrNull(it) } ?: f.value
                    editInk.style = Paint.Style.FILL
                    editInk.textSize = (if (f.multiline) z * fit[i] * 10f else fr.height() * 0.62f).coerceAtMost(fr.height() * 0.8f)
                    c.save(); c.clipRect(fr)
                    val pad = if (f.multiline) pageScale(i, z) * 2f else fr.height() * 0.15f
                    if (f.multiline) {
                        var ty = fr.top + editInk.textSize
                        for (line in text.split('\n')) { c.drawText(line, fr.left + pad, ty, editInk); ty += editInk.textSize * 1.2f }
                    } else c.drawText(text, fr.left + pad, fr.centerY() + editInk.textSize * 0.35f, editInk)
                    c.restore()
                }
            }
        }
        if (fieldFade <= 0f) return
        val ink = Theme.accentInk()
        fieldTint.color = ink; fieldTint.alpha = (28 * fieldFade).toInt()
        fieldLine.color = ink; fieldLine.alpha = (255 * fieldFade).toInt()
        fields[i]?.forEach { f ->
            if (!f.editable) return@forEach
            toScreen(e, i, x, y, z, f.rect, fr)
            c.drawRect(fr, fieldTint)
            c.drawRect(fr, fieldLine)
        }
    }

    private fun fitLeft(i: Int) = (width - dw[i]) / 2
    private fun fitTop(i: Int) = (height - dh[i]) / 2

    private fun drawPage(c: Canvas, e: PdfEngine, i: Int, x: Float, y: Float, z: Float, want: MutableList<Req>, tiles: MutableList<Req>?) {
        dst.set(x, y, x + dw[i] * z, y + dh[i] * z)
        val bk = PdfEngine.key(PdfEngine.BASE, i)
        val b = e.base.get(bk)
        if (b != null) c.drawBitmap(b, null, dst, bmpPaint) else {
            // A thumbnail stretched over the page beats a blank sheet while it renders.
            val t = e.thumbs.get(PdfEngine.key(PdfEngine.THUMB, i))
            if (t != null) c.drawBitmap(t, null, dst, bmpPaint) else c.drawRect(dst, white)
        }
        // A stale bitmap (page edited since) keeps drawing until its replacement lands.
        if (e.needs(bk)) want += baseReq(e, i)
        if (tiles == null || scaling || !scroller.isFinished || shift != 0f || z <= 1.05f) return

        val w = width.toFloat(); val h = height.toFloat()
        val li = ceil(ln(z) / ln(2f) * 4f - 0.01f).toInt().coerceAtLeast(1)
        val zq = 2f.pow(li / 4f)
        val span = TILE * z / zq
        val s = fit[i] * zq
        val vx0 = max(dst.left, 0f) - dst.left
        val vx1 = min(dst.right, w) - dst.left
        val vy0 = max(dst.top, 0f) - dst.top
        val vy1 = min(dst.bottom, h) - dst.top
        c.save()
        c.clipRect(dst)
        for (ty in floor(vy0 / span).toInt()..floor((vy1 - 0.5f) / span).toInt()) {
            for (tx in floor(vx0 / span).toInt()..floor((vx1 - 0.5f) / span).toInt()) {
                val tk = PdfEngine.key(PdfEngine.TILE, i, li, tx, ty)
                val t = e.tiles.get(tk)
                if (t != null) {
                    tdst.set(dst.left + tx * span, dst.top + ty * span, dst.left + (tx + 1) * span, dst.top + (ty + 1) * span)
                    c.drawBitmap(t, null, tdst, bmpPaint)
                }
                if (e.needs(tk)) tiles += e.req(PdfEngine.TILE, i, li, tx, ty, TILE, TILE, s, tx * TILE.toFloat(), ty * TILE.toFloat())
            }
        }
        c.restore()
    }

    private fun baseReq(e: PdfEngine, i: Int): Req {
        val f = baseF[i]
        val bw = ceil(dw[i] * f).toInt().coerceAtLeast(1)
        val bh = ceil(dh[i] * f).toInt().coerceAtLeast(1)
        return e.req(PdfEngine.BASE, i, 0, 0, 0, bw, bh, fit[i] * f)
    }
}
