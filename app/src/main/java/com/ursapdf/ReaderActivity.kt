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
    private var scrubber: Scrubber? = null
    private var cropper: CropOverlay? = null
    private var eng: PdfEngine? = null
    private var state: DocState? = null
    private var insets = IntArray(4)
    private lateinit var invertBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var cropBtn: ImageView
    private lateinit var themeBtn: ImageView
    private lateinit var searchBtn: ImageView
    // Search strip: a field row, and under it the previous / count / next row.
    private lateinit var search: LinearLayout
    private lateinit var fieldRow: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var field: android.widget.EditText
    private lateinit var goBtn: ImageView
    private lateinit var closeBtn: ImageView
    private lateinit var prevBtn: ImageView
    private lateinit var nextBtn: ImageView
    private lateinit var count: TextView
    private var searching = false
    private var resultsShown = false
    private var searchGen = 0
    private var searchDone = false
    private val results = ArrayList<Pair<Int, Int>>()       // page, match index on that page
    private val hits = HashMap<Int, List<List<RectF>>>()
    private var at = -1
    private lateinit var flash: TextView
    private val onTheme: () -> Unit = { applyTheme() }
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
        Theme.listeners += onTheme
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
        // Every top-bar control is a 48 dp square icon; the name is its content description.
        fun iconButton(icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            contentDescription = name
            setOnClickListener { act() }
        }
        backBtn = iconButton(R.drawable.ic_back, "Back") { goHome() }
        top.addView(backBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        titleView = label("", 16f, Theme.text()).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        searchBtn = iconButton(R.drawable.ic_search, "Search") { openSearch() }.apply { visibility = View.GONE }
        top.addView(searchBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        cropBtn = iconButton(R.drawable.ic_crop, "Page Cropper") { openCropper() }
        top.addView(cropBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        invertBtn = iconButton(R.drawable.ic_invert, "Invert Colours") { toggleInvert() }
        top.addView(invertBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        themeBtn = iconButton(R.drawable.ic_bulb_on, "Toggle Light Mode") { Theme.toggle(this) }
        top.addView(themeBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(4) })
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
        buildSearchStrip()
        ui.addView(search, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        // Hidden: fully below the screen. Typing: only the field row shows. Results: both rows.
        search.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (!searching) v.translationY = v.height.toFloat()
            else if (!resultsShown) v.translationY = navRow.height.toFloat()
        }

        // The same pill, shown alone for a moment after a swipe turns the page.
        flash = label("", 13f, Theme.text(), bold = true).apply {
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
            alpha = 0f
        }
        root.addView(flash, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(32)
        })
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        top.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!uiShown) v.translationY = -v.height.toFloat() }
        bottom.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!uiShown) v.translationY = v.height.toFloat() }

        root.setOnApplyWindowInsetsListener { _, ins ->
            insets = ins.safe()
            top.setPadding(insets[0], insets[1], insets[2], 0)
            // The search strip rides on the keyboard while it is up.
            val ime = if (android.os.Build.VERSION.SDK_INT >= 30) ins.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            search.setPadding(insets[0], 0, insets[2], maxOf(insets[3], ime))
            (flash.layoutParams as FrameLayout.LayoutParams).bottomMargin = dpi(32) + insets[3]
            flash.requestLayout()
            strip?.setPadding(insets[0], 0, insets[2], 0)
            scrubber?.let {
                it.setPadding(insets[0], 0, insets[2], insets[3])
                it.layoutParams.height = dpi(32) + insets[3]
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
        s.size = Store.sizeOf(this, key)
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
            setPadding(insets[0], 0, insets[2], 0)
        }
        strip = st
        bottom.addView(st, LinearLayout.LayoutParams(MATCH_PARENT, dpi(120)))
        val sc = Scrubber(this, e.pageCount) { p ->
            pdf.jumpTo(p)
            strip?.centerOn(p)
        }.apply {
            setBackgroundColor(Theme.panel())
            setPadding(insets[0], 0, insets[2], insets[3])
        }
        scrubber = sc
        bottom.addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, dpi(32) + insets[3]))
        searchBtn.visibility = if (e.canSearch) View.VISIBLE else View.GONE
        applyTheme()
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
        scrubber?.current = pdf.currentPage
    }

    override fun onPageChanged(page: Int) = updatePill()

    /**
     * After a swipe: fade the page pill in (250 ms), hold 0.625 s, fade out
     * (250 ms). Each turn restarts the hold.
     * The chrome already shows the same pill, so nothing flashes while it is up.
     * The fades are linear: an ease-out curve reaches 80% opacity in the first 60 ms,
     * so a 250 ms fade read as a pop.
     */
    override fun onTurned(page: Int) {
        val e = eng ?: return
        if (uiShown) return
        flash.text = "Page ${page + 1} of ${e.pageCount}"
        flash.removeCallbacks(hideFlash)
        flash.animate().cancel()
        flash.animate().alpha(1f).setDuration((250 * (1 - flash.alpha)).toLong()).setInterpolator(linear).start()
        flash.postDelayed(hideFlash, 250L + 625L)
    }

    private val linear = android.view.animation.LinearInterpolator()
    private val hideFlash = Runnable {
        flash.animate().alpha(0f).setDuration(250).setInterpolator(linear).start()
    }

    /** Runs on every frame of a theme change. */
    private fun applyTheme() {
        top.setBackgroundColor(Theme.panel())
        titleView.setTextColor(Theme.text())
        search.setBackgroundColor(Theme.panel())
        fieldRow.findViewWithTag<View>("box").background = pill(Theme.button(), 14f)
        field.setTextColor(Theme.text()); field.setHintTextColor(Theme.sub())
        count.setTextColor(Theme.text())
        for (b in listOf(backBtn, searchBtn, cropBtn, themeBtn, goBtn, closeBtn, prevBtn, nextBtn)) {
            b.setColorFilter(Theme.text())
            (b.background as? android.graphics.drawable.RippleDrawable)?.setColor(android.content.res.ColorStateList.valueOf(Theme.ripple()))
        }
        themeBtn.setImageResource(if (Theme.light) R.drawable.ic_bulb_off else R.drawable.ic_bulb_on)
        themeBtn.contentDescription = if (Theme.light) "Toggle Dark Mode" else "Toggle Light Mode"
        paintInvertButton()
        for (p in listOf(badge, flash)) {
            p.setTextColor(Theme.text())
            p.background = pill(Theme.panel(), 14f)
        }
        strip?.setBackgroundColor(Theme.panel())
        scrubber?.setBackgroundColor(Theme.panel())
        strip?.invalidate(); scrubber?.invalidate(); pdf.invalidate()
    }

    override fun onTap(x: Float, y: Float) {
        if (eng == null) return
        if (searching) { keyboard(false); return }
        if (uiShown) { hideUi(); return }
        val w = pdf.width; val h = pdf.height
        if (x > w * 0.25f && x < w * 0.75f && y > h * 0.25f && y < h * 0.75f) showUi()
    }

    override fun onVerticalSwipe() {
        if (eng == null || searching) return
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

    override fun onResume() {
        super.onResume()
        Updater.resumed(this, home = false)
    }

    override fun onPause() {
        super.onPause()
        Updater.paused(this)
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        invertAnim?.cancel()
        Theme.listeners -= onTheme
        flash.removeCallbacks(hideFlash)
        eng?.close()
        eng = null
    }

    // ---- search ----

    private fun buildSearchStrip() {
        fun icon(res: Int, name: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            contentDescription = name
            setOnClickListener { act() }
        }
        search = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        fieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(8), dpi(4), dpi(8))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "box" }
        field = android.widget.EditText(this).apply {
            hint = "Search in this PDF"
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(16), dpi(12), dpi(8), dpi(12))
            setOnEditorActionListener { _, id, _ ->
                if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
        }
        box.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        goBtn = icon(R.drawable.ic_search, "Find") { runSearch() }
        box.addView(goBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        fieldRow.addView(box, LinearLayout.LayoutParams(0, dpi(48), 1f))
        closeBtn = icon(R.drawable.ic_close, "Close search") { closeSearch() }
        fieldRow.addView(closeBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginStart = dpi(4) })
        search.addView(fieldRow)

        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), 0, dpi(12), dpi(8))
            alpha = 0f
        }
        prevBtn = icon(R.drawable.ic_prev, "Previous result") { step(-1) }
        navRow.addView(prevBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        count = label("", 15f, Theme.text(), bold = true).apply { gravity = Gravity.CENTER }
        navRow.addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nextBtn = icon(R.drawable.ic_next, "Next result") { step(1) }
        navRow.addView(nextBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        search.addView(navRow)
    }

    private fun keyboard(show: Boolean) {
        val im = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (show) { field.requestFocus(); im.showSoftInput(field, 0) }
        else { im.hideSoftInputFromWindow(field.windowToken, 0); field.clearFocus() }
    }

    /** The page strip slides out, then the search strip rises in its place (%ease250% each). */
    private fun openSearch() {
        if (searching || eng?.canSearch != true) return
        searching = true; resultsShown = false
        navRow.alpha = 0f
        bottom.animate().translationY(bottom.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).withEndAction {
            strip?.visibility = View.INVISIBLE
            if (!searching) return@withEndAction
            search.animate().translationY(navRow.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
            keyboard(true)
        }.start()
        eng?.submit(PdfEngine.Q_THUMB, emptyList())
    }

    /** Search every page; the strip rises further to show previous / count / next. */
    private fun runSearch() {
        val e = eng ?: return
        val q = field.text.toString().trim()
        if (q.isEmpty()) return
        keyboard(false)
        results.clear(); hits.clear(); at = -1; searchDone = false
        pdf.hits = hits; pdf.focusHit = null
        searchGen = e.search(q)
        e.onMatches = { g, page, rects ->
            if (g == searchGen) {
                hits[page] = rects
                for (k in rects.indices) results += page to k
                pdf.hits = hits
                if (at < 0) goTo(0) else updateCount()
            }
        }
        e.onSearchDone = { g -> if (g == searchGen) { searchDone = true; updateCount() } }
        updateCount()
        if (!resultsShown) {
            resultsShown = true
            search.animate().translationY(0f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
            navRow.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        }
    }

    private fun goTo(i: Int) {
        if (i !in results.indices) return
        at = i
        val (page, k) = results[i]
        pdf.focusHit = page to k
        // The match must not sit under the top bar or the search strip.
        val visTop = (top.bottom + top.translationY).coerceAtLeast(0f)
        val visBottom = search.top + search.translationY
        hits[page]?.getOrNull(k)?.firstOrNull()?.let { pdf.reveal(page, it, visTop, visBottom) }
        updateCount()
    }

    private fun step(d: Int) = goTo(at + d)

    private fun updateCount() {
        val more = if (searchDone) "" else "…"
        count.text = when {
            results.isEmpty() && searchDone -> "No matches"
            results.isEmpty() -> "Searching…"
            else -> "${at + 1} of ${results.size}$more"
        }
        // Previous stops at the first result, Next at the last.
        setEnabled(prevBtn, at > 0)
        setEnabled(nextBtn, at in 0 until results.size - 1)
    }

    private fun setEnabled(b: ImageView, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.35f
    }

    /** Back to the plain reader, skipping the page strip: search and top bar slide out. */
    private fun closeSearch() {
        if (!searching) return
        searching = false; resultsShown = false
        eng?.search(null)
        results.clear(); hits.clear(); at = -1
        pdf.hits = emptyMap(); pdf.focusHit = null
        pdf.setBand(0f, 0f)
        keyboard(false)
        search.animate().translationY(search.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
        navRow.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        uiShown = false
        top.animate().translationY(-top.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            cropper != null -> closeCropper(null)
            // While viewing results, Back steps back one result; from the first, it closes.
            searching && resultsShown && at > 0 -> step(-1)
            searching -> closeSearch()
            else -> goHome()
        }
    }

    /** Opened from another app, the reader is the task root; Back still lands on the home list. */
    private fun goHome() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
