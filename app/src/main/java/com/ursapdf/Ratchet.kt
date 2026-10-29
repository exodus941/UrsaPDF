package com.ursapdf

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The ratchet tick from tools/ratchet-tick.js, made the same way: half a closed hi-hat
 * (six square waves at 600 Hz times 2, 3, 4.16, 5.43, 6.79 and 8.21) and half white
 * noise, decaying in 4 and 5 ms, through a high-pass at 8.5 kHz and a band-pass at
 * 11.5 kHz (Q 0.5), at a gain of 0.25. [tick] plays one, never closer than 35 ms apart.
 *
 * Each pitch, a semitone apart up to half an octave, is made on its own: every frequency
 * scaled and every decay shortened. Speeding up one recording instead sent its top end
 * past the output's limit, where the resampler folded it back down as tones that slid
 * the wrong way, and on a phone speaker that sounded like a flanger.
 */
class Ratchet {
    private val rate = 48000
    private val tracks: List<AudioTrack?>
    private var last = 0L

    companion object {
        /** Semitones above the base, 0..6: half an octave. */
        const val STEPS = 6
        /** Made once, about 23 ms for all seven; [warm] starts it off the main thread. */
        val shared by lazy { Ratchet() }
        fun warm() { Thread { shared }.start() }
    }

    init {
        tracks = (0..STEPS).map { make(Math.pow(2.0, it / 12.0)) }
    }

    private fun make(f: Double): AudioTrack? {
        val n = (rate * 0.03).toInt()
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
        val pcm = ShortArray(n) { (x[it] * 0.25 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort() }
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build().also { it.write(pcm, 0, n) }
        } catch (_: Exception) { null }
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

    /**
     * [gap]: the least time since the last tick, in ms; a tick sooner is dropped.
     * [pitch]: 1 as made, up to √2; the nearest semitone plays.
     */
    fun tick(gap: Long = 35, pitch: Double = 1.0) {
        val step = (12 * ln(pitch) / ln(2.0)).roundToInt().coerceIn(0, STEPS)
        val t = tracks[step] ?: return
        val now = SystemClock.uptimeMillis()
        if (now - last < gap) return
        last = now
        try { t.stop(); t.reloadStaticData(); t.play() } catch (_: Exception) {}
    }
}
