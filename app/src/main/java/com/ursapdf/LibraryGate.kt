package com.ursapdf

import android.app.Activity
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen "Downloading required libraries" over the home list or the reader, on phones
 * that need the engine: first launch, and whenever a newer engine is published. It cannot
 * be dismissed; a failed download offers Retry.
 */
class LibraryGate(private val a: Activity, private val host: FrameLayout, private val onReady: () -> Unit) {

    private var view: FrameLayout? = null
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var retry: TextView
    val showing get() = view != null

    /**
     * Load the engine, or show the gate until it is downloaded. With [update], also look
     * for a newer engine in the background and show the gate to fetch it.
     */
    fun check(update: Boolean) {
        if (!Libraries.needed) return
        if (!Libraries.load(a)) { start(); return }
        if (!update) return
        val app = a.applicationContext
        Thread {
            val newer = try { Libraries.latest()?.takeIf { it.version > Libraries.installedVersion(app) } } catch (e: Exception) { null }
            if (newer != null) a.runOnUiThread { if (!a.isDestroyed) start(newer) }
        }.start()
    }

    /** Show the gate and fetch [asset], or look it up first when null. */
    fun start(asset: Libraries.Asset? = null) {
        if (view == null) build()
        retry.fade(false, gone = false)
        status.text = "Connecting…"
        bar.isIndeterminate = true
        val app = a.applicationContext
        Thread {
            val err = try {
                val found = asset ?: Libraries.latest() ?: error("No engine for this phone in the latest release")
                Libraries.install(app, found) { done, total ->
                    a.runOnUiThread {
                        if (total > 0) { bar.isIndeterminate = false; bar.progress = (done * 1000 / total).toInt() }
                        status.text = if (total > 0) "${Formatter.formatShortFileSize(a, done)} of ${Formatter.formatShortFileSize(a, total)}"
                            else Formatter.formatShortFileSize(a, done)
                    }
                }
                null
            } catch (e: java.io.IOException) {
                "Couldn’t reach GitHub. Check the connection and try again."
            } catch (e: Exception) {
                "Couldn’t download: ${e.message ?: "the download failed"}."
            }
            a.runOnUiThread {
                if (err != null) fail(err)
                else {
                    Libraries.load(app)
                    close()
                    onReady()
                }
            }
        }.start()
    }

    private fun fail(why: String) {
        bar.isIndeterminate = false; bar.progress = 0
        status.text = why
        retry.fade(true)
    }

    private fun build() {
        val v = FrameLayout(a).apply { isClickable = true; setBackgroundColor(Theme.bg()) }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dpi(32), 0, a.dpi(32), 0)
        }
        col.addView(a.label("Downloading required libraries", 20f, Theme.text(), bold = true).apply { gravity = Gravity.CENTER })
        col.addView(a.label("Search and form filling on this phone need a one-time download.", 15f, Theme.sub()).apply {
            gravity = Gravity.CENTER; setPadding(0, a.dpi(8), 0, a.dpi(24))
        })
        bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.accentInk())
            indeterminateTintList = progressTintList
        }
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, a.dpi(8)))
        status = a.label("", 14f, Theme.sub()).apply { gravity = Gravity.CENTER; setPadding(0, a.dpi(12), 0, 0) }
        col.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        retry = a.label("Retry", 15f, BG, bold = true).apply {
            gravity = Gravity.CENTER
            background = a.pill(ACCENT, 14f)
            foreground = a.ripple()
            setPadding(a.dpi(24), a.dpi(12), a.dpi(24), a.dpi(12))
            setOnClickListener { start() }
            visibility = View.INVISIBLE  // holds its space, so the text above stays put when it fades in
        }
        col.addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = a.dpi(24) })
        v.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        host.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view = v
    }

    private fun close() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).withEndAction { host.removeView(v) }.start()
    }
}
