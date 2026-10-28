package com.ursapdf

import android.annotation.SuppressLint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.FormEditRecord
import android.graphics.pdf.models.FormWidgetInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import java.io.File

/** One form field on a page. [rect] is in page points, top-left origin. Types are Pdfium.*. */
class Field(
    val index: Int, val type: Int, val rect: RectF, val value: String, val checked: Boolean,
    val options: List<String>, val selected: Set<Int>, val readOnly: Boolean,
    val multiline: Boolean, val label: String,
) {
    fun sameState(o: Field) = value == o.value && checked == o.checked && selected == o.selected
    val editable get() = !readOnly && type in listOf(Pdfium.CHECKBOX, Pdfium.RADIOBUTTON, Pdfium.COMBOBOX, Pdfium.LISTBOX, Pdfium.TEXTFIELD)
}

/**
 * Search and form editing for one open document. Every callback runs on the main
 * thread. Two implementations: the system renderer (Android 15) and the downloaded
 * engine ([Libraries]). [live]: page renders already show edits; otherwise the page
 * view paints edited values over the page itself.
 */
interface Tools {
    val canSearch: Boolean
    val live: Boolean
    fun isForm(cb: (Boolean) -> Unit)
    /** Starts a search for [q] (null stops one). Matches arrive page by page. */
    fun search(q: String?, onMatches: (page: Int, rects: List<List<RectF>>) -> Unit, onDone: () -> Unit)
    fun fields(page: Int, cb: (List<Field>) -> Unit)
    fun setText(page: Int, f: Field, text: String, cb: (List<Field>) -> Unit)
    fun click(page: Int, f: Field, cb: (List<Field>) -> Unit)
    fun choose(page: Int, f: Field, option: Int, cb: (List<Field>) -> Unit)
    /** Writes the document with its edits to [out]. */
    fun save(out: File, cb: (Boolean) -> Unit)
    fun close()

    companion object {
        fun of(e: PdfEngine): Tools? = when {
            Features.builtIn -> SystemTools(e)
            Libraries.ready -> EngineTools(e)
            else -> null
        }
    }
}

@SuppressLint("NewApi")
private class SystemTools(private val e: PdfEngine) : Tools {
    override val canSearch = true
    override val live = true

    override fun isForm(cb: (Boolean) -> Unit) =
        cb(e.formType == PdfRenderer.PDF_FORM_TYPE_ACRO_FORM || e.formType == PdfRenderer.PDF_FORM_TYPE_XFA_FOREGROUND)

    override fun search(q: String?, onMatches: (Int, List<List<RectF>>) -> Unit, onDone: () -> Unit) {
        val g = e.search(q)
        e.onMatches = { gen, page, rects -> if (gen == g) onMatches(page, rects) }
        e.onSearchDone = { gen -> if (gen == g) onDone() }
    }

    private fun read(page: Int): List<Field> = e.workerPage(page).formWidgetInfos.map { w ->
        val type = when (w.widgetType) {
            FormWidgetInfo.WIDGET_TYPE_PUSHBUTTON -> Pdfium.PUSHBUTTON
            FormWidgetInfo.WIDGET_TYPE_CHECKBOX -> Pdfium.CHECKBOX
            FormWidgetInfo.WIDGET_TYPE_RADIOBUTTON -> Pdfium.RADIOBUTTON
            FormWidgetInfo.WIDGET_TYPE_COMBOBOX -> Pdfium.COMBOBOX
            FormWidgetInfo.WIDGET_TYPE_LISTBOX -> Pdfium.LISTBOX
            FormWidgetInfo.WIDGET_TYPE_TEXTFIELD -> Pdfium.TEXTFIELD
            FormWidgetInfo.WIDGET_TYPE_SIGNATURE -> Pdfium.SIGNATURE
            else -> 0
        }
        val items = w.listItems.orEmpty()
        val value = w.textValue.orEmpty()
        Field(
            w.widgetIndex, type, RectF(w.widgetRect), value,
            checked = value.equals("true", true) || value.equals("on", true) || value.equals("yes", true),
            options = items.map { it.label }, selected = items.indices.filter { items[it].isSelected }.toSet(),
            readOnly = w.isReadOnly, multiline = w.isMultiLineText, label = w.accessibilityLabel.orEmpty(),
        )
    }

    override fun fields(page: Int, cb: (List<Field>) -> Unit) = e.run {
        val f = try { read(page) } catch (t: Throwable) { emptyList() }
        e.post { cb(f) }
    }

    private fun edit(page: Int, record: FormEditRecord, cb: (List<Field>) -> Unit) = e.run {
        val f = try { e.workerPage(page).applyEdit(record); read(page) } catch (t: Throwable) { emptyList() }
        e.post { e.refreshPage(page); cb(f) }
    }

