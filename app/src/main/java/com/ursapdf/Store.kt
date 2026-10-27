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
    val crop = RectF(0f, 0f, 1f, 1f)
    var opened = 0L
}

object Store {
    private const val MAX = 200

    private fun prefs(c: Context) = c.getSharedPreferences("ursa", Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<DocState> {
        val arr = try { JSONArray(prefs(c).getString("docs", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<DocState>(arr.length())
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            out += DocState(j.getString("u"), j.optString("n", "PDF")).apply {
                page = j.optInt("p")
                offset = j.optDouble("o", 0.0).toFloat()
                x = j.optDouble("x", 0.0).toFloat()
                zoom = j.optDouble("z", 1.0).toFloat()
                invert = j.optBoolean("i", false)
                pages = j.optInt("c")
                crop.set(
                    j.optDouble("l", 0.0).toFloat(), j.optDouble("t", 0.0).toFloat(),
                    j.optDouble("r", 1.0).toFloat(), j.optDouble("b", 1.0).toFloat()
                )
                opened = j.optLong("ts")
            }
        }
        out.sortByDescending { it.opened }
        return out
    }

    fun get(c: Context, uri: String) = all(c).firstOrNull { it.uri == uri }

    fun put(c: Context, d: DocState) {
        val list = all(c)
        list.removeAll { it.uri == d.uri }
        list.add(0, d)
        list.sortByDescending { it.opened }
        write(c, list.take(MAX))
    }

    fun remove(c: Context, uri: String) = write(c, all(c).filter { it.uri != uri })

    private fun write(c: Context, list: List<DocState>) {
        val arr = JSONArray()
        for (d in list) arr.put(JSONObject().apply {
            put("u", d.uri); put("n", d.name); put("p", d.page)
            put("o", d.offset.toDouble()); put("x", d.x.toDouble()); put("z", d.zoom.toDouble())
            put("c", d.pages); put("i", d.invert)
            put("l", d.crop.left.toDouble()); put("t", d.crop.top.toDouble())
            put("r", d.crop.right.toDouble()); put("b", d.crop.bottom.toDouble())
            put("ts", d.opened)
        })
        prefs(c).edit().putString("docs", arr.toString()).apply()
    }
}
