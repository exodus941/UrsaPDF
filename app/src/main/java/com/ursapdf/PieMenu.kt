package com.ursapdf

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A round menu under a held finger: a blank centre and one segment per item around it,
 * the first at the top, then clockwise. It fades in over 250 ms. The finger drags out
 * onto a segment, which turns yellow, and lifting there picks it; lifting on the
 * centre picks nothing. Either way it fades out over 250 ms. It opens by growing out
 * of the finger rather than fading: a fade started while the chrome slid away could
 * lose its first frames and pop in.
 *
 * The disc tilts toward the finger, as a weighted disc resting on a centre pin would
 * under a press: the side pressed dips away, up to [TILT] degrees at the rim.
 */
class PieMenu(
    private val a: Activity, private val items: List<Item>, x: Float, y: Float,
    private val leftHanded: Boolean, private val done: (PieMenu) -> Unit,
) : View(a) {

    /** [enabled] false draws the segment dimmed and it cannot be picked. */
    class Item(val icon: Int, val name: String, val enabled: Boolean = true, val act: () -> Unit)

    companion object {
        const val TILT = 20f
        /** The finger's turning speed is smoothed over this long (seconds), so uneven touch reports even out. */
        const val SMOOTH = 0.06
        /** The finger's turning speed is its travel over this long, in ms. */
        const val SPEED_MS = 80L
        /**
         * A tick plays the moment the finger crosses into a segment or the centre, but only
         * once it is this far past the line, degrees round the ring or dp in and out, so a
         * finger resting on a line does not chatter.
         */
        const val HYST_DEG = 4.0
        const val HYST_DP = 4f
        /** Turns a second where the pitch starts rising, and where it is full. */
        const val SLOW_TURNS = 0.5625
        const val FULL_TURNS = 2.0
        /** The top pitch: half an octave. */
        const val PITCH = 1.4142135623730951
        /** The volume at full speed against a slow tick: 4, so 12 dB louder (6 dB still faded); capped short of clipping. */
        const val BOOST = 4.0
        /** The beam: its width each side of the finger, in degrees, and its peak strength, 0..1. */
        const val BEAM_HALF = 40f
        const val BEAM_PEAK = 0.5f
        /** The beam's eased fade, sampled at this many stops each side. */
        const val BEAM_STOPS = 8
        /** The glow round the finger, in the centre too: its radius in dp and peak strength. */
        const val GLOW_DP = 144f
        const val GLOW_PEAK = 0.35f
        /** The glow's inverse-square falloff: its core, as a share of the radius, and the stops it is drawn with. */
        const val GLOW_CORE = 0.25f
        const val GLOW_STOPS = 16
        /** The yellow selector's and the name pill's fade, in and out, linear: 125 ms. */
        const val SEL_MS = 125f
        /** The shadow's soft outer part: darkness and blur (dp); it stays centred under the disc. */
        const val PENUMBRA_DARK = 0.35f
        const val PENUMBRA_BLUR = 14f
        /** Its darker core: darkness, blur (dp), radius against the disc's, and how far (dp) it slides away from a press. */
        const val UMBRA_DARK = 0.3f
        const val UMBRA_BLUR = 5f
        const val UMBRA_SIZE = 0.96f
        const val UMBRA_SHIFT = 10f

        /** 0 below the start, 1 at and past the top, linear between. */
        fun ramp(v: Double, start: Double, top: Double) = ((v - start) / (top - start)).coerceIn(0.0, 1.0)
        fun pitch(f: Double) = Math.pow(PITCH, f)
    }

    private val outerR = a.dp(120f)
    private val innerR = a.dp(40f)
    private val iconPx = a.dpi(24)
    private val downX = x
    private val downY = y
    private var cx = x
    private var cy = y
    private var sel = -1
    private var closing = false
    private val icons = items.map { a.getDrawable(it.icon)!!.mutate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = a.dp(1f) }
    private val wedge = Path()
    private val oval = RectF()
    private val inner = RectF()
    private val onTheme: () -> Unit = { invalidate() }
    private val step get() = 360f / items.size
    // One tick each time the finger crosses into a segment or the centre, at once.
    private val ratchet = Ratchet.shared
    private var region = -1           // under the finger: -1 the centre, else a segment, dimmed ones too
    // Speed counts: the finger's turns a second, smoothed over SMOOTH, raise the pitch from
    // SLOW_TURNS to FULL_TURNS.
    private var turns = 0.0
    private var turnDeg = Double.NaN  // angle at the last move on the ring
    private var turnAt = 0L           // event time of that move
    private val trail = ArrayDeque<Pair<Long, Double>>()  // (event time, degrees travelled) over the last SPEED_MS
    private var travel = 0.0          // degrees travelled round the ring this visit
    private var lastF = 0.0           // the last tick's place on the pitch ramp
    private fun turnsNow(): Double {
        val idle = (android.os.SystemClock.uptimeMillis() - turnAt) / 1000.0
        return turns * kotlin.math.exp(-idle.coerceAtLeast(0.0) / SMOOTH)
    }
    private val hystPx = a.dp(HYST_DP)

    /** The region under (d, angle), held on the current one until the finger is clearly past its line. */
    private fun regionAt(d: Float, angle: Double): Int {
        val seg = (((angle + step / 2) % 360) / step).toInt().coerceAtMost(items.size - 1)
        if (region < 0) return if (d > innerR + hystPx) seg else -1
        if (d < innerR - hystPx) return -1
        val off = kotlin.math.abs(((angle - region * step + 540) % 360) - 180)
        return if (off > step / 2 + HYST_DEG) seg else region
    }
    // The glow round the finger; it fades in with the first move, 62.5 ms.
    private var fx = x
    private var fy = y
    private var glow = 0f
    private var touched = false
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowCols = IntArray(GLOW_STOPS + 1)
    private val glowPos = FloatArray(GLOW_STOPS + 1)
    private val clip = Path()
    // The beam: a soft yellow spotlight on the ring at the finger's angle, not bound to the
    // segments. Screen-blended, so its strength is a colour, not an alpha; it fades in and
    // out with the yellow, 62.5 ms, as the finger leaves or enters the centre.
    private var beamDeg = 0f
    private var beam = 0f
    private var onRing = false
    private val screen = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
    private val multiply = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamCols = IntArray(BEAM_STOPS * 2 + 1)
    private val beamPos = FloatArray(BEAM_STOPS * 2 + 1)
    private val beamTurn = android.graphics.Matrix()
    // Each segment's yellow, 0..1; it moves toward its target over SEL_MS (125 ms), linear.
    private val lit = FloatArray(items.size)
    private var litAt = 0L            // last fade step; 0 while every segment is settled
    private val blend = android.animation.ArgbEvaluator()

    /**
     * Opening grows the disc out of the finger, 0 to full size over 250 ms, ease-out.
     * The scale is drawn, not a view property, so the tilt keeps its pivot on the centre.
     */
    private var grow = 0f
    private val growAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Motion.MS
        interpolator = Motion.IN
        addUpdateListener { grow = it.animatedValue as Float; invalidate(); shade.invalidate() }
    }

    // The disc's shadow on the page, the same wherever the pie opens. Two layers, both
    // multiplied over the page (a grey; white leaves it as it is), both centred on the disc
    // at rest: a soft, wide penumbra that stays put, and a darker, tighter umbra that slides
    // away from the pressed edge, up to UMBRA_SHIFT dp, so it shows just past the raised rim, as
    // under a disc tipped down on that side. Each fades by easing its grey to white.
    private fun shadowDisc(dark: Float, blurDp: Float, r: Float): android.graphics.Bitmap {
        val b = a.dp(blurDp) * 2; val s = (2 * (r + b)).toInt()
        return android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888).also { bm ->
            val c = Canvas(bm)
            c.drawColor(android.graphics.Color.WHITE)
            val g = (255 * (1 - dark)).toInt()
            c.drawCircle(s / 2f, s / 2f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(g, g, g)
                maskFilter = android.graphics.BlurMaskFilter(a.dp(blurDp), android.graphics.BlurMaskFilter.Blur.NORMAL)
            })
        }
    }
    private val penumbra by lazy { shadowDisc(PENUMBRA_DARK, PENUMBRA_BLUR, outerR) }
    private val umbra by lazy { shadowDisc(UMBRA_DARK, UMBRA_BLUR, outerR * UMBRA_SIZE) }
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
    }
    private val shadowMap = android.graphics.Matrix()
    private val fadeM = android.graphics.ColorMatrix()

    private val shade = object : View(a) {
        override fun hasOverlappingRendering() = false
        override fun onDraw(c: Canvas) {
            val k = grow * alphaNow()
            if (k <= 0f) return
            val o = 255 * (1 - k)
            fadeM.set(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f))
            shadowPaint.colorFilter = android.graphics.ColorMatrixColorFilter(fadeM)
            // Grows with the disc, about the same point.
            fun place(bm: android.graphics.Bitmap, x: Float, y: Float) {
                shadowMap.setTranslate(-bm.width / 2f, -bm.height / 2f)
                shadowMap.postScale(grow, grow)
                shadowMap.postTranslate(x, y)
                c.drawBitmap(bm, shadowMap, shadowPaint)
            }
            place(penumbra, cx, cy)
            val dx = if (touched) fx - cx else 0f; val dy = if (touched) fy - cy else 0f
            val d = hypot(dx, dy)
            val s = if (d > 1f) a.dp(UMBRA_SHIFT) * min(1f, d / outerR) / d else 0f
            // Away from the press: the far edge rises, so its shadow falls out past that rim.
            place(umbra, cx - dx * s, cy - dy * s)
        }
    }
    private fun alphaNow() = alpha

    /** The name pill and the shadow live beside the disc in its parent. */
    private fun dropSiblings() {
        (name.parent as? android.view.ViewGroup)?.removeView(name)
        (shade.parent as? android.view.ViewGroup)?.removeView(shade)
    }

    init {
        cameraDistance = 8000f * a.resources.displayMetrics.density
        contentDescription = items.joinToString { it.name }
    }

    // The hovered item's name, in the page pill's style: above the disc, else beside it on
    // the side away from the thumb (left, or right when [leftHanded]), else below. A sibling
    // view, so the disc's tilt does not skew it. Fades with the yellow, SEL_MS.
    private val name = a.label("", 13f, Theme.text(), bold = true).apply {
        setPadding(a.dpi(16), a.dpi(8), a.dpi(16), a.dpi(8))
        alpha = 0f
    }

    private fun showName() {
        val it = items.getOrNull(sel)
        if (it == null) { name.animate().alpha(0f).setDuration((SEL_MS * name.alpha).toLong()).setInterpolator(Motion.FADE).start(); return }
        name.text = it.name
        name.setTextColor(Theme.text())
        name.background = a.pill(Theme.panel(), 14f)
        name.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val w = name.measuredWidth; val h = name.measuredHeight
        val gap = a.dp(12f); val edge = a.dp(16f)
        val (x, y) = when {
            cy - outerR - gap - h >= edge -> cx - w / 2f to cy - outerR - gap - h
            !leftHanded && cx - outerR - gap - w >= edge -> cx - outerR - gap - w to cy - h / 2f
            leftHanded && cx + outerR + gap + w <= width - edge -> cx + outerR + gap to cy - h / 2f
            else -> cx - w / 2f to cy + outerR + gap
        }
        name.translationX = x; name.translationY = y
        name.animate().alpha(1f).setDuration((SEL_MS * (1 - name.alpha)).toLong()).setInterpolator(Motion.FADE).start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.listeners += onTheme
        growAnim.start()
        ratchet.start()
        ratchet.whoosh(true)
        (parent as? android.view.ViewGroup)?.let { p ->
            p.addView(name, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            // Under the disc, untilted: the shadow lies on the page.
            p.addView(shade, p.indexOfChild(this), android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Not removed here: this runs inside the parent's own removal of the disc.
        (parent as? android.view.ViewGroup ?: name.parent as? android.view.ViewGroup)?.post { dropSiblings() }
        growAnim.cancel()
        ratchet.stopLater()  // the close sound plays on past the fade
        Theme.listeners -= onTheme
    }

    /** The disc stays whole on screen; held near an edge, it sits off the finger. */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = outerR + a.dp(8f)
        cx = downX.coerceIn(m, (w - m).coerceAtLeast(m))
        cy = downY.coerceIn(m, (h - m).coerceAtLeast(m))
        pivotX = cx; pivotY = cy
    }

    /** Every move, the lift and a cancel of the touch that opened it. */
    fun touch(e: MotionEvent) {
        if (closing) return
        val dx = e.x - cx; val dy = e.y - cy
        val d = hypot(dx, dy)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 0 degrees at the top, rising clockwise.
                val angle = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360
                val r = regionAt(d, angle)
                fx = e.x; fy = e.y; touched = true
                if (r >= 0) {
                    // Speed over the last SPEED_MS of travel, not between two reports: two reports
                    // a millisecond apart read as a huge speed, and one tick leapt in pitch.
                    if (!turnDeg.isNaN()) travel += kotlin.math.abs(((angle - turnDeg + 540) % 360) - 180)
                    trail.addLast(e.eventTime to travel)
                    while (trail.size > 2 && e.eventTime - trail[1].first >= SPEED_MS) trail.removeFirst()
                    val (t0, a0) = trail.first()
                    if (e.eventTime - t0 >= 20) turns = (travel - a0) / 360 / ((e.eventTime - t0) / 1000.0)
                    turnDeg = angle; turnAt = e.eventTime
                } else { turnDeg = Double.NaN; turns = 0.0; trail.clear(); travel = 0.0 }
                onRing = r >= 0
                if (onRing) beamDeg = angle.toFloat()
                if (litAt == 0L) litAt = android.os.SystemClock.uptimeMillis()
                invalidate()
                if (r != region) {
                    region = r
                    // At most a semitone (a sixth of the range) from one tick to the next.
                    val f = ramp(turnsNow(), SLOW_TURNS, FULL_TURNS).coerceIn(lastF - 1.0 / 6, lastF + 1.0 / 6)
                    lastF = f
                    // Louder as it rises: quick ticks measured softer on a phone speaker.
                    ratchet.tick(pitch = pitch(f), gain = 1 + (BOOST - 1) * f)
                }
                val k = r.takeIf { it >= 0 && items[it].enabled } ?: -1
                if (k != sel) {
                    sel = k
                    if (litAt == 0L) litAt = android.os.SystemClock.uptimeMillis()  // the fade starts now
                    invalidate()
                    showName()
                }
                val f = min(1f, d / outerR) * TILT / d.coerceAtLeast(1f)
                // Measured: these signs push the pressed side away from the viewer.
                rotationY = dx * f
                rotationX = -dy * f
                shade.invalidate()
            }
            MotionEvent.ACTION_UP -> close(items.getOrNull(sel))
            MotionEvent.ACTION_CANCEL -> close(null)
        }
    }

    /** Fades out over 250 ms; the pick runs at once. */
    fun close(pick: Item? = null) {
        if (closing) return
        closing = true
        ratchet.whoosh(false)
        animate().cancel()
        name.animate().cancel()
        name.animate().alpha(0f).setDuration((Motion.MS * name.alpha).toLong()).setInterpolator(Motion.FADE).start()
        animate().alpha(0f).rotationX(0f).rotationY(0f).setDuration((Motion.MS * alpha).toLong()).setInterpolator(Motion.FADE)
            .setUpdateListener { shade.invalidate() }
            .withEndAction { dropSiblings(); done(this) }.start()
        pick?.act?.invoke()
    }

    // A sweep gradient round the centre: black (no change under Screen) far from the finger,
    // rising with an ease-in-out curve to BEAM_PEAK of the yellow at the finger's angle.
    // The centre disc, drawn after, covers its middle, so it lights only the ring.
    private fun drawBeam(c: Canvas) {
        // Screen cannot brighten the light theme's white disc, so in light mode the beam
        // multiplies instead, white (no change) rising to the yellow.
        val light = Theme.light
        beamPaint.xfermode = if (light) multiply else screen
        val span = BEAM_HALF / 360f
        for (i in 0..BEAM_STOPS * 2) {
            val u = (i - BEAM_STOPS) / BEAM_STOPS.toFloat()      // -1 .. 1 across the beam
            val t = 1 - kotlin.math.abs(u)
            val k = t * t * (3 - 2 * t) * BEAM_PEAK * beam
            fun ch(v: Int) = if (light) (255 - (255 - v) * k).toInt() else (v * k).toInt()
            beamCols[i] = android.graphics.Color.rgb(
                ch(android.graphics.Color.red(ACCENT)), ch(android.graphics.Color.green(ACCENT)), ch(android.graphics.Color.blue(ACCENT)))
            beamPos[i] = 0.5f + u * span
        }
        // The gradient's 0.5 sits at the finger: 0 degrees is the top, a sweep's 0 is +x.
        val sweep = android.graphics.SweepGradient(cx, cy, beamCols, beamPos)
        beamTurn.setRotate(beamDeg - 90f - 180f, cx, cy)
        sweep.setLocalMatrix(beamTurn)
        beamPaint.shader = sweep
        c.drawOval(oval, beamPaint)
    }

    // A soft round glow on the finger, over the centre disc as well as the ring, so the
    // beam's light carries into the centre; the centre itself never fills yellow. The same
    // eased fade and blend as the beam, kept inside the disc.
    private fun drawGlow(c: Canvas) {
        val light = Theme.light
        glowPaint.xfermode = if (light) multiply else screen
        // Inverse square, as light from a point falls off: 1 / (1 + (d / GLOW_CORE)²), shifted
        // and scaled so it is full at the finger and nothing at the rim.
        val edge = 1f / (1f + (1f / GLOW_CORE) * (1f / GLOW_CORE))
        for (i in 0..GLOW_STOPS) {
            val u = i / GLOW_STOPS.toFloat()          // 0 at the finger, 1 at the rim
            val q = u / GLOW_CORE
            val k = ((1f / (1f + q * q) - edge) / (1f - edge)) * GLOW_PEAK * glow
            fun ch(v: Int) = if (light) (255 - (255 - v) * k).toInt() else (v * k).toInt()
            glowCols[i] = android.graphics.Color.rgb(
                ch(android.graphics.Color.red(ACCENT)), ch(android.graphics.Color.green(ACCENT)), ch(android.graphics.Color.blue(ACCENT)))
            glowPos[i] = u
        }
        val r = a.dp(GLOW_DP)
        glowPaint.shader = android.graphics.RadialGradient(fx, fy, r, glowCols, glowPos, android.graphics.Shader.TileMode.CLAMP)
        c.save()
        clip.reset(); clip.addOval(oval, Path.Direction.CW)
        c.clipPath(clip)
        c.drawCircle(fx, fy, r, glowPaint)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        if (grow <= 0f) return
        c.scale(grow, grow, downX, downY)
        oval.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        inner.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        fill.color = Theme.popup()
        c.drawOval(oval, fill)
        // Step every segment's yellow toward its target by the time since the last frame.
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (litAt == 0L) 0f else (now - litAt) / 62.5f       // the beam and glow
        val dtSel = dt * 62.5f / SEL_MS                                // the yellow, slower
        litAt = now
        var moving = false
        for (k in lit.indices) {
            val want = if (k == sel) 1f else 0f
            lit[k] = if (lit[k] < want) minOf(want, lit[k] + dtSel) else maxOf(want, lit[k] - dtSel)
            if (lit[k] != want) moving = true
        }
        val beamWant = if (onRing) 1f else 0f
        beam = if (beam < beamWant) minOf(beamWant, beam + dt) else maxOf(beamWant, beam - dt)
        if (beam != beamWant) moving = true
        if (touched && glow < 1f) { glow = minOf(1f, glow + dt); moving = true }
        if (moving) postInvalidateOnAnimation() else litAt = 0L
        // A lit segment: a ring sector, centred on its angle.
        for (k in lit.indices) {
            if (lit[k] <= 0f) continue
            val start = -90f + k * step - step / 2
            wedge.reset()
            wedge.arcTo(oval, start, step)
            wedge.arcTo(inner, start + step, -step)
            wedge.close()
            fill.color = ACCENT
            fill.alpha = (255 * lit[k]).toInt()
            c.drawPath(wedge, fill)
            fill.alpha = 255
        }
        if (beam > 0f) drawBeam(c)
        edge.color = Theme.line()
        for (k in items.indices) {
            val r = Math.toRadians((-90f + k * step - step / 2).toDouble())
            c.drawLine(cx + innerR * cos(r).toFloat(), cy + innerR * sin(r).toFloat(), cx + outerR * cos(r).toFloat(), cy + outerR * sin(r).toFloat(), edge)
        }
        fill.color = Theme.panel()
        c.drawOval(inner, fill)
        c.drawOval(inner, edge)
        if (glow > 0f) drawGlow(c)
        edge.color = Theme.outline()
        c.drawOval(oval, edge)
        val mid = (innerR + outerR) / 2
        items.forEachIndexed { k, it ->
            val r = Math.toRadians((-90f + k * step).toDouble())
            val ix = (cx + mid * cos(r)).toInt() - iconPx / 2
            val iy = (cy + mid * sin(r)).toInt() - iconPx / 2
            val d = icons[k]
            d.setBounds(ix, iy, ix + iconPx, iy + iconPx)
            d.setTint(if (it.enabled) blend.evaluate(lit[k], Theme.text(), BG) as Int else Theme.sub())
            d.alpha = if (it.enabled) 255 else 90
            d.draw(c)
        }
    }
}
