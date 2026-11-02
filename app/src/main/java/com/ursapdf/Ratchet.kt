package com.ursapdf

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ratchet tick from tools/ratchet-tick.js, made the same way: half a closed hi-hat
 * (six square waves at 600 Hz times 2, 3, 4.16, 5.43, 6.79 and 8.21) and half white
 * noise, decaying in 4 and 5 ms, through a high-pass at 8.5 kHz and a band-pass at
 * 11.5 kHz (Q 0.5), at a gain of 0.25.
 *
 * Each pitch, a semitone apart up to half an octave, is made on its own: every frequency
 * scaled and every decay shortened. Speeding up one recording instead sent its top end
 * past the output's limit, where the resampler folded it back down as tones that slid
 * the wrong way, and on a phone speaker that sounded like a flanger. Every pitch is
 * scaled to the same loudness (RMS) as the lowest.
 *
 * Playback is one low-latency stream, open while [start]ed, into which [tick] mixes a
 * tick at once. A clip restarted for every tick waited on the track's start and had its
 * attack faded in by the mixer, so quick ticks came late and quieter.
 */
class Ratchet {
    private val rate = 48000
    private val sounds: List<FloatArray>
    private val voices = ArrayList<IntArray>()  // [sound, position] of each tick playing
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    companion object {
        /** Semitones above the base, 0..6: half an octave. */
        const val STEPS = 6
        /** Frames mixed per write: 1 ms. */
        const val CHUNK = 48
        /** The open and close sound's loudness against a tick: 33%, set by the user. */
        const val WHOOSH = 0.33
        /** The open and close sound's length against 340 ms: 1.5 and 2, set by the user. */
        const val OPEN_K = 1.5
        const val CLOSE_K = 2.0
        /** Made once, about 23 ms for all seven; [warm] starts it off the main thread. */
        val shared by lazy { Ratchet() }
        fun warm() { Thread { shared }.start() }
    }

    init {
        val raw = (0..STEPS).map { make(Math.pow(2.0, it / 12.0)) }
        // Same energy, not same RMS: a higher tick is shorter, and at equal RMS it measured softer.
        val ref = energy(raw[0])
        val ticks = raw.map { s -> val k = sqrt(ref / energy(s)).toFloat(); FloatArray(s.size) { s[it] * k } }
        // The pie's open and close sound, at WHOOSH of the tick's loudness (RMS over its sounding part).
        val target = loud(ticks[0]) * WHOOSH
        val swish = listOf(makeWhoosh(true), makeWhoosh(false)).map { s -> val k = (target / loud(s)).toFloat(); FloatArray(s.size) { s[it] * k } }
        sounds = ticks + swish
    }

    /** RMS over the samples that sound, as the demo page measured it. */
    private fun loud(x: FloatArray): Double {
        var e = 0.0; var k = 0
        for (v in x) { e += v.toDouble() * v; if (kotlin.math.abs(v) > 1e-4f) k++ }
        return sqrt(e / maxOf(1, k)).takeIf { it > 0 } ?: 1.0
    }

