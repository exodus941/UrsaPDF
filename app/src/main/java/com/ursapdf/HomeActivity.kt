package com.ursapdf

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

class HomeActivity : Activity() {

    private val pick = 1
    private var docs: List<DocState> = emptyList()   // everything remembered
    private var shown: List<DocState> = emptyList()  // after the search filter
    private val selected = LinkedHashSet<String>()   // uris, while selecting
    private var selecting = false

    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var themeBtn: ImageView
    private lateinit var updateBtn: ImageView
    private lateinit var sticky: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var clear: ImageView
    private lateinit var list: ListView
    private val rows by lazy { Adapter() }
    private val spacer by lazy { View(this).apply { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, 0) } }
    private lateinit var empty: TextView
    private lateinit var topFade: Vignette
    private lateinit var bottomFade: Vignette
    private lateinit var bar: LinearLayout
    private lateinit var modal: Modal
    private var bottomInset = 0
    private val onTheme: () -> Unit = { applyTheme() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        frame = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(12), dpi(8), dpi(8))
        }
        title = label("UrsaPDF", 24f, Theme.text(), bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        themeBtn = iconButton(R.drawable.ic_bulb_on, "Toggle Light Mode") { Theme.toggle(this) }
        header.addView(themeBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        updateBtn = iconButton(R.drawable.ic_refresh, "Check for Updates") { Updater.check(this) }
        header.addView(updateBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        root.addView(header)

        // The list scrolls under the sticky block (Open a PDF and search), which is
        // drawn last so it sits above everything in the body.
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = getDrawable(android.R.color.transparent)
            clipToPadding = false
            addHeaderView(spacer, null, false)
            adapter = rows
            setOnItemClickListener { _, _, pos, _ ->
                val d = shown.getOrNull(pos - headerViewsCount) ?: return@setOnItemClickListener
                if (selecting) toggle(d) else open(Uri.parse(d.uri))
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                shown.getOrNull(pos - headerViewsCount)?.let { startSelecting(it) }; true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) {
                    if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }
                override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) = updateFades()
            })
        }
        body.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        empty = label("", 15f, Theme.sub()).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        body.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        topFade = Vignette(this, fromBottom = false)
        body.addView(topFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(32), Gravity.TOP))
        bottomFade = Vignette(this, fromBottom = true)
        body.addView(bottomFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(32), Gravity.BOTTOM))
        sticky = buildSticky()
        body.addView(sticky, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        sticky.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        bar = buildSelectionBar()
        frame.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!selecting) v.translationY = v.height.toFloat() }

        frame.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.safe()
            root.setPadding(s[0], s[1], s[2], 0)
            val ime = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
            bottomInset = maxOf(s[3], ime)
            bar.setPadding(s[0] + dpi(8), dpi(8), s[2] + dpi(8), dpi(8) + s[3])
            place()
            ins
        }
        setContentView(frame)
        modal = Modal(this, frame, root)
        Theme.listeners += onTheme
        applyTheme()
    }

    private fun iconButton(icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        background = ripple()
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        contentDescription = name
        setOnClickListener { act() }
    }

    private fun buildSticky() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dpi(16), dpi(4), dpi(16), dpi(12))
        val openBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(ACCENT, 14f)
            foreground = ripple()
            setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_add) })
            addView(label("Open a PDF", 16f, BG, bold = true).apply { setPadding(dpi(12), 0, 0, 0) })
            setOnClickListener { openPicker() }
        }
        addView(openBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), 0, dpi(4), 0)
        }
        searchIcon = ImageView(context).apply { setImageResource(R.drawable.ic_search) }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dpi(20), dpi(20)))
        search = EditText(context).apply {
            hint = "Search files"
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(12), dpi(12), dpi(8), dpi(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clear = iconButton(R.drawable.ic_close, "Clear search") {
            search.setText("")
            hideKeyboard()
            list.setSelection(0)
        }.apply { visibility = View.GONE }
        searchBox.addView(clear, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        addView(searchBox, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)).apply { topMargin = dpi(12) })
    }

    private fun buildSelectionBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        elevation = dp(8f)
        fun action(icon: Int, text: String, act: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            addView(ImageView(context).apply { setImageResource(icon); tag = "icon" }, LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(label(text, 15f, Theme.text(), bold = true).apply { setPadding(dpi(12), 0, 0, 0); tag = "text" })
            setOnClickListener { act() }
        }
        addView(action(R.drawable.ic_remove, "Remove All Selected") { removeSelected() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(action(R.drawable.ic_delete, "Delete All Selected") { deleteSelected() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    /** List starts under the sticky block; the top vignette hangs from its lower edge. */
    private fun place() {
        val h = sticky.height
        // A blank first row as tall as the sticky block: real rows start below it and
        // still scroll up underneath. (Top padding set after the first layout left rows
        // hidden behind the block.)
        if (spacer.layoutParams.height != h) { spacer.layoutParams.height = h; spacer.requestLayout() }
        list.setPadding(0, 0, 0, bottomInset + if (selecting) bar.height else 0)
        topFade.translationY = h.toFloat()
        empty.translationY = h + dp(24f)
        // The list runs under the gesture bar, so the shadow runs to the screen's edge too;
        // stopping above the bar left its darkest row against white. Over the selection
        // bar it sits on the bar's top edge instead.
        val fadeH = dpi(32) + if (selecting) 0 else bottomInset
        if (bottomFade.layoutParams.height != fadeH) { bottomFade.layoutParams.height = fadeH; bottomFade.requestLayout() }
        bottomFade.translationY = -(if (selecting) bar.height else 0).toFloat()
        updateFades()
    }

    /** Each edge darkens only while more of the list lies past it. */
    private fun updateFades() {
        if (!::bottomFade.isInitialized) return  // setOnScrollListener calls in at once
        topFade.show(list.canScrollVertically(-1))
        bottomFade.show(list.canScrollVertically(1))
    }

    override fun onDestroy() {
        super.onDestroy()
        Theme.listeners -= onTheme
    }

    /** Runs on every frame of a theme change; touches only colours. */
    private fun applyTheme() {
        root.setBackgroundColor(Theme.bg())
        window.decorView.setBackgroundColor(Theme.bg())
        sticky.setBackgroundColor(Theme.bg())
        title.setTextColor(Theme.text())
        for (b in listOf(themeBtn, updateBtn, clear)) {
            b.setColorFilter(Theme.text())
            (b.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
        themeBtn.setImageResource(if (Theme.light) R.drawable.ic_bulb_off else R.drawable.ic_bulb_on)
        themeBtn.contentDescription = if (Theme.light) "Toggle Dark Mode" else "Toggle Light Mode"
        searchBox.background = pill(Theme.button(), 14f)
        searchIcon.setColorFilter(Theme.sub())
        search.setTextColor(Theme.text())
        search.setHintTextColor(Theme.sub())
        empty.setTextColor(Theme.sub())
        bar.setBackgroundColor(Theme.panel())
        for (i in 0 until bar.childCount) {
            val a = bar.getChildAt(i) as LinearLayout
            (a.findViewWithTag<ImageView>("icon")).setColorFilter(Theme.text())
            (a.findViewWithTag<TextView>("text")).setTextColor(Theme.text())
        }
        for (i in 0 until list.childCount) paintRow(list.getChildAt(i))
        topFade.invalidate(); bottomFade.invalidate()
        Theme.applyBars(window)
    }

    private fun paintRow(v: View) {
        val row = v as? LinearLayout ?: return
        val d = row.tag as? DocState ?: return
        val on = d.uri in selected
        row.setBackgroundColor(if (on) Theme.selected() else 0)
        (row.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        (row.getChildAt(0) as ImageView).apply {
            visibility = if (selecting) View.VISIBLE else View.GONE
            setImageResource(if (on) R.drawable.ic_checked else R.drawable.ic_unchecked)
            setColorFilter(if (on) Theme.accentInk() else Theme.sub())
        }
        val texts = row.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).setTextColor(Theme.text())
        (texts.getChildAt(1) as TextView).setTextColor(Theme.sub())
        (row.getChildAt(2) as ImageView).apply {
            visibility = if (selecting) View.INVISIBLE else View.VISIBLE
            setColorFilter(Theme.sub())
        }
    }

    // ---- search ----

    private fun filter() {
        val q = search.text.toString().trim()
        clear.visibility = if (search.text.isEmpty()) View.GONE else View.VISIBLE
        shown = if (q.isEmpty()) docs else docs.filter { it.name.contains(q, ignoreCase = true) }
        empty.text = if (q.isNotEmpty() && shown.isEmpty()) "No PDF names contain “$q”" else ""
        empty.visibility = if (empty.text.isEmpty()) View.GONE else View.VISIBLE
        rows.notifyDataSetChanged()
        if (q != lastQuery) list.setSelection(0)  // new results start at the top
        lastQuery = q
        list.post { updateFades() }
    }
    private var lastQuery = ""

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---- one file ----

    private fun rowMenu(anchor: View, d: DocState) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Theme.popup(), 12f)
            elevation = dp(8f)
            setPadding(0, dpi(8), 0, dpi(8))
        }
        val pop = PopupWindow(box, WRAP_CONTENT, WRAP_CONTENT, true).apply { elevation = dp(8f); setBackgroundDrawable(null) }
        fun item(icon: Int, text: String, act: () -> Unit) = box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple()
            setPadding(dpi(16), dpi(12), dpi(24), dpi(12))
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(Theme.text()) }, LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(label(text, 15f, Theme.text()).apply { setPadding(dpi(16), 0, 0, 0) })
            setOnClickListener { pop.dismiss(); act() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        item(R.drawable.ic_remove, "Remove from list") {
            modal.confirm("Remove from list?", "“${d.name}” leaves the list until you open it again. The file stays where it is.", "Remove") {
                Store.remove(this, d.uri)
                refresh()
                toast("Removed “${d.name}” from the list")
            }
        }
        item(R.drawable.ic_rename, "Rename file") { rename(d) }
        item(R.drawable.ic_delete, "Delete file") {
            modal.confirm("Delete file?", "“${d.name}” will be deleted from this device. This cannot be undone.", "Delete") {
                if (deleteFile(d)) { refresh(); toast("Deleted “${d.name}”") }
                else toast("Couldn’t delete “${d.name}”: the app that shared it doesn’t allow it")
            }
        }
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        pop.showAtLocation(frame, Gravity.TOP or Gravity.END, dpi(12), at[1])
    }

    private fun rename(d: DocState) {
        modal.input("Rename file", d.name, "Rename") { raw ->
            if (raw.isEmpty() || raw == d.name) return@input
            val name = if (raw.endsWith(".pdf", ignoreCase = true)) raw else "$raw.pdf"
            val newUri = try {
                DocumentsContract.renameDocument(contentResolver, Uri.parse(d.uri), name)
            } catch (e: Exception) { null }
            if (newUri == null) { toast("Couldn’t rename: the app that shared this file doesn’t allow it"); return@input }
            try { contentResolver.takePersistableUriPermission(newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Store.rename(this, d.uri, newUri.toString(), name)
            refresh()
            toast("Renamed to “$name”")
        }
    }

    /** Deletes the file itself, then forgets it. False if its provider refuses. */
    private fun deleteFile(d: DocState): Boolean {
        val u = Uri.parse(d.uri)
        val ok = try { DocumentsContract.deleteDocument(contentResolver, u) } catch (e: Exception) {
            try { contentResolver.delete(u, null, null) > 0 } catch (_: Exception) { false }
        }
        if (ok) {
            try { contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Store.remove(this, d.uri)
        }
        return ok
    }

    // ---- many files ----

    private fun startSelecting(d: DocState) {
        hideKeyboard()
        if (!selecting) {
            selecting = true
            bar.animate().translationY(0f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
        }
        toggle(d)
    }

    private fun toggle(d: DocState) {
        if (!selected.remove(d.uri)) selected += d.uri
        if (selected.isEmpty()) stopSelecting() else { rows.notifyDataSetChanged(); place() }
    }

    private fun stopSelecting() {
        selecting = false
        selected.clear()
        bar.animate().translationY(bar.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
        rows.notifyDataSetChanged()
        place()
    }

    private fun picked() = docs.filter { it.uri in selected }
    private fun count(n: Int) = if (n == 1) "1 file" else "$n files"

    private fun removeSelected() {
        val p = picked()
        modal.confirm("Remove ${count(p.size)} from the list?", "They leave the list until you open them again. The files stay where they are.", "Remove") {
            for (d in p) Store.remove(this, d.uri)
            stopSelecting(); refresh()
            toast("Removed ${count(p.size)} from the list")
        }
    }

    private fun deleteSelected() {
        val p = picked()
        modal.confirm("Delete ${count(p.size)}?", "They will be deleted from this device. This cannot be undone.", "Delete") {
            val failed = p.count { !deleteFile(it) }
            stopSelecting(); refresh()
            toast(if (failed == 0) "Deleted ${count(p.size)}" else "Deleted ${count(p.size - failed)}; ${count(failed)} couldn’t be deleted")
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        docs = Store.all(this)
        filter()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            modal.showing -> modal.cancel()
            selecting -> stopSelecting()
            search.text.isNotEmpty() -> search.setText("")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Updater.resumed(this, home = true)
        fillSizes()
    }

    /** Entries saved before sizes were kept: look them up off the main thread. */
    private fun fillSizes() {
        val missing = docs.filter { it.size <= 0 }.map { it.uri }
        if (missing.isEmpty()) return
        val app = applicationContext
        Thread {
            val found = missing.associateWith { Store.sizeOf(app, it) }.filterValues { it > 0 }
            if (found.isEmpty()) return@Thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Store.setSizes(app, found)
                refresh()
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        Updater.paused(this)
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, pick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == pick && res == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            open(uri)
        }
    }

    private fun open(uri: Uri) {
        startActivity(Intent(this, ReaderActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? LinearLayout) ?: docRow()
            val d = shown[p]
            row.tag = d
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = d.name
            val now = System.currentTimeMillis()
            val ago = if (now - d.opened < DateUtils.MINUTE_IN_MILLIS) "just now"
                else DateUtils.getRelativeTimeSpanString(d.opened, now, DateUtils.MINUTE_IN_MILLIS)
            val parts = ArrayList<CharSequence>()
            if (d.pages > 0) parts += "Page ${d.page + 1} of ${d.pages}"
            parts += ago
            if (d.size > 0) parts += android.text.format.Formatter.formatShortFileSize(this@HomeActivity, d.size)
            (texts.getChildAt(1) as TextView).text = parts.joinToString("  ·  ")
            (row.getChildAt(2) as ImageView).setOnClickListener { v -> rowMenu(v, d) }
            paintRow(row)
            return row
        }

        private fun docRow() = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            foreground = ripple()
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dpi(20), dpi(4), dpi(4), dpi(4))
            addView(ImageView(context), LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(16) })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpi(8), 0, dpi(8))
                addView(label("", 16f, Theme.text()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                addView(label("", 13f, Theme.sub()).apply { setPadding(0, dpi(2), 0, 0) })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_more)
                background = ripple()
                setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
                contentDescription = "File options"
                isFocusable = false
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        }
    }
}