    override fun setText(page: Int, f: Field, text: String, cb: (List<Field>) -> Unit) =
        edit(page, FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_SET_TEXT, page, f.index).setText(text).build(), cb)

    override fun click(page: Int, f: Field, cb: (List<Field>) -> Unit) =
        edit(page, FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_CLICK, page, f.index)
            .setClickPoint(Point(f.rect.centerX().toInt(), f.rect.centerY().toInt())).build(), cb)

    override fun choose(page: Int, f: Field, option: Int, cb: (List<Field>) -> Unit) =
        edit(page, FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_SET_INDICES, page, f.index)
            .setSelectedIndices(intArrayOf(option)).build(), cb)

    override fun save(out: File, cb: (Boolean) -> Unit) = e.run {
        val ok = try {
            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                .use { e.workerWrite(it) }
            out.length() > 0
        } catch (t: Throwable) { android.util.Log.w("ursa", "save failed", t); false }
        e.post { cb(ok) }
    }

    override fun close() {}
}

/** The downloaded engine on its own thread (PDFium is single-threaded). */
private class EngineTools(private val e: PdfEngine) : Tools {
    override val canSearch = true
    override val live = false
    private val thread = HandlerThread("ursa-engine").apply { start() }
    private val h = Handler(thread.looper)
    private var doc = 0L
    @Volatile private var searchGen = 0

    init { h.post { doc = Pdfium.open(e.dupFd()) } }

    private fun bg(block: () -> Unit) { h.post { if (doc != 0L) block() } }

    override fun isForm(cb: (Boolean) -> Unit) = bg {
        val t = Pdfium.formType(doc)
        e.post { cb(t == 1 || t == 3) }  // FORMTYPE_ACRO_FORM, FORMTYPE_XFA_FOREGROUND
    }

    override fun search(q: String?, onMatches: (Int, List<List<RectF>>) -> Unit, onDone: () -> Unit) {
        val g = ++searchGen
        if (q.isNullOrEmpty()) return
        // One page per message, so a newer search or an edit is never stuck behind it.
        fun page(i: Int) {
            if (g != searchGen) return
            if (i >= e.pageCount) { e.post { if (g == searchGen) onDone() }; return }
            val a = Pdfium.search(doc, i, q)
            val matches = ArrayList<List<RectF>>()
            var p = 1
            repeat(a.getOrElse(0) { 0f }.toInt()) {
                val n = a[p++].toInt()
                matches += List(n) { RectF(a[p], a[p + 1], a[p + 2], a[p + 3]).also { p += 4 } }
            }
            if (matches.isNotEmpty()) e.post { if (g == searchGen) onMatches(i, matches) }
            h.post { page(i + 1) }
        }
        bg { page(0) }
    }

    private fun read(page: Int): List<Field> {
        val a = JSONArray(Pdfium.widgets(doc, page))
        return List(a.length()) { k ->
            val o = a.getJSONObject(k)
            val opts = o.getJSONArray("options")
            val flags = o.optInt("flags")
            Field(
                o.getInt("i"), o.getInt("type"),
                RectF(o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("r").toFloat(), o.getDouble("b").toFloat()),
                o.optString("value"), o.optBoolean("checked"),
                options = List(opts.length()) { opts.getJSONObject(it).optString("label") },
                selected = (0 until opts.length()).filter { opts.getJSONObject(it).optBoolean("on") }.toSet(),
                readOnly = flags and 1 != 0, multiline = flags and (1 shl 12) != 0,
                label = o.optString("label").ifEmpty { o.optString("name") },
            )
        }
    }

    override fun fields(page: Int, cb: (List<Field>) -> Unit) = bg {
        val f = try { read(page) } catch (t: Throwable) { emptyList() }
        e.post { cb(f) }
    }

    private fun edit(page: Int, op: () -> Unit, cb: (List<Field>) -> Unit) = bg {
        op()
        val f = try { read(page) } catch (t: Throwable) { emptyList() }
        e.post { cb(f) }
    }

    override fun setText(page: Int, f: Field, text: String, cb: (List<Field>) -> Unit) =
        edit(page, { Pdfium.setText(doc, page, f.index, text) }, cb)

    override fun click(page: Int, f: Field, cb: (List<Field>) -> Unit) =
        edit(page, { Pdfium.click(doc, page, f.index) }, cb)

    override fun choose(page: Int, f: Field, option: Int, cb: (List<Field>) -> Unit) =
        edit(page, { Pdfium.choose(doc, page, f.index, option) }, cb)

    override fun save(out: File, cb: (Boolean) -> Unit) = bg {
        val ok = try {
            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                .use { Pdfium.save(doc, it.fd) } && out.length() > 0
        } catch (t: Throwable) { false }
        e.post { cb(ok) }
    }

    override fun close() {
        searchGen++
        h.post { if (doc != 0L) Pdfium.close(doc); doc = 0L; thread.quitSafely() }
    }
}
