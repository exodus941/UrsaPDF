package com.ursapdf

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** What the app remembers about one PDF. Crop is the visible part of each page, 0..1. */
class DocState(val uri: String, var name: String) {
    var page = 0
    var offset = 0f   // fraction of the page above the screen top
    var x = 0f        // horizontal scroll as a fraction of content width
    var zoom = 1f
    var invert = false
    var pages = 0
    var size = 0L     // bytes; 0 until known
    val crop = RectF(0f, 0f, 1f, 1f)
    var opened = 0L
    val bookmarks = sortedMapOf<Int, String>()  // page index to name; "" means unnamed
    var lock = 0f     // zoom lock: the locked zoom, 0 when off
}

/**
 * The recents list, under "docs". A file that leaves the list (removed, or pushed past
 * [MAX]) keeps its crop, bookmarks and zoom lock under "kept" until the app is
 * uninstalled; opening it again brings them back.
 */
object Store {
    private const val MAX = 200

    private fun prefs(c: Context) = c.getSharedPreferences("ursa", Context.MODE_PRIVATE)

    private fun read(j: JSONObject) = DocState(j.getString("u"), j.optString("n", "PDF")).apply {
        page = j.optInt("p")
        offset = j.optDouble("o", 0.0).toFloat()
        x = j.optDouble("x", 0.0).toFloat()
        zoom = j.optDouble("z", 1.0).toFloat()
        invert = j.optBoolean("i", false)
        pages = j.optInt("c")
        size = j.optLong("s")
        crop.set(
            j.optDouble("l", 0.0).toFloat(), j.optDouble("t", 0.0).toFloat(),
            j.optDouble("r", 1.0).toFloat(), j.optDouble("b", 1.0).toFloat()
        )
        opened = j.optLong("ts")
        // 1.5.11 stored bare page numbers; later versions store {p, n}.
        j.optJSONArray("bm")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { o -> bookmarks[o.getInt("p")] = o.optString("n") } ?: run { bookmarks[a.getInt(k)] = "" } }
        lock = j.optDouble("lk", 0.0).toFloat()
    }

    private fun json(d: DocState) = JSONObject().apply {
        put("u", d.uri); put("n", d.name); put("p", d.page)
        put("o", d.offset.toDouble()); put("x", d.x.toDouble()); put("z", d.zoom.toDouble())
        put("c", d.pages); put("i", d.invert); put("s", d.size)
        put("l", d.crop.left.toDouble()); put("t", d.crop.top.toDouble())
        put("r", d.crop.right.toDouble()); put("b", d.crop.bottom.toDouble())
        put("ts", d.opened)
        if (d.bookmarks.isNotEmpty()) put("bm", JSONArray().apply { for ((p, n) in d.bookmarks) put(JSONObject().put("p", p).put("n", n)) })
        if (d.lock > 0f) put("lk", d.lock.toDouble())
    }

    private fun list(c: Context, key: String): MutableList<DocState> {
        val arr = try { JSONArray(prefs(c).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapTo(ArrayList(arr.length())) { read(arr.getJSONObject(it)) }
    }

    fun all(c: Context): MutableList<DocState> = list(c, "docs").apply { sortByDescending { it.opened } }

    /** In the list, else what was kept of it; null for a file never opened. */
    fun get(c: Context, uri: String) = all(c).firstOrNull { it.uri == uri }
        ?: list(c, "kept").firstOrNull { it.uri == uri }

    fun put(c: Context, d: DocState) {
        val list = all(c)
        list.removeAll { it.uri == d.uri }
        list.add(0, d)
        list.sortByDescending { it.opened }
        write(c, list)
    }

    fun remove(c: Context, uri: String) {
        val list = all(c)
        val gone = list.filter { it.uri == uri }
        list.removeAll(gone)
        write(c, list, gone)
    }

    /** Size in bytes from the provider, else from the file itself; 0 if neither answers. */
    fun sizeOf(c: Context, uri: String): Long {
        val u = android.net.Uri.parse(uri)
        try {
            c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (_: Exception) {}
        return try { c.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize.coerceAtLeast(0) } ?: 0 } catch (_: Exception) { 0 }
    }

    /** A renamed file keeps its reading state under its new address and name. */
    fun rename(c: Context, oldUri: String, newUri: String, newName: String) {
        val list = all(c)
        val i = list.indexOfFirst { it.uri == oldUri }
        if (i < 0) return
        val o = list[i]
        list[i] = DocState(newUri, newName).also { d ->
            d.page = o.page; d.offset = o.offset; d.x = o.x; d.zoom = o.zoom; d.invert = o.invert
            d.pages = o.pages; d.size = o.size; d.crop.set(o.crop); d.opened = o.opened; d.bookmarks.putAll(o.bookmarks)
            d.lock = o.lock
        }
        write(c, list)
    }

    /** Record sizes for entries saved before sizes were kept. */
    fun setSizes(c: Context, sizes: Map<String, Long>) {
        val list = all(c)
        for (d in list) sizes[d.uri]?.let { d.size = it }
        write(c, list)
    }

    private fun hasKeep(d: DocState) = d.bookmarks.isNotEmpty() || d.lock > 0f || d.crop != RectF(0f, 0f, 1f, 1f)

    /**
     * The list, at most [MAX]; [dropped] and any entry past [MAX] move to "kept" when they
     * hold a crop, bookmarks or a lock. An entry back in the list leaves "kept".
     */
    private fun write(c: Context, list: List<DocState>, dropped: List<DocState> = emptyList()) {
        val shown = list.take(MAX)
        val uris = shown.mapTo(HashSet()) { it.uri }
        val out = dropped + list.drop(MAX)
        val outUris = out.mapTo(HashSet()) { it.uri }
        val kept = list(c, "kept").filter { it.uri !in uris && it.uri !in outUris } + out.filter { hasKeep(it) }
        prefs(c).edit()
            .putString("docs", JSONArray().apply { for (d in shown) put(json(d)) }.toString())
            .putString("kept", JSONArray().apply { for (d in kept) put(json(d)) }.toString())
            .apply()
    }
}
