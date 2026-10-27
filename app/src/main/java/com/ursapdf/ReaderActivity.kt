package com.ursapdf

import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
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
    private lateinit var invertBtn: ImageView
    private var invertAnim: ValueAnimator? = null
    private var uiShown = false
    private val easeOut = PathInterpolator(0f, 0f, 0.2f, 1f)
    private val easeIn = PathInterpolator(0.42f, 0f, 1f, 1f)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        uri = intent.data ?: run { finish(); return }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // ACTION_VIEW grants are temporary; the file still opens now.
        }
        Theme.load(this)
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

        // The chrome stays laid out; while hidden it sits just off screen, so it never
        // flashes in place before sliding.
        ui = FrameLayout(this)
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.panel())
            isClickable = true
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Theme.text())
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            contentDescription = "Back"
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        titleView = label("", 16f, Theme.text()).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(label("Page Cropper", 14f, Theme.accentInk(), bold = true).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_crop)!!.mutate().apply { setTint(Theme.accentInk()) }, null, null, null)
            compoundDrawablePadding = dpi(8)
            gravity = Gravity.CENTER_VERTICAL
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(8), dpi(12))
            setOnClickListener { openCropper() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(48)))
        invertBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_invert)
            setColorFilter(Theme.text())
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            contentDescription = "Invert Colours"
            setOnClickListener { toggleInvert() }
        }
        top.addView(invertBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(4) })
        ui.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        badge = label("", 13f, Theme.text(), bold = true).apply {
            background = pill(Theme.panel(), 14f)
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
        }
        bottom.addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dpi(12) })
        ui.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        top.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!uiShown) v.translationY = -v.height.toFloat() }
        bottom.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!uiShown) v.translationY = v.height.toFloat() }

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
        e.invert = if (s.invert) 1f else 0f
        paintInvertButton()
        titleView.text = s.name
        pdf.setDocument(e, s.page.coerceIn(0, e.pageCount - 1), s.offset, s.x, s.zoom)

        val st = ThumbStrip(this, e) { pdf.jumpTo(it) }.apply {
            setBackgroundColor(Theme.panel())
            isClickable = true
            visibility = View.INVISIBLE
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
        if (uiShown) { hideUi(); return }
        val w = pdf.width; val h = pdf.height
        if (x > w * 0.25f && x < w * 0.75f && y > h * 0.25f && y < h * 0.75f) showUi()
    }

    override fun onVerticalSwipe() {
        if (eng == null) return
        if (uiShown) hideUi() else showUi()
    }

    /** Top bar drops in from above, strip rises from below: 250 ms, ease-out. */
    private fun showUi() {
        if (uiShown) return
        uiShown = true
        updatePill()
        strip?.visibility = View.VISIBLE
        strip?.centerOn(pdf.currentPage)
        top.animate().translationY(0f).setDuration(250).setInterpolator(easeOut).start()
        bottom.animate().translationY(0f).setDuration(250).setInterpolator(easeOut).start()
    }

    /** Both slide back out the way they came: 250 ms, ease-in. */
    private fun hideUi() {
        if (!uiShown) return
        uiShown = false
        top.animate().translationY(-top.height.toFloat()).setDuration(250).setInterpolator(easeIn).start()
        bottom.animate().translationY(bottom.height.toFloat()).setDuration(250).setInterpolator(easeIn)
            .withEndAction { if (!uiShown) strip?.visibility = View.INVISIBLE }.start()
        eng?.submit(PdfEngine.Q_THUMB, emptyList())
    }

    /** Fades the document between normal and inverted over 250 ms. */
    private fun toggleInvert() {
        val e = eng ?: return
        val s = state ?: return
        s.invert = !s.invert
        paintInvertButton()
        invertAnim?.cancel()
        invertAnim = ValueAnimator.ofFloat(e.invert, if (s.invert) 1f else 0f).apply {
            duration = 250
            interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener { eng?.invert = it.animatedValue as Float }
            start()
        }
        save()
    }

    private fun paintInvertButton() {
        val on = state?.invert == true
        invertBtn.setColorFilter(if (on) Theme.accentInk() else Theme.text())
        invertBtn.isSelected = on
    }

    private fun openCropper() {
        val e = eng ?: return
        hideUi()
        val c = CropOverlay(this, e, pdf.currentPage, insets, pdf.pageRect()) { r -> closeCropper(r) }
        cropper = c
        root.addView(c, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun closeCropper(r: RectF?) {
        val e = eng ?: return
        val c = cropper ?: return
        cropper = null
        if (r != null && r != e.crop) {
            e.setCrop(r)
            pdf.onCropChanged()
            save()
        }
        c.close(pdf.pageRect(), RectF(e.crop)) { root.removeView(c) }
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
        invertAnim?.cancel()
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