    /**
     * "Air into Gust", as chosen on the demo page: white noise through a band-pass (Q 1.6)
     * sweeping 300 Hz to 3 kHz on open, back down on close, exponentially over 95% of its
     * 340 ms, stretched by OPEN_K or CLOSE_K (510 and 680 ms, set by the user). It swells to its
     * peak in a third of the 340 ms, stretched alike, and dies away by the end, both exponential.
     */
    private fun makeWhoosh(open: Boolean): FloatArray {
        val k = if (open) OPEN_K else CLOSE_K; val dur = 0.34 * k; val rise = 0.12 * k; val n = (rate * dur).toInt()
        val lo = 300.0; val hi = 3000.0; val q = 1.6
        val rnd = java.util.Random(2)
        val out = FloatArray(n)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val u = (t / (dur * 0.95)).coerceAtMost(1.0)
            val f = if (open) lo * Math.pow(hi / lo, u) else hi * Math.pow(lo / hi, u)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * q); val cw = cos(w)
            val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
            val xin = rnd.nextDouble() * 2 - 1
            val y = (alpha * xin - alpha * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = xin; y2 = y1; y1 = y
            val g = if (t < rise) 0.0001 * Math.pow(0.6 / 0.0001, t / rise) else 0.6 * Math.pow(0.0001 / 0.6, (t - rise) / (dur - rise))
            out[i] = (y * g).toFloat()
        }
        return out
    }

    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }.takeIf { it > 0 } ?: 1.0

    private fun make(f: Double): FloatArray {
        val n = (rate * 0.03 / f).toInt()
        val hat = doubleArrayOf(2.0, 3.0, 4.16, 5.43, 6.79, 8.21)
        val rnd = java.util.Random(1)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            var metal = 0.0
            for (r in hat) metal += if (sin(2 * PI * 600 * f * r * t) >= 0) 1.0 else -1.0
            val attack = minOf(1.0, t * f / 0.0005)
            attack * (0.5 * (metal / hat.size) * exp(-t * f / 0.004) + 0.5 * (rnd.nextDouble() * 2 - 1) * exp(-t * f / 0.005))
        }
        val peak = x.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        for (i in x.indices) x[i] /= peak
        // Web Audio's high-pass Q is in dB: its default 1 dB is 1.122 as a plain Q.
        biquad(x, 8500.0 * f, 1.122, high = true)
        biquad(x, 11500.0 * f, 0.5, high = false)
        return FloatArray(n) { (x[it] * 0.25).toFloat() }
    }

    /** Robert Bristow-Johnson's cookbook filters, as Web Audio uses: high-pass, or band-pass with 0 dB peak. */
    private fun biquad(x: DoubleArray, f: Double, q: Double, high: Boolean) {
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val cw = cos(w)
        val b0: Double; val b1: Double; val b2: Double
        if (high) { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 } else { b0 = alpha; b1 = 0.0; b2 = -alpha }
        val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val y = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y
            x[i] = y
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    /** Closes the stream once a sound just started has played out (the close sound is 680 ms). */
    fun stopLater() { main.removeCallbacks(stopper); main.postDelayed(stopper, 750) }

    /** The pie's open or close sound. */
    fun whoosh(open: Boolean) {
        if (!running) return
        synchronized(voices) { voices += intArrayOf(STEPS + if (open) 1 else 2, 0, 1000) }
    }

    /** Opens the stream; silence until a tick. */
    fun start() {
        main.removeCallbacks(stopper)
        if (running) return
        val t = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(min)
                .build()
        } catch (_: Exception) { return }
        track = t
        running = true
        t.play()
        thread = Thread {
            val buf = FloatArray(CHUNK)
            while (running) {
                java.util.Arrays.fill(buf, 0f)
                synchronized(voices) {
                    val it = voices.iterator()
                    while (it.hasNext()) {
                        val v = it.next(); val s = sounds[v[0]]
                        val g = v[2] / 1000f
                        var p = v[1]
                        for (i in 0 until CHUNK) { if (p >= s.size) break; buf[i] += s[p++] * g }
                        if (p >= s.size) it.remove() else v[1] = p
                    }
                }
                if (t.write(buf, 0, CHUNK, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        }.apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Closes the stream. */
    fun stop() {
        main.removeCallbacks(stopper)
        running = false
        thread?.join(100); thread = null
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
        synchronized(voices) { voices.clear() }
    }

    /** [pitch]: 1 as made, up to √2; the nearest semitone plays. [gain]: its volume, 1 as made. */
    fun tick(pitch: Double = 1.0, gain: Double = 1.0) {
        if (!running) return
        val step = (12 * ln(pitch) / ln(2.0)).roundToInt().coerceIn(0, STEPS)
        synchronized(voices) { if (voices.size < 4) voices += intArrayOf(step, 0, (gain * 1000).toInt()) }
    }
}
