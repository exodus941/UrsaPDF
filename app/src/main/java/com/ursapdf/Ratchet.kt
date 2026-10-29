package com.ursapdf

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The ratchet tick from tools/ratchet-tick.js, made the same way: half a closed hi-hat
 * (six square waves at 600 Hz times 2, 3, 4.16, 5.43, 6.79 and 8.21) and half white
 * noise, decaying in 4 and 5 ms, through a high-pass at 8.5 kHz and a band-pass at
 * 11.5 kHz (Q 0.5), at a gain of 0.25. [tick] plays one, never closer than 35 ms apart.
 */
class Ratchet {
    private val rate = 48000
    private val track: AudioTrack?
    private var last = 0L

    init {
        val n = (rate * 0.03).toInt()
        val hat = doubleArrayOf(2.0, 3.0, 4.16, 5.43, 6.79, 8.21)
        val rnd = java.util.Random(1)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            var metal = 0.0
            for (r in hat) metal += if (sin(2 * PI * 600 * r * t) >= 0) 1.0 else -1.0
            val attack = minOf(1.0, t / 0.0005)
            attack * (0.5 * (metal / hat.size) * exp(-t / 0.004) + 0.5 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.005))
        }
        val peak = x.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        for (i in x.indices) x[i] /= peak
        // Web Audio's high-pass Q is in dB: its default 1 dB is 1.122 as a plain Q.
        biquad(x, 8500.0, 1.122, high = true)
        biquad(x, 11500.0, 0.5, high = false)
        val pcm = ShortArray(n) { (x[it] * 0.25 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort() }
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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

    fun tick() {
        val t = track ?: return
        val now = SystemClock.uptimeMillis()
        if (now - last < 35) return
        last = now
        try { t.stop(); t.reloadStaticData(); t.play() } catch (_: Exception) {}
    }

    fun release() = track?.release()
}
