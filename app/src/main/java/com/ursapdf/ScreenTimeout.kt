package com.ursapdf

import android.content.Context

/** How long the reader keeps the screen on after the last touch. 0 means no timeout. */
object ScreenTimeout {
    val CHOICES = listOf(
        30_000L to "30 seconds",
        60_000L to "1 minute",
        120_000L to "2 minutes",
        180_000L to "3 minutes",
        300_000L to "5 minutes",
        0L to "No timeout",
    )
    private const val DEFAULT = 120_000L

    fun get(c: Context) = c.getSharedPreferences("ursa", Context.MODE_PRIVATE).getLong("screenTimeout", DEFAULT)
    fun set(c: Context, ms: Long) = c.getSharedPreferences("ursa", Context.MODE_PRIVATE).edit().putLong("screenTimeout", ms).apply()
}
