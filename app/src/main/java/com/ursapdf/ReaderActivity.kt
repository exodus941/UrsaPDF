package com.ursapdf

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class ReaderActivity : Activity(), PdfView.Listener {

    private lateinit var uri: Uri
    private lateinit var root: FrameLayout
    private lateinit var pdf: PdfView
    private lateinit var ui: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var badge: TextView
    private lateinit var bottom: LinearLayout
    private lateinit var top: LinearLayout
    private var spinner: View? = null
    private var strip: ThumbStrip? = null
    private var cropper: CropOverlay? = null
    private var eng: PdfEngine? = null
    private var state: DocState? = null
    private var insets = IntArray(4)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        uri = intent.data ?: run { finish(); return }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // ACTION_VIEW grants are temporary; the file still opens now.
        }
        buildUi()
        window.immersive()  // needs the decor view that setContentView creates

        val app = applicationContext
        Thread {
            try {
                val e = PdfEngine.open(app, uri)
                runOnUiThread { if (isDestroyed) e.close() else opened(e) }
            } catch (ex: Exception) {
                runOnUiThread {
                    val msg = if (ex is SecurityException && ex.message?.contains("password", true) == true)
                        "Password-protected PDFs are not supported" else "Cannot open this PDF"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (ex is SecurityException || ex is java.io.FileNotFoundException) Store.remove(this, uri.toString())
                    finish()
                }
            }
        }.start()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        pdf = PdfView(this).also { it.listener = this }
        root.addView(pdf, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        spinner = ProgressBar(this).also {
            root.addView(it, FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.CENTER))
        }

        ui = FrameLayout(this).apply { visibility = View.GONE }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PANEL)
            isClickable = true
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            background = ripple()
            setPadding(dpi(14), dpi(14), dpi(14), dpi(14))
            contentDescription = "Back"
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(dpi(52), dpi(52)))
        titleView = label("", 16f, 0xFFFFFFFF.toInt()).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(label("View Cropper", 14f, ACCENT, bold = true).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_crop, 0, 0, 0)
            compoundDrawablePadding = dpi(6)
            gravity = Gravity.CENTER_VERTICAL
            background = ripple()
            setPadding(dpi(12), dpi(14), dpi(16), dpi(14))
            setOnClickListener { openCropper() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(52)))
        ui.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        badge = label("", 13f, 0xFFFFFFFF.toInt(), bold = true).apply {
            background = pill(0xE0000000.toInt(), 14f)
            setPadding(dpi(14), dpi(6), dpi(14), dpi(6))
        }
        bottom.addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dpi(10) })
        ui.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, ins ->
            insets = ins.safe()
            top.setPadding(insets[0], insets[1], insets[2], 0)
            strip?.let {
                it.setPadding(insets[0], 0, insets[2], insets[3])
                it.layoutParams.height = dpi(124) + insets[3]
                it.requestLayout()
            }
            ins
        }
        setContentView(root)
    }

    private fun opened(e: PdfEngine) {
        eng = e
        root.removeView(spinner); spinner = null
        val key = uri.toString()
        val s = Store.get(this, key) ?: DocState(key, displayName())
        s.pages = e.pageCount
        s.opened = System.currentTimeMillis()
        state = s
        e.crop.set(s.crop)
        titleView.text = s.name
        pdf.setDocument(e, s.page.coerceIn(0, e.pageCount - 1), s.offset, s.x, s.zoom)

        val st = ThumbStrip(this, e) { pdf.jumpTo(it) }.apply {
            setBackgroundColor(PANEL)
            isClickable = true
            setPadding(insets[0], 0, insets[2], insets[3])
        }
        strip = st
        bottom.addView(st, LinearLayout.LayoutParams(MATCH_PARENT, dpi(124) + insets[3]))
        updatePill()
        Store.put(this, s)
    }

    private fun displayName(): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }

    private fun updatePill() {
        val e = eng ?: return
        badge.text = "Page ${pdf.currentPage + 1} of ${e.pageCount}"
        strip?.current = pdf.currentPage
    }

    override fun onPageChanged(page: Int) = updatePill()

    override fun onTap(x: Float, y: Float) {
        if (eng == null) return
        if (ui.visibility == View.VISIBLE) { hideUi(); return }
        val w = pdf.width; val h = pdf.height
        if (x > w * 0.25f && x < w * 0.75f && y > h * 0.25f && y < h * 0.75f) showUi()
    }

    private fun showUi() {
        updatePill()
        ui.alpha = 0f
        ui.visibility = View.VISIBLE
        ui.animate().alpha(1f).setDuration(120).start()
        strip?.post { strip?.centerOn(pdf.currentPage) }
    }

    private fun hideUi() {
        ui.animate().alpha(0f).setDuration(120).withEndAction { ui.visibility = View.GONE }.start()
        eng?.submit(PdfEngine.Q_THUMB, emptyList())
    }

    private fun openCropper() {
        val e = eng ?: return
        hideUi()
        val c = CropOverlay(this, e, pdf.currentPage, insets) { r -> closeCropper(r) }
        cropper = c
        root.addView(c, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pdf.visibility = View.INVISIBLE
    }

    private fun closeCropper(r: RectF?) {
        val e = eng ?: return
        cropper?.let { root.removeView(it) }
        cropper = null
        pdf.visibility = View.VISIBLE
        if (r != null && r != e.crop) {
            e.setCrop(r)
            pdf.onCropChanged()
            save()
        }
    }

    private fun save() {
        val s = state ?: return
        val e = eng ?: return
        val p = pdf.position()
        s.page = p[0].toInt(); s.offset = p[1]; s.x = p[2]; s.zoom = pdf.zoom
        s.crop.set(e.crop)
        s.opened = System.currentTimeMillis()
        Store.put(this, s)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.immersive()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        eng?.close()
        eng = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cropper != null) closeCropper(null) else goHome()
    }

    /** Opened from another app, the reader is the task root; Back still lands on the home list. */
    private fun goHome() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
