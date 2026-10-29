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
    private val rotary: Boolean, private val done: (PieMenu) -> Unit,
) : View(a) {

    /** [enabled] false draws the segment dimmed and it cannot be picked. */
    class Item(val icon: Int, val name: String, val enabled: Boolean = true, val act: () -> Unit)

    companion object {
        const val TILT = 20f
        /** Ratchet: finger travel around the centre per tick, in dp; about four to a segment at mid ring. */
        const val NOTCH_DP = 20f
        /** Ratchet: the finger's speed is smoothed over this long (seconds), so uneven touch reports even out. */
        const val SMOOTH = 0.06
        /** Ratchet: at most this many ticks a second, one per 80 ms; faster turns hold this rate, evenly. Set by the user (half of the first 25); do not change unasked. */
        const val MAX_RATE = 12.5
        /**
         * Without the ratchet: how long the finger must stay in a segment, or the centre,
         * before it ticks. 62.5 ms; Android's timers count whole milliseconds.
         */
        const val DWELL = 63L
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
    // With [rotary], a rotary dial's ratchet. The finger's travel around the centre (not
    // in or out, not in the centre) is smoothed into one speed, and that speed drives an
    // even ticker: a tick every NOTCH_DP of travel, each timed to the millisecond it falls
    // due rather than to whichever touch report crossed a notch, which bunched them.
    // Without it, one tick for each segment, or the centre, the finger stays in for DWELL;
    // one only passed through stays silent.
    private val ratchet = Ratchet()
    private var lastDeg = Double.NaN  // finger angle at the last move on the ring; NaN in the centre
    private val notchPx = a.dp(NOTCH_DP)
    private var pendingPx = 0f        // travel around the centre since the last frame
    private var speed = 0.0           // smoothed, px per second
    private var phase = 0.0           // fraction of the way to the next tick
    private var lastFrame = 0L
    private var due = false           // a tick is posted for later in this frame
    private val dueTick = Runnable { due = false; phase -= 1; if (!closing) ratchet.tick(gap = 0) }
    private val frame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (closing) return
            android.view.Choreographer.getInstance().postFrameCallback(this)
            if (lastFrame == 0L) { lastFrame = t; return }
            val dt = ((t - lastFrame) / 1e9).coerceIn(0.001, 0.1)
            lastFrame = t
            speed += (pendingPx / dt - speed) * (1 - kotlin.math.exp(-dt / SMOOTH))
            pendingPx = 0f
            val rate = min(speed / notchPx, MAX_RATE)
            if (rate < 0.5) return            // still, or as good as: the ratchet rests
            phase += rate * dt
            if (due) return
            if (phase >= 1) { phase -= 1; ratchet.tick(gap = 0); return }
            // The next notch falls before the next frame: post it for its own moment.
            val wait = (1 - phase) / rate
            if (wait < dt * 1.5) { due = true; postDelayed(dueTick, (wait * 1000).toLong()) }
        }
    }
    private var region = -1           // under the finger: -1 the centre, else a segment, dimmed ones too
    private var ticked = -1           // the region the last tick was for
    private val settle = Runnable { if (!closing && region != ticked) { ticked = region; ratchet.tick() } }
    // Each segment's yellow, 0..1; it moves toward its target over 62.5 ms, linear.
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
        addUpdateListener { grow = it.animatedValue as Float; invalidate() }
    }

    init {
        cameraDistance = 8000f * a.resources.displayMetrics.density
        contentDescription = items.joinToString { it.name }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.listeners += onTheme
        growAnim.start()
        if (rotary) android.view.Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        growAnim.cancel()
        removeCallbacks(settle)
        removeCallbacks(dueTick)
        android.view.Choreographer.getInstance().removeFrameCallback(frame)
        ratchet.release()
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
                val r = if (d < innerR) -1 else (((angle + step / 2) % 360) / step).toInt().coerceAtMost(items.size - 1)
                if (!rotary) {
                    if (r != region) { region = r; removeCallbacks(settle); postDelayed(settle, DWELL) }
                } else if (r < 0) lastDeg = Double.NaN else {
                    // Out of the centre onto the ring: one tick at once, and the ratchet starts fresh.
                    if (lastDeg.isNaN()) { ratchet.tick(); phase = 0.0 } else {
                        val turn = ((angle - lastDeg + 540) % 360) - 180  // degrees, the short way round
                        pendingPx += (kotlin.math.abs(turn) * Math.PI / 180 * d).toFloat()
                    }
                    lastDeg = angle
                }
                val k = r.takeIf { it >= 0 && items[it].enabled } ?: -1
                if (k != sel) {
                    sel = k
                    if (litAt == 0L) litAt = android.os.SystemClock.uptimeMillis()  // the fade starts now
                    invalidate()
                }
                val f = min(1f, d / outerR) * TILT / d.coerceAtLeast(1f)
                // Measured: these signs push the pressed side away from the viewer.
                rotationY = dx * f
                rotationX = -dy * f
            }
            MotionEvent.ACTION_UP -> close(items.getOrNull(sel))
            MotionEvent.ACTION_CANCEL -> close(null)
        }
    }

    /** Fades out over 250 ms; the pick runs at once. */
    fun close(pick: Item? = null) {
        if (closing) return
        closing = true
        animate().cancel()
        animate().alpha(0f).rotationX(0f).rotationY(0f).setDuration((Motion.MS * alpha).toLong()).setInterpolator(Motion.FADE)
            .withEndAction { done(this) }.start()
        pick?.act?.invoke()
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
        val dt = if (litAt == 0L) 0f else (now - litAt) / 62.5f
        litAt = now
        var moving = false
        for (k in lit.indices) {
            val want = if (k == sel) 1f else 0f
            lit[k] = if (lit[k] < want) minOf(want, lit[k] + dt) else maxOf(want, lit[k] - dt)
            if (lit[k] != want) moving = true
        }
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
        edge.color = Theme.line()
        for (k in items.indices) {
            val r = Math.toRadians((-90f + k * step - step / 2).toDouble())
            c.drawLine(cx + innerR * cos(r).toFloat(), cy + innerR * sin(r).toFloat(), cx + outerR * cos(r).toFloat(), cy + outerR * sin(r).toFloat(), edge)
        }
        fill.color = Theme.panel()
        c.drawOval(inner, fill)
        c.drawOval(inner, edge)
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
