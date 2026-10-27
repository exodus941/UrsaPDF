package com.ursapdf

import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * %ease250%, the app's motion preset: 250 ms, ease-out sliding in, ease-in sliding
 * out. Opacity fades use [FADE]: an ease-out fade reaches 80% in 60 ms and reads as
 * a pop.
 */
object Motion {
    const val MS = 250L
    val IN = PathInterpolator(0f, 0f, 0.2f, 1f)
    val OUT = PathInterpolator(0.42f, 0f, 1f, 1f)
    val FADE = LinearInterpolator()
}
