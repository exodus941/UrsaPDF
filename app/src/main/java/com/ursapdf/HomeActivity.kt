package com.ursapdf

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class HomeActivity : Activity() {

    private val pick = 1
    private var docs: List<DocState> = emptyList()
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var themeBtn: ImageView
    private lateinit var updateBtn: ImageView
    private lateinit var list: ListView
    private val onTheme: () -> Unit = { applyTheme() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(12), dpi(8), dpi(8))
        }
        title = label("UrsaPDF", 24f, Theme.text(), bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Theme and update buttons, the same 48 dp icon squares as the reader's top bar.
        fun iconButton(icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            contentDescription = name
            setOnClickListener { act() }
        }
        themeBtn = iconButton(R.drawable.ic_bulb_on, "Toggle Light Mode") { Theme.toggle(this) }
        header.addView(themeBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        updateBtn = iconButton(R.drawable.ic_refresh, "Check for Updates") { Updater.check(this) }
        header.addView(updateBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        root.addView(header)

        list = ListView(this).apply {
            divider = null
            selector = getDrawable(android.R.color.transparent)
            clipToPadding = false
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ ->
                if (pos == 0) openPicker() else open(Uri.parse(docs[pos - 1].uri))
            }
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.setOnApplyWindowInsetsListener { v, ins ->
            val s = ins.safe()
            v.setPadding(s[0], s[1], s[2], 0)
            list.setPadding(0, 0, 0, s[3])
            ins
        }
        setContentView(root)
        Theme.listeners += onTheme
        applyTheme()
    }

    override fun onDestroy() {
        super.onDestroy()
        Theme.listeners -= onTheme
    }

    /** Runs on every frame of a theme change; touches only colours. */
    private fun applyTheme() {
        root.setBackgroundColor(Theme.bg())
        window.decorView.setBackgroundColor(Theme.bg())
        title.setTextColor(Theme.text())
        for (b in listOf(themeBtn, updateBtn)) {
            b.setColorFilter(Theme.text())
            (b.background as? RippleDrawable)?.setColor(android.content.res.ColorStateList.valueOf(Theme.ripple()))
        }
        themeBtn.setImageResource(if (Theme.light) R.drawable.ic_bulb_off else R.drawable.ic_bulb_on)
        themeBtn.contentDescription = if (Theme.light) "Toggle Dark Mode" else "Toggle Light Mode"
        for (i in 0 until list.childCount) paintRow(list.getChildAt(i))
        Theme.applyBars(window)
    }

    private fun paintRow(v: View) {
        if (v.tag != "doc") return
        val row = v as LinearLayout
        (row.getChildAt(0) as TextView).setTextColor(Theme.text())
        (row.getChildAt(1) as TextView).setTextColor(Theme.sub())
        (row.background as? RippleDrawable)?.setColor(android.content.res.ColorStateList.valueOf(Theme.ripple()))
    }

    override fun onResume() {
        super.onResume()
        docs = Store.all(this)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        Updater.resumed(this, home = true)
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
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            open(uri)
        }
    }

    private fun open(uri: Uri) {
        startActivity(Intent(this, ReaderActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = docs.size + 1
        override fun getItem(p: Int): Any? = if (p == 0) null else docs[p - 1]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (p == 0) 0 else 1

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            if (p == 0) return convert ?: openRow()
            val row = (convert as? LinearLayout) ?: docRow()
            val d = docs[p - 1]
            (row.getChildAt(0) as TextView).text = d.name
            val now = System.currentTimeMillis()
            val ago = if (now - d.opened < DateUtils.MINUTE_IN_MILLIS) "just now"
                else DateUtils.getRelativeTimeSpanString(d.opened, now, DateUtils.MINUTE_IN_MILLIS)
            (row.getChildAt(1) as TextView).text =
                if (d.pages > 0) "Page ${d.page + 1} of ${d.pages}  ·  $ago" else "$ago"
            paintRow(row)
            return row
        }

        private fun openRow(): View = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pill(ACCENT, 14f)
                setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_add) })
                addView(label("Open a PDF", 16f, BG, bold = true).apply { setPadding(dpi(12), 0, 0, 0) })
            }
            addView(inner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        private fun docRow() = LinearLayout(this@HomeActivity).apply {
            tag = "doc"
            orientation = LinearLayout.VERTICAL
            background = ripple()
            setPadding(dpi(20), dpi(12), dpi(20), dpi(12))
            addView(label("", 16f, Theme.text()).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(label("", 13f, Theme.sub()).apply { setPadding(0, dpi(2), 0, 0) })
        }
    }
}
