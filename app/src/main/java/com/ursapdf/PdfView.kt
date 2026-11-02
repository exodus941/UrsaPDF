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
        /** A finger held still for 250 ms at (x, y); later moves of that touch are the listener's. */
        fun onLongPress(x: Float, y: Float) {}
    }

    companion object {
        const val MAX_ZOOM = 8f
        const val TILE = 512
        const val BASE_MAX_PIXELS = 6_000_000f
        const val TURN_MS = 250L
        const val DOUBLE_TAP_ZOOM = 2.5f
        /** Page Turn: the release spring's rate (per second) and how long it runs. */
        const val CURL_OMEGA = 16.0
        const val CURL_MS = 600L
        /** Page Turn: mesh cells across and down the page, and the roll's radius as a share of its width. */
        const val CURL_GRID = 40
        const val CURL_RADIUS = 0.22f
        /** Page Turn: the bend at its darkest, edge on to the viewer. */
        const val CURL_DIM = 0.5f
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

    /**
     * Continuous Mode: every page fitted to the width, stacked top to bottom [stackGap]
     * apart, scrolled and flung freely. Zoom 1 is the page width and the least there is;
     * sideways swipes no longer turn. [sy] then scrolls the whole stack.
     */
    var continuous = false
        set(v) {
            if (v == field) return
            val p = if (fit.isNotEmpty()) position() else null
            field = v
            turnAnim?.cancel(); scroller.forceFinished(true); shift = 0f
            if (p != null) pending = floatArrayOf(p[0], p[1], 0f, if (locked) lockZoom else 1f)
            laidW = 0
            relayout()
        }
    private var pos = FloatArray(0)     // continuous: top of each page in the stack at zoom 1
    private var stackH = 0f             // continuous: the whole stack's height at zoom 1
    private val stackGap = ctx.dp(8f)

    /** Page Turn: a swipe at fit curls the page over like a book's, instead of sliding it. */
    var pageTurn = false

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
    fun pageRect() = RectF(left() + shift, pageY(currentPage), left() + shift + pageW(), pageY(currentPage) + pageH())

    /** page, pan y fraction, pan x fraction. Continuous: y is how far down the page the screen's top is. */
    fun position(): FloatArray {
        val w = pageW(); val h = pageH()
        if (continuous && pos.isNotEmpty()) {
            val f = ((sy / zoom - pos[currentPage]) / dh[currentPage]).coerceIn(0f, 1f)
            return floatArrayOf(currentPage.toFloat(), f, if (w > width) sx / w else 0f)
        }
        return floatArrayOf(currentPage.toFloat(), if (h > height) sy / h else 0f, if (w > width) sx / w else 0f)
    }

    /** Screen y of page [i]'s top: continuous, its place in the stack; else the current page's. */
    private fun pageY(i: Int) = if (continuous && pos.isNotEmpty()) top() + pos[i] * zoom else top()

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
                val s = if (continuous) w / cw else min(w / cw, h / ch)
                fit[i] = s; dw[i] = cw * s; dh[i] = ch * s
                baseF[i] = min(1f, sqrt(BASE_MAX_PIXELS / (dw[i] * dh[i])))
            }
            pos = FloatArray(n)
            var y = 0f
            for (i in 0 until n) { pos[i] = y; y += dh[i] + stackGap }
            stackH = (y - stackGap).coerceAtLeast(0f)
            laidW = w; laidH = h
        }
        pending?.let { p ->
            pending = null
            currentPage = p[0].toInt().coerceIn(0, n - 1)
            zoom = p[3].coerceIn(1f, MAX_ZOOM)
            sy = if (continuous) (pos[currentPage] + p[1] * dh[currentPage]) * zoom else p[1] * pageH()
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
        val all = if (continuous) stackH * zoom else h
        sy = if (all <= band) -(bandTop + (band - all) / 2) else sy.coerceIn(-bandTop, all - height + bandBottom)
    }

    /** Continuous: the page under the middle of the screen is the current one. */
    private fun track() {
        if (!continuous || pos.isEmpty()) return
        val y = (sy + height / 2f) / zoom
        var i = 0
        while (i + 1 < n && pos[i + 1] - stackGap / 2 <= y) i++
        if (i != currentPage) { currentPage = i; listener?.onPageChanged(i) }
    }

    private fun left() = pageW().let { if (it < width) (width - it) / 2 else -sx }
    private fun top() = -sy  // clamp() centres a short page by making sy negative

    private fun changed() {
        clamp()
        track()
        invalidate()
    }

    // ---- zoom lock ----

    /**
     * The locked zoom, 0 when off. While locked, pinch, double-tap and double-tap drag
     * do not zoom; they flash a lock in the centre instead. Drags still pan, and a new
     * page opens at the same zoom, at its top left.
     */
    var lockZoom = 0f
        set(v) { field = v; if (v > 0f && fit.isNotEmpty()) { zoom = v.coerceIn(1f, MAX_ZOOM); changed() } }
    private val locked get() = lockZoom > 0f
    private var lockShown = 0f
    private var lockAnim: ValueAnimator? = null
    private var lockHinted = false  // one flash per touch
    private val lockIcon = ctx.getDrawable(R.drawable.ic_lock)!!.mutate()
    private val lockBox = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lockRect = RectF()

    /** 62.5 ms in, held to 500 ms, 250 ms out; linear. A new attempt restarts it. */
    private fun lockHint() {
        if (lockHinted) return
        lockHinted = true
        lockAnim?.cancel()
        val from = lockShown
        // Time in ms from 0 to 750: rising from where it is over 62.5 ms, full to 500, then out.
        lockAnim = ValueAnimator.ofFloat(0f, 750f).apply {
            duration = 750
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val ms = it.animatedValue as Float
                lockShown = when {
                    ms < 62.5f -> from + (1f - from) * ms / 62.5f
                    ms < 500f -> 1f
                    else -> 1f - (ms - 500f) / 250f
                }
                invalidate()
            }
            start()
        }
    }

    /**
     * A dark see-through box multiplied over the page with a white lock; on an inverted
     * page a light box screened over it with a dark lock.
     */
    private fun drawLock(c: Canvas, e: PdfEngine) {
        if (lockShown <= 0f) return
        val inv = e.invert > 0.5f
        val s = context.dp(96f); val r = context.dp(20f)
        lockRect.set((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
        // Multiply and screen ignore paint alpha, so the strength is an opaque grey:
        // multiply by 40% grey darkens what is behind by 60%; screen by 60% grey lightens it as much.
        val k = 0.6f * lockShown
        val g = ((if (inv) k else 1f - k) * 255).toInt()
        lockBox.color = Color.rgb(g, g, g)
        lockBox.xfermode = android.graphics.PorterDuffXfermode(if (inv) android.graphics.PorterDuff.Mode.SCREEN else android.graphics.PorterDuff.Mode.MULTIPLY)
        c.drawRoundRect(lockRect, r, r, lockBox)
        val i = context.dpi(48); val l = (width - i) / 2; val t = (height - i) / 2
        lockIcon.setBounds(l, t, l + i, t + i)
        lockIcon.setTint(if (inv) 0xFF111111.toInt() else Color.WHITE)
        lockIcon.alpha = (255 * lockShown).toInt()
        lockIcon.draw(c)
    }

    /** [turned]: the page moved under a swipe, not a jump from the strip. */
    private fun setPage(p: Int, turned: Boolean = false) {
        if (continuous) {
            // Scroll the page's top to the screen's top; the zoom and sideways pan stay.
            scroller.forceFinished(true)
            sy = pos[p] * zoom
            if (p != currentPage) { currentPage = p; listener?.onPageChanged(p) }
            clamp(); invalidate()  // not changed(): the page asked for stays current
            return
        }
        shift = 0f; zoom = if (locked) lockZoom.coerceIn(1f, MAX_ZOOM) else 1f; sx = 0f; sy = 0f
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
        val curl = curling()
        // A curl lets go like a sheet of paper: it carries the finger's speed and settles
        // on a critically damped spring, no bounce. A slide keeps its ease-out.
        val x0 = shift - target; val v0 = v; val lift0 = lift
        turnAnim = (if (curl) ValueAnimator.ofFloat(0f, 1f) else ValueAnimator.ofFloat(shift, target)).apply {
            duration = if (curl) CURL_MS else ms
            interpolator = if (curl) android.view.animation.LinearInterpolator() else DecelerateInterpolator()
            addUpdateListener {
                if (curl) {
                    val t = it.animatedFraction * CURL_MS / 1000.0
                    var x = ((x0 + (v0 + CURL_OMEGA * x0) * t) * kotlin.math.exp(-CURL_OMEGA * t)).toFloat()
                    if (x * x0 < 0) x = 0f  // never past the target: no curl the other way
                    shift = target + x
                    lift = if (x0 == 0f) 0f else lift0 * abs(x / x0).coerceAtMost(1f)
                } else shift = it.animatedValue as Float
                invalidate()
            }
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

    // ---- page curl ----
    // The top page rolls round a cylinder lying across it, as paper does: flat up to the
    // cylinder's axis, then bending over it, and past half a turn showing its back, which
    // carries on flat once the roll is complete. The cylinder sits so that the page's
    // corner lands under the finger. The page is drawn as a mesh, each point placed by
    // that roll, so the whole sheet bends rather than folding along a crease; the back is
    // the page faint under paper colour, and the bend is shaded by how far it faces away.
    // Turning forward lifts the current page off the next; turning back lays the previous
    // page down over the current one.

    private var curlBottom = true      // the corner turned: bottom right, else top right
    private var lift = 0f              // the finger's vertical travel; the corner rises with it
    private val shade = Paint()
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val front = FloatArray((CURL_GRID + 1) * (CURL_GRID + 1) * 2)
    private val backV = FloatArray((CURL_GRID + 1) * (CURL_GRID + 1) * 2)
    private val frontC = IntArray((CURL_GRID + 1) * (CURL_GRID + 1))
    private val backC = IntArray((CURL_GRID + 1) * (CURL_GRID + 1))
    private var backBmp: android.graphics.Bitmap? = null
    private var backOf: android.graphics.Bitmap? = null

    private fun curling() = pageTurn && shift != 0f && zoom <= 1.01f &&
        ((shift < 0 && currentPage + 1 < n) || (shift > 0 && currentPage > 0))

    /** The page's back: its print at half size, faint under paper colour. Made once per bitmap. */
    private fun backOf(b: android.graphics.Bitmap): android.graphics.Bitmap {
        if (backOf === b) backBmp?.let { return it }
        backBmp?.recycle()
        val s = android.graphics.Bitmap.createScaledBitmap(b, (b.width / 2).coerceAtLeast(1), (b.height / 2).coerceAtLeast(1), true)
        val m = if (s.isMutable) s else s.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { if (it !== s) s.recycle() }
        Canvas(m).drawColor(0xD9FFFFFF.toInt())
        backBmp = m; backOf = b
        return m
    }

    private fun grey(k: Float): Int { val g = (255 * k.coerceIn(0f, 1f)).toInt(); return Color.rgb(g, g, g) }

    private fun drawCurl(c: Canvas, e: PdfEngine, want: MutableList<Req>) {
        val fwd = shift < 0
        val top = if (fwd) currentPage else currentPage - 1
        val under = if (fwd) currentPage + 1 else currentPage
        val step = width + gap
        // How far over the top page is: 0 lying flat, 1 turned all the way.
        val p = (if (fwd) -shift / step else 1 - shift / step).coerceIn(0f, 1f)
        drawPage(c, e, under, fitLeft(under), fitTop(under), 1f, want, null)
        drawHits(c, e, under, fitLeft(under), fitTop(under), 1f)
        if (p >= 1f) return
        val l = fitLeft(top); val t = fitTop(top); val w = dw[top]; val h = dh[top]
        val bmp = e.base.get(PdfEngine.key(PdfEngine.BASE, top)) ?: e.thumbs.get(PdfEngine.key(PdfEngine.THUMB, top))
        if (e.needs(PdfEngine.key(PdfEngine.BASE, top))) want += baseReq(e, top)
        if (bmp == null) return
        val cx = l + w; val cy = if (curlBottom) t + h else t
        // The corner swings from where it lies to its mirror past the spine at the left edge;
        // the finger's vertical travel lifts it most halfway over.
        var tx = cx - p * 2 * w
        var ty = cy + lift * kotlin.math.sin(Math.PI * p).toFloat()
        // Paper does not stretch: the corner stays within the page's width of the spine.
        val sdx = tx - l; val sdy = ty - cy
        val sd = kotlin.math.hypot(sdx, sdy)
        if (sd > w) { tx = l + sdx * w / sd; ty = cy + sdy * w / sd }
        var nx = cx - tx; var ny = cy - ty
        val len = kotlin.math.hypot(nx, ny)
        if (len < 0.5f) { drawPage(c, e, top, l, t, 1f, want, null); drawHits(c, e, top, l, t, 1f); return }
        nx /= len; ny /= len
        // The roll grows from nothing as the page starts over, so a resting page is flat.
        val r = w * CURL_RADIUS * (p * 4).coerceAtMost(1f)
        val pr = (Math.PI * r).toFloat()
        // The corner lies flat past the roll, under the finger: it is dc beyond the axis.
        val dc = (len + pr) / 2
        val ax = cx - nx * dc; val ay = cy - ny * dc
        var k = 0
        for (j in 0..CURL_GRID) for (i in 0..CURL_GRID) {
            val x = l + w * i / CURL_GRID; val y = t + h * j / CURL_GRID
            val d = (x - ax) * nx + (y - ay) * ny
            val q: Float; val lit: Float
            when {
                d <= 0f -> { q = d; lit = 1f }
                d < pr -> {
                    val a = d / r
                    q = r * kotlin.math.sin(a)
                    // Facing the viewer at 0 and pi, edge on at pi/2.
                    lit = CURL_DIM + (1 - CURL_DIM) * kotlin.math.abs(kotlin.math.cos(a))
                }
                else -> { q = -(d - pr); lit = 1f }
            }
            val px = x + (q - d) * nx; val py = y + (q - d) * ny
            // The front holds the points not yet past the top of the roll; the back the rest.
            val half = pr / 2
            val df = minOf(d, half); val qf = if (df <= 0f) df else r * kotlin.math.sin(df / r)
            front[2 * k] = x + (qf - d) * nx; front[2 * k + 1] = y + (qf - d) * ny
            val db = maxOf(d, half); val qb = if (db < pr) r * kotlin.math.sin(db / r) else -(db - pr)
            backV[2 * k] = x + (qb - d) * nx; backV[2 * k + 1] = y + (qb - d) * ny
            frontC[k] = grey(if (d <= half) lit else CURL_DIM)
            backC[k] = grey(if (d >= half) lit else CURL_DIM)
            if (d <= half) { front[2 * k] = px; front[2 * k + 1] = py } else { backV[2 * k] = px; backV[2 * k + 1] = py }
            k++
        }
        // The roll's shadow on the page beneath, along the far side of the bend.
        val ex = ax + nx * r; val ey = ay + ny * r
        val sh = context.dp(24f)
        c.save()
        c.clipRect(fitLeft(under), fitTop(under), fitLeft(under) + dw[under], fitTop(under) + dh[under])
        shade.shader = android.graphics.LinearGradient(ex, ey, ex + nx * sh, ey + ny * sh, 0x54000000, 0, android.graphics.Shader.TileMode.CLAMP)  // 33% black
        // Only beyond the bend: a half-plane, drawn as a big rectangle turned to the axis.
        c.save()
        c.rotate(Math.toDegrees(kotlin.math.atan2(ny.toDouble(), nx.toDouble())).toFloat(), ex, ey)
        c.drawRect(ex, ey - 4 * (width + height), ex + 4 * (width + height), ey + 4 * (width + height), shade)
        c.restore()
        c.restore()
        meshPaint.colorFilter = e.filter
        c.drawBitmapMesh(bmp, CURL_GRID, CURL_GRID, front, 0, frontC, 0, meshPaint)
        c.drawBitmapMesh(backOf(bmp), CURL_GRID, CURL_GRID, backV, 0, backC, 0, meshPaint)
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
            if (locked) { lockHint(); return false }
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
                if (continuous) edgeDir = 0
                if (drag == Drag.NONE && !continuous) { curlBottom = (e1?.y ?: e2.y) > height / 2f; lift = 0f }
                drag = when {
                    zoom > 1.01f || continuous -> Drag.PAN
                    // Sideways wins up to 45 degrees, as in 1.2.0.
                    abs(tx) >= abs(ty) -> Drag.TURN
                    else -> Drag.VERTICAL
                }
            }
            when (drag) {
                Drag.PAN -> { sx += dx; sy += dy; changed() }
                Drag.TURN -> { if (e1 != null) lift = e2.y - e1.y; dragTurn(dx) }
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
                    val minY = if (continuous) (-bandTop).toInt() else 0
                    val maxY = max(0f, if (continuous) stackH * zoom - height + bandBottom else pageH() - height).toInt()
                    // At the page width a continuous flick has nowhere to go but up or down.
                    val (fx, fy) = if (continuous && zoom <= 1.01f) 0f to vy else snapFlick(vx, vy)
                    scroller.fling(sx.toInt(), sy.toInt(), -fx.toInt(), -fy.toInt(), 0, maxX, minY, maxY)
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
            cancelHold()  // a held second tap is a zoom, never the pie menu
            if (locked) {
                // Locked: a drag or a plain double-tap only flashes the lock.
                val moved = e.actionMasked == MotionEvent.ACTION_MOVE && abs(e.y - qsY) > touchSlop
                if (e.actionMasked == MotionEvent.ACTION_DOWN) qsY = e.y
                if (moved || e.actionMasked == MotionEvent.ACTION_UP) lockHint()
                return true
            }
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
            if (formMode) fieldAt(e.x, e.y)?.let { listener?.onFieldTap(if (continuous) pageAt(e.y) else currentPage, it); return }
            listener?.onTap(e.x, e.y)
        }
    // No long-press action exists, and a detected long press drops every later move:
    // a held quick-scale drag froze after 400 ms.
    }).apply { setIsLongpressEnabled(false) }

    /**
     * A flick within 15° of a multiple of 45° travels along that line instead, at the
     * same speed; any other flick keeps its own direction.
     */
    private fun snapFlick(vx: Float, vy: Float): Pair<Float, Float> {
        val speed = kotlin.math.hypot(vx, vy)
        if (speed == 0f) return vx to vy
        val deg = Math.toDegrees(kotlin.math.atan2(vy.toDouble(), vx.toDouble()))
        val near = Math.round(deg / 45.0) * 45.0
        if (abs(deg - near) > 15.0) return vx to vy
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
        // Continuous: the page width already fills the screen's width; in, then back.
        if (continuous) { if (zoom < 1.01f) zoomInAt(fx, fy, DOUBLE_TAP_ZOOM) else zoomToFit(fx, fy); return }
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

    // ---- long press ----
    // Our own, not GestureDetector's: its long press drops every later move, which froze
    // quick scale. Held 250 ms within the touch slop, one finger, no double-tap.
    private var holdX = 0f
    private var holdY = 0f
    private val hold = Runnable { held() }

    private fun held() {
        if (drag != Drag.NONE || scaling || shift != 0f) return
        // The view lets go of this touch; the listener takes its later moves.
        val now = android.os.SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, holdX, holdY, 0)
        scaleDet.onTouchEvent(c); gestures.onTouchEvent(c); c.recycle()
        drag = Drag.NONE
        listener?.onLongPress(holdX, holdY)
    }

    private fun cancelHold() = removeCallbacks(hold)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { holdX = e.x; holdY = e.y; lockHinted = false; removeCallbacks(hold); postDelayed(hold, 250) }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(e.x - holdX, e.y - holdY) > touchSlop) cancelHold()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
        }
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

        if (continuous) {
            // Every page that shows, top to bottom, each with its own tiles.
            for (i in 0 until n) {
                val y = pageY(i)
                if (y > height) break
                if (y + dh[i] * zoom < 0) continue
                drawPage(canvas, e, i, left(), y, zoom, want, tiles)
                drawHits(canvas, e, i, left(), y, zoom)
                drawFields(canvas, e, i, left(), y, zoom)
            }
        } else if (curling()) {
            drawCurl(canvas, e, want)
        } else {
            drawPage(canvas, e, cur, left() + shift, top(), zoom, want, tiles)
            drawHits(canvas, e, cur, left() + shift, top(), zoom)
            drawFields(canvas, e, cur, left() + shift, top(), zoom)
            val step = width + gap
            if (shift < 0 && cur + 1 < n) { drawPage(canvas, e, cur + 1, fitLeft(cur + 1) + shift + step, fitTop(cur + 1), 1f, want, null); drawHits(canvas, e, cur + 1, fitLeft(cur + 1) + shift + step, fitTop(cur + 1), 1f) }
            if (shift > 0 && cur > 0) { drawPage(canvas, e, cur - 1, fitLeft(cur - 1) + shift - step, fitTop(cur - 1), 1f, want, null); drawHits(canvas, e, cur - 1, fitLeft(cur - 1) + shift - step, fitTop(cur - 1), 1f) }
        }

        want += tiles
        for (p in intArrayOf(cur + 1, cur - 1, cur + 2, cur - 2)) {
            if (p < 0 || p >= n) continue
            if (e.needs(PdfEngine.key(PdfEngine.BASE, p))) want += baseReq(e, p)
        }
        e.submit(PdfEngine.Q_VIEW, want)
        drawLock(canvas, e)
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
        if (page != currentPage && !continuous) setPage(page)
        scroller.forceFinished(true)
        bandTop = visTop; bandBottom = height - visBottom
        if (!locked) zoom = max(zoom, 2f)
        val s = fit[page] * zoom
        val cx = (rect.centerX() - e.crop.left * e.pw[page]) * s
        val cy = (rect.centerY() - e.crop.top * e.ph[page]) * s
        sx = cx - width / 2f
        sy = cy - (visTop + visBottom) / 2f + if (continuous) pos[page] * zoom else 0f
        changed()
    }

    private fun drawHits(c: Canvas, e: PdfEngine, i: Int, x: Float, y: Float, z: Float) {
        if (hitFade <= 0f) return
        val list = shownHits[i] ?: return
        val s = fit[i] * z  // screen px per point
        val ox = x - e.crop.left * e.pw[i] * s
        val oy = y - e.crop.top * e.ph[i] * s
        val ink = ACCENT  // amber on the page in either theme
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
        val page = if (continuous) pageAt(y) else currentPage
        val list = fields[page] ?: return null
        val slop = context.dp(6f)
        return list.lastOrNull { f ->
            toScreen(e, page, left() + shift, pageY(page), zoom, f.rect, fr)
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

    /** Continuous: the page at screen row [y]. */
    private fun pageAt(y: Float): Int {
        val s = (y + sy) / zoom
        var i = 0
        while (i + 1 < n && pos[i + 1] - stackGap / 2 <= s) i++
        return i
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
