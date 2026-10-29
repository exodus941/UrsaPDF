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
    private lateinit var timeoutBtn: ImageView
    private lateinit var bookmarkBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var cropBtn: ImageView
    private lateinit var moreBtn: ImageView
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
    // Search and forms, from the system renderer or the downloaded engine.
    private var tools: Tools? = null
    private lateinit var editBtn: ImageView
    private lateinit var modal: Modal
    private lateinit var gate: LibraryGate
    private val fieldsNow = HashMap<Int, List<Field>>()       // as edited
    private val fieldsFirst = HashMap<Int, List<Field>>()     // as opened, to tell what changed
    private var dirty = false
    private var isForm = false
    private var closing = false
    private val saveAs = 21
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
        backBtn = iconButton(R.drawable.ic_back, "Back") { closeReader() }
        top.addView(backBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        titleView = label("", 16f, Theme.text()).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        // 16 dp after the title doubles the gap you see to the search glyph (46 to about 90 px at 2.6x).
        top.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dpi(16) })
        searchBtn = iconButton(R.drawable.ic_search, "Search") { openSearch() }.apply { visibility = View.GONE }
        top.addView(searchBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        cropBtn = iconButton(R.drawable.ic_frame, "Page Cropper") { openCropper() }
        top.addView(cropBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        // Only for PDFs that are forms; shown once the open document says so.
        editBtn = iconButton(R.drawable.ic_rename, "Fill in Form") { toggleForm() }.apply { visibility = View.GONE }
        top.addView(editBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        bookmarkBtn = iconButton(R.drawable.ic_bookmark, "Bookmarks") { bookmarkMenu() }
        top.addView(bookmarkBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        timeoutBtn = iconButton(R.drawable.ic_timeout, "Screen Timeout") { timeoutMenu() }
        top.addView(timeoutBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        moreBtn = iconButton(R.drawable.ic_more, "More options") { moreMenu() }
        top.addView(moreBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(4) })
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
            // Android 11+ reports the keyboard as its own inset; older versions fold it into the window inset.
            @Suppress("DEPRECATION")
            val ime = if (android.os.Build.VERSION.SDK_INT >= 30) ins.getInsets(android.view.WindowInsets.Type.ime()).bottom else ins.systemWindowInsetBottom
            if (android.os.Build.VERSION.SDK_INT >= 30) search.setPadding(insets[0], 0, insets[2], maxOf(insets[3], ime))
            (flash.layoutParams as FrameLayout.LayoutParams).bottomMargin = dpi(32) + insets[3]
            flash.requestLayout()
            strip?.setPadding(insets[0], 0, insets[2], 0)
            scrubber?.let {
                it.setPadding(insets[0], 0, insets[2], insets[3] + barGap())
                it.layoutParams.height = dpi(32) + insets[3] + barGap()
                it.requestLayout()
            }
            ins
        }
        setContentView(root)
        modal = Modal(this, root, pdf) { if (uiShown) top.height else 0 }
        menu = Menu(this, root)
        // A PDF opened from another app skips the home list, so the reader blocks too.
        gate = LibraryGate(this, root) { eng?.let { setupTools(it) } }
        gate.check(update = false)
        // Android 10 and older give a full-screen window no keyboard inset; the part of the
        // window the keyboard leaves visible is the measure that works there.
        if (android.os.Build.VERSION.SDK_INT < 30) root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val kb = (root.rootView.height - r.bottom).coerceAtLeast(0)
            val want = maxOf(insets[3], kb)
            if (search.paddingBottom != want) search.setPadding(insets[0], 0, insets[2], want)
        }
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
        titleView.text = s.name
        pdf.lockZoom = s.lock
        pdf.setDocument(e, s.page.coerceIn(0, e.pageCount - 1), s.offset, s.x, if (s.lock > 0f) s.lock else s.zoom)

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
            setPadding(insets[0], 0, insets[2], insets[3] + barGap())
        }
        scrubber = sc
        bottom.addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, dpi(32) + insets[3] + barGap()))
        // Older phones without the engine yet: the gate sets the tools up once it is in.
        if (!gate.showing) setupTools(e)
        applyTheme()
        updatePill()
        Store.put(this, s)
    }

    private fun setupTools(e: PdfEngine) {
        val t = Tools.of(e)
        tools = t
        searchBtn.fade(t?.canSearch == true)
        t?.isForm { form ->
            editBtn.fade(form)
            isForm = form
            if (form && !t.live) loadFields(pdf.currentPage)
        }
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

    override fun onPageChanged(page: Int) {
        updatePill()
        if (pdf.formMode || dirty || (isForm && tools?.live == false)) loadFields(page)
    }

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
        for (b in listOf(backBtn, searchBtn, cropBtn, editBtn, bookmarkBtn, timeoutBtn, moreBtn, goBtn, closeBtn, prevBtn, nextBtn)) {
            b.setColorFilter(Theme.text())
            (b.background as? android.graphics.drawable.RippleDrawable)?.setColor(android.content.res.ColorStateList.valueOf(Theme.ripple()))
        }
        if (::editBtn.isInitialized) paintEditButton()
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
        // With a menu open, a tap on the page only closes the menu.
        if (menu.showing) { menu.dismiss(); return }
        if (sheetOpen) { closeBookmarks(); return }
        // Taps never turn pages, so a tap anywhere toggles the chrome.
        if (uiShown) hideUi() else showUi()
    }

    override fun onVerticalSwipe() {
        if (eng == null || searching) return
        if (menu.showing) { menu.dismiss(); return }
        if (sheetOpen) { closeBookmarks(); return }
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
        menu.dismiss()
        closeBookmarks()
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
        invertAnim?.cancel()
        invertAnim = ValueAnimator.ofFloat(e.invert, if (s.invert) 1f else 0f).apply {
            duration = 250
            interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener { eng?.invert = it.animatedValue as Float }
            start()
        }
        save()
    }

    /** Space between the scrubber and the navigation bar, so a thumb on it stays clear of the system's buttons. */
    private fun barGap() = dpi(16)

    private lateinit var menu: Menu
    private var menuOf: View? = null

    /** Opens a menu under the top bar at its end edge; its own button closes it again. */
    private fun openMenu(from: View, rows: List<Menu.Row>) {
        val same = menu.showing && menuOf === from
        menu.dismiss()
        closeBookmarks()
        menuOf = null
        if (same) return
        menuOf = from
        // Its end edge lines up with the button's end edge.
        val at = IntArray(2).also { from.getLocationInWindow(it) }
        menu.show(rows, Gravity.TOP or Gravity.END, root.width - (at[0] + from.width), top.height)
    }

    private fun moreMenu() {
        fun toggle(on: Boolean) = if (on) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off
        // The toggles leave the menu open; a tap outside it closes it.
        openMenu(moreBtn, listOf(
            Menu.Row("Invert Colours", mark = { toggle(state?.invert == true) }, stay = true) { toggleInvert() },
            Menu.Row("Dark Mode", mark = { toggle(!Theme.light) }, stay = true) { Theme.toggle(this) },
            Menu.Row("Zoom Lock", mark = { toggle(pdf.lockZoom > 0f) }, stay = true) { toggleLock() },
            Menu.Row("Left-Handed Pie", mark = { toggle(leftPie) }, stay = true) { leftPie = !leftPie },
            Menu.Row("Ratchet", mark = { toggle(ratchetPie) }, stay = true) { ratchetPie = !ratchetPie },
        ))
    }

    /** Locks the zoom the page is at now, for this file. */
    private fun toggleLock() {
        pdf.lockZoom = if (pdf.lockZoom > 0f) 0f else pdf.zoom
        save()
        toast(if (pdf.lockZoom > 0f) "Zoom locked" else "Zoom unlocked")
    }

    private fun bookmarkMenu() {
        openMenu(bookmarkBtn, listOf(
            Menu.Row(if (pdf.currentPage in (state?.bookmarks ?: return)) "Remove Bookmark" else "Add Bookmark") { toggleBookmark() },
            Menu.Row("Show Bookmarks") { showBookmarks() },
        ))
    }

    private fun toggleBookmark() {
        val s = state ?: return
        val page = pdf.currentPage
        if (page in s.bookmarks) {
            s.bookmarks -= page; save()
            toast("Bookmark removed from page ${page + 1}")
        } else nameBookmark(page, "Add Bookmark", "Save") { toast("Bookmarked page ${page + 1}") }
    }

    // ---- pie menu ----
    private var pie: PieMenu? = null

    /** The pie mirrored for a left thumb; for every file. */
    private var leftPie: Boolean
        get() = getSharedPreferences("ursa", MODE_PRIVATE).getBoolean("leftPie", false)
        set(v) { getSharedPreferences("ursa", MODE_PRIVATE).edit().putBoolean("leftPie", v).apply() }

    /** The pie ticks like a rotary dial as the finger turns; off by default, for every file. */
    private var ratchetPie: Boolean
        get() = getSharedPreferences("ursa", MODE_PRIVATE).getBoolean("ratchet", false)
        set(v) { getSharedPreferences("ursa", MODE_PRIVATE).edit().putBoolean("ratchet", v).apply() }

    /** A page held still for 250 ms. The chrome and any search slide away as the pie opens. */
    override fun onLongPress(x: Float, y: Float) {
        if (eng == null || pie != null) return
        closeSearch()  // search slides away; only the pie shows
        menu.dismiss()
        closeBookmarks()
        hideUi()
        val marked = pdf.currentPage in (state?.bookmarks ?: emptyMap<Int, String>())
        // Clockwise from the top, for a right thumb: the most used up and to the left, the
        // rarest bottom right, under the thumb. Left-handed mirrors it side to side.
        val add = PieMenu.Item(if (marked) R.drawable.ic_bookmark_minus else R.drawable.ic_bookmark, if (marked) "Remove Bookmark" else "Add Bookmark") { toggleBookmark() }
        val invert = PieMenu.Item(R.drawable.ic_invert, "Invert Colours") { toggleInvert() }
        val crop = PieMenu.Item(R.drawable.ic_frame, "Page Cropper") { openCropper() }
        val lock = PieMenu.Item(R.drawable.ic_lock, "Zoom Lock") { toggleLock() }
        val find = PieMenu.Item(R.drawable.ic_search, "Search", tools?.canSearch == true) { showUi(); openSearch() }
        val list = PieMenu.Item(R.drawable.ic_bookmarks, "Show Bookmarks") { showBookmarks() }
        val right = listOf(add, invert, crop, lock, find, list)
        val items = if (leftPie) listOf(add) + right.drop(1).reversed() else right
        val p = PieMenu(this, items, x, y, ratchetPie, leftPie) { root.removeView(it) }
        pie = p
        root.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun bookmarkName(p: Int) = state?.bookmarks?.get(p)?.takeIf { it.isNotBlank() } ?: "Page ${p + 1}"

    /** The name modal, filled with the current name; an emptied name falls back to "Page N". */
    private fun nameBookmark(p: Int, title: String, action: String, done: () -> Unit) {
        val s = state ?: return
        modal.input(title, bookmarkName(p), action) { raw ->
            val n = raw.trim()
            s.bookmarks[p] = if (n == "Page ${p + 1}") "" else n
            save()
            done()
        }
    }

    // ---- bookmark list ----
    // It takes the page strip's place: the strip slides down, then the list slides up,
    // %ease250% both ways. A tap outside it or Back reverses that.
    private var sheet: LinearLayout? = null
    private val sheetOpen get() = sheet != null

    /** Every bookmark in the file, in page order. A tap jumps there; the minus removes one at once. */
    private fun showBookmarks() {
        val s = state ?: return
        val e = eng ?: return
        if (sheetOpen) return
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun empty() = label("No bookmarks yet", 15f, Theme.sub()).apply { setPadding(dpi(20), dpi(16), dpi(20), dpi(16)) }
        for (p in s.bookmarks.keys) list.addView(LinearLayout(this).apply row@{
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple()
            setPadding(dpi(20), dpi(4), dpi(4), dpi(4))
            minimumHeight = dpi(56)
            // The name, and under it the page when the name is not already the page.
            val name = label("", 15f, Theme.text()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val where = label("Page ${p + 1} of ${e.pageCount}", 13f, Theme.sub()).apply { setPadding(0, dpi(2), 0, 0) }
            fun paintName() {
                name.text = bookmarkName(p)
                where.visibility = if (s.bookmarks[p].isNullOrBlank()) View.GONE else View.VISIBLE
            }
            paintName()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(where)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_rename)
                setColorFilter(Theme.sub())
                background = ripple()
                setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
                contentDescription = "Rename bookmark on page ${p + 1}"
                setOnClickListener { nameBookmark(p, "Rename Bookmark", "Save") { paintName() } }
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_remove)
                setColorFilter(Theme.sub())
                background = ripple()
                setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
                contentDescription = "Remove bookmark on page ${p + 1}"
                setOnClickListener {
                    s.bookmarks -= p; save()
                    // The row fades and closes over 250 ms; the last one leaves the empty note.
                    val row = this@row; val h = row.height; row.isEnabled = false; isEnabled = false
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = Motion.MS; interpolator = Motion.OUT
                        addUpdateListener { a -> val t = a.animatedValue as Float; row.alpha = t; row.layoutParams.height = (h * t).toInt(); row.requestLayout() }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                list.removeView(row)
                                if (list.childCount == 0) list.addView(empty().apply { alpha = 0f; fade(true) })
                            }
                        })
                        start()
                    }
                }
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
            setOnClickListener { closeBookmarks(); pdf.jumpTo(p); strip?.centerOn(p) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { height = WRAP_CONTENT })
        if (s.bookmarks.isEmpty()) list.addView(empty())
        val cap = (root.height * 0.5f).toInt()
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }.apply { addView(list) }
        val sh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.panel())
            isClickable = true
            setPadding(insets[0], dpi(8), insets[2], dpi(8) + insets[3])
            addView(label("Bookmarks", 13f, Theme.sub(), bold = true).apply { setPadding(dpi(20), dpi(4), dpi(20), dpi(4)) })
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            visibility = View.INVISIBLE
        }
        sheet = sh
        ui.addView(sh, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        bottom.animate().translationY(bottom.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).withEndAction {
            if (sheet !== sh) return@withEndAction
            sh.translationY = sh.height.toFloat(); sh.visibility = View.VISIBLE
            sh.animate().translationY(0f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
        }.start()
    }

    private fun closeBookmarks() {
        val sh = sheet ?: return
        sheet = null
        sh.animate().cancel()
        sh.isEnabled = false
        sh.animate().translationY(sh.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).withEndAction {
            ui.removeView(sh)
            if (uiShown && !searching) bottom.animate().translationY(0f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
        }.start()
    }

    private fun timeoutMenu() {
        openMenu(timeoutBtn, ScreenTimeout.CHOICES.map { (ms, name) ->
            Menu.Row(name, mark = { if (ms == ScreenTimeout.get(this)) R.drawable.ic_check else null }) { ScreenTimeout.set(this, ms); awake() }
        })
    }

    // The screen stays on while the reader is used, and for the chosen time after the
    // last touch; then the phone's own screen timeout takes over.
    private val sleep = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun awake() {
        val ms = ScreenTimeout.get(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeCallbacks(sleep)
        if (ms > 0) root.postDelayed(sleep, ms)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) awake()
        // The rest of the touch that opened the pie belongs to it.
        pie?.let { p ->
            val m = android.view.MotionEvent.obtain(ev).apply { offsetLocation(-p.left.toFloat(), -p.top.toFloat()) }
            p.touch(m); m.recycle()
            val end = ev.actionMasked
            if (end == android.view.MotionEvent.ACTION_UP || end == android.view.MotionEvent.ACTION_CANCEL || end == android.view.MotionEvent.ACTION_DOWN) {
                if (end == android.view.MotionEvent.ACTION_DOWN) p.close()
                pie = null
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
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
            pdf.lockZoom = 0f  // a new frame wants a new zoom
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
        s.page = p[0].toInt(); s.offset = p[1]; s.x = p[2]; s.zoom = pdf.zoom; s.lock = pdf.lockZoom
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
        awake()
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
        tools?.close()
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
            // The keyboard sends Search; a hardware or older keyboard sends a plain Enter key.
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
                if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || enter) { runSearch(); true } else false

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
        if (searching || tools?.canSearch != true) return
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
        val t = tools ?: return
        val q = field.text.toString().trim()
        if (q.isEmpty()) return
        keyboard(false)
        results.clear(); hits.clear(); at = -1; searchDone = false
        pdf.hits = hits; pdf.focusHit = null
        t.search(q, { page, rects ->
            hits[page] = rects
            for (k in rects.indices) results += page to k
            pdf.hits = hits
            if (at < 0) goTo(0) else updateCount()
        }, { searchDone = true; updateCount() })
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
        tools?.search(null, { _, _ -> }, {})
        results.clear(); hits.clear(); at = -1
        pdf.hits = emptyMap(); pdf.focusHit = null
        pdf.setBand(0f, 0f)
        keyboard(false)
        search.animate().translationY(search.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
        navRow.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        uiShown = false
        top.animate().translationY(-top.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
    }

    // ---- forms ----

    /** The pencil: outline the fields and send taps on them here, or stop. */
    private fun toggleForm() {
        val on = !pdf.formMode
        pdf.formMode = on
        paintEditButton()
        if (on) {
            hideUi()
            loadFields(pdf.currentPage)
            showFlash("Tap a field to fill it in")
        }
    }

    private fun paintEditButton() = editBtn.setColorFilter(if (pdf.formMode) Theme.accentInk() else Theme.text())

    private fun loadFields(page: Int) {
        if (fieldsNow.containsKey(page)) return
        tools?.fields(page) { f ->
            fieldsFirst.putIfAbsent(page, f)
            fieldsNow[page] = f
            publishFields()
        }
    }

    /**
     * Hand the view its fields. With the downloaded engine the view paints every field's
     * value itself: the older system renderer draws no form values at all, so even a
     * form opened unchanged would look empty.
     */
    private fun publishFields() {
        pdf.fields = HashMap(fieldsNow)
        if (tools?.live == false) pdf.paintEdits = HashMap(fieldsNow)
    }

    private fun edited(page: Int, f: List<Field>) {
        if (f.isEmpty()) return
        fieldsNow[page] = f
        dirty = true
        publishFields()
    }

    override fun onFieldTap(page: Int, f: Field) {
        val t = tools ?: return
        if (!f.editable) {
            toast(if (f.type == Pdfium.SIGNATURE) "Signature fields can’t be filled in here" else "This field can’t be changed")
            return
        }
        val title = f.label.ifEmpty { "Field" }
        when (f.type) {
            Pdfium.TEXTFIELD -> {
                val done: (String) -> Unit = { v -> if (v != f.value) t.setText(page, f, v) { edited(page, it) } }
                if (f.multiline) modal.inputLines(title, f.value, "Done", done) else modal.input(title, f.value, "Done", done)
            }
            Pdfium.CHECKBOX, Pdfium.RADIOBUTTON -> t.click(page, f) { edited(page, it) }
            Pdfium.COMBOBOX, Pdfium.LISTBOX -> if (f.options.isNotEmpty()) {
                modal.choose(title, null, f.options.mapIndexed { k, o ->
                    (if (k in f.selected) "✓  $o" else o) to { t.choose(page, f, k) { edited(page, it) } }
                })
            }
        }
    }

    /** Closing with unsaved edits asks first. */
    private fun closeReader() {
        if (!dirty) { goHome(); return }
        modal.choose("Save your changes?", "This form has changes that are not saved.", listOf(
            "Save Changes to File" to { saveInPlace() },
            "Save as New File" to { askNewFile() },
            "Close Without Saving" to { dirty = false; goHome() },
        ))
    }

    private fun saveToTemp(then: (java.io.File) -> Unit) {
        val out = java.io.File(cacheDir, "save.pdf")
        tools?.save(out) { ok -> if (ok) then(out) else toast("Couldn’t save this form") }
    }

    private fun saveInPlace() = saveToTemp { tmp ->
        val ok = try {
            contentResolver.openOutputStream(uri, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            true
        } catch (e: Exception) { false }
        tmp.delete()
        if (ok) {
            dirty = false
            state?.let { it.size = tmp.length().takeIf { l -> l > 0 } ?: it.size }
            toast("Saved “${state?.name}”")
            goHome()
        } else {
            toast("This file can’t be changed in place; choose where to save a copy")
            askNewFile()
        }
    }

    private fun askNewFile() {
        val base = state?.name?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "Form"
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "$base (filled).pdf")
        }, saveAs)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
        val target = data?.data
        if (req != saveAs || res != RESULT_OK || target == null) return
        saveToTemp { tmp ->
            val ok = try {
                contentResolver.openOutputStream(target, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                true
            } catch (e: Exception) { false }
            if (!ok) { tmp.delete(); toast("Couldn’t write the new file"); return@saveToTemp }
            try {
                contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            val name = try {
                contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null } ?: "Filled form.pdf"
            Store.put(this, DocState(target.toString(), name).also { d ->
                d.pages = eng?.pageCount ?: 0
                d.size = tmp.length()
                state?.let { s -> d.crop.set(s.crop); d.page = pdf.currentPage }
                d.opened = System.currentTimeMillis()
            })
            tmp.delete()
            dirty = false
            toast("Saved as “$name”")
            goHome()
        }
    }

    private fun toast(t: String) = note(t)

    private fun showFlash(text: String) {
        flash.text = text
        flash.removeCallbacks(hideFlash)
        flash.animate().cancel()
        flash.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        flash.postDelayed(hideFlash, Motion.MS + 1500L)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            gate.showing -> {}  // the library download cannot be dismissed
            pie != null -> { pie?.close(); pie = null }
            menu.showing -> menu.dismiss()
            sheetOpen -> closeBookmarks()
            modal.showing -> modal.cancel()
            cropper != null -> closeCropper(null)
            // While viewing results, Back steps back one result; from the first, it closes.
            searching && resultsShown && at > 0 -> step(-1)
            searching -> closeSearch()
            else -> closeReader()
        }
    }

    /** Opened from another app, the reader is the task root; Back still lands on the home list. */
    private fun goHome() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
