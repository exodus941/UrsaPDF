package com.ursapdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.ext.SdkExtensions
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File

/**
 * One render job. [scale] is output pixels per PDF point; [x],[y] shift the output
 * window from the crop origin, in output pixels. Crop is copied so the job is immutable.
 */
class Req(
    val key: Long, val gen: Int, val page: Int, val w: Int, val h: Int,
    val scale: Float, val x: Float, val y: Float, val cropL: Float, val cropT: Float,
    val pageGen: Int = 0,
)

class BitmapCache(bytes: Int) : LruCache<Long, Bitmap>(bytes) {
    override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
}

/**
 * PdfRenderer behind one worker thread. PdfRenderer allows one open page and is not
 * thread-safe, so every native call stays on the worker. Callers replace their whole
 * wish list each frame; stale wishes vanish without explicit cancel.
 *
 * Everything not marked "worker" is main-thread only.
 */
class PdfEngine private constructor(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer, ctx: Context) {

    companion object {
        const val BASE = 0
        const val TILE = 1
        const val THUMB = 2
        const val FULL = 3

        /** Queues, served in this order. */
        const val Q_URGENT = 0
        const val Q_VIEW = 1
        const val Q_THUMB = 2

        fun key(kind: Int, page: Int, level: Int = 0, tx: Int = 0, ty: Int = 0): Long =
            (kind.toLong() shl 60) or (page.toLong() shl 36) or (level.toLong() shl 24) or
                (tx.toLong() shl 12) or ty.toLong()

        fun kindOf(key: Long) = (key ushr 60).toInt()

        /** Blocking; call off the main thread. */
        fun open(ctx: Context, uri: Uri): PdfEngine {
            var pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open file")
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close(); throw e
            } catch (e: Exception) {
                // Not seekable (some cloud providers stream): copy to cache and retry.
                pfd.close()
                val f = File(ctx.cacheDir, "open.pdf")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 16) } }
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(pfd)
            }
            return PdfEngine(pfd, renderer, ctx)
        }
    }

    val pageCount = renderer.pageCount
    val pw = IntArray(pageCount)
    val ph = IntArray(pageCount)

    val crop = RectF(0f, 0f, 1f, 1f)
    var gen = 0; private set
    val base: BitmapCache
    val tiles: BitmapCache
    val thumbs = BitmapCache(16 shl 20)
    val listeners = ArrayList<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val queues = Array(3) { ArrayDeque<Req>() }
    private val busy = HashSet<Long>()   // rendering or awaiting delivery; guarded by lock
    @Volatile private var closed = false
    private var openIdx = -1             // worker
    private var openPage: PdfRenderer.Page? = null  // worker

    init {
        for (i in 0 until pageCount) renderer.openPage(i).use { pw[i] = it.width; ph[i] = it.height }
        val mc = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val mb = (mc / 3).coerceIn(32, 96)
        base = BitmapCache(mb shl 20)
        tiles = BitmapCache(mb shl 20)
    }

    // Thumbnail box. The strip and the page view (while a page renders) share these bitmaps.
    private val density = ctx.resources.displayMetrics.density
    val thumbW = 56 * density
    val thumbMaxH = 80 * density

    /** Thumbnail size of page [i] under the current crop: width to height. */
    fun thumbSize(i: Int): Pair<Float, Float> {
        val aw = pw[i] * crop.width()
        val ah = ph[i] * crop.height()
        val s = kotlin.math.min(thumbW / aw, thumbMaxH / ah)
        return Pair(aw * s, ah * s)
    }

    fun thumbReq(i: Int): Req {
        val (w, h) = thumbSize(i)
        val s = w / (pw[i] * crop.width())
        return req(THUMB, i, 0, 0, 0, kotlin.math.ceil(w).toInt().coerceAtLeast(1), kotlin.math.ceil(h).toInt().coerceAtLeast(1), s)
    }

    fun req(kind: Int, page: Int, level: Int, tx: Int, ty: Int, w: Int, h: Int, scale: Float, x: Float = 0f, y: Float = 0f) =
        if (kind == FULL) Req(key(kind, page, level, tx, ty), gen, page, w, h, scale, x, y, 0f, 0f, pageGen[page])
        else Req(key(kind, page, level, tx, ty), gen, page, w, h, scale, x, y, crop.left, crop.top, pageGen[page])

    // ---- per-page refresh, after a form edit changes a page ----
    // The old bitmaps keep drawing until the new ones land, so the page never blanks.

    private val pageGen = IntArray(pageCount)
    private val stale = HashSet<Long>()

    fun pageOf(key: Long) = ((key ushr 36) and 0xFFFFFF).toInt()

    /** True when [key] has no bitmap yet, or only one from before its page changed. */
    fun needs(key: Long) = cacheFor(key).get(key) == null || key in stale

    fun refreshPage(i: Int) {
        pageGen[i]++
        for (c in listOf(base, tiles, thumbs)) for (k in c.snapshot().keys) if (pageOf(k) == i) stale += k
        notifyListeners()
    }

    // ---- work that must run on the render thread (PdfRenderer is single-threaded) ----

    private val tasks = ArrayDeque<() -> Unit>()   // guarded by lock

    /** Run [task] on the render thread, ahead of pending renders. */
    fun run(task: () -> Unit) = synchronized(lock) { tasks.addLast(task); lock.notify() }

    /** Render thread only: the open page [i]. */
    fun workerPage(i: Int) = page(i)

    /** Render thread only: write the document, form edits included, to [out]. */
    @android.annotation.SuppressLint("NewApi")
    fun workerWrite(out: ParcelFileDescriptor) = renderer.write(out, false)

    /** A second descriptor on the open file, for the downloaded engine (reads are positional). */
    fun dupFd(): Int = pfd.dup().detachFd()

    fun post(r: () -> Unit) = main.post(r)

    /** Built-in form type (PDF_FORM_TYPE_*), or -1 where the system renderer has no forms. */
    @android.annotation.SuppressLint("NewApi")
    val formType: Int = if (Features.builtIn) try { renderer.pdfFormType } catch (e: Throwable) { -1 } else -1

    fun cacheFor(key: Long) = when (kindOf(key)) {
        TILE -> tiles
        THUMB -> thumbs
        else -> base
    }

    /** Replace the wishes of one queue. */
    fun submit(q: Int, list: List<Req>) = synchronized(lock) {
        val d = queues[q]
        d.clear()
        val seen = HashSet<Long>()
        for (r in list) if (r.key !in busy && seen.add(r.key)) d.addLast(r)
        lock.notify()
    }

    /** Document invert, 0 (normal) to 1 (inverted); bitmaps stay normal, paints apply [filter]. */
    var invert = 0f
        set(v) {
            field = v
            filter = if (v <= 0f) null else {
                val a = 1f - 2f * v
                val o = 255f * v
                ColorMatrixColorFilter(floatArrayOf(a, 0f, 0f, 0f, o, 0f, a, 0f, 0f, o, 0f, 0f, a, 0f, o, 0f, 0f, 0f, 1f, 0f))
            }
            notifyListeners()
        }
    var filter: ColorFilter? = null; private set

    fun setCrop(r: RectF) {
        crop.set(r)
        reset()
    }

    /** Drop every cached bitmap; in-flight results of the old generation are discarded. */
    fun reset() {
        gen++
        base.evictAll(); tiles.evictAll(); thumbs.evictAll(); stale.clear()
        synchronized(lock) { queues.forEach { it.clear() } }
        notifyListeners()
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun close() {
        closed = true
        listeners.clear()
        synchronized(lock) { lock.notify() }
    }

    // ---- text search ----
    // Android 15's PdfRenderer reads page text (also on 11-14 with SDK extension 13).
    // A search runs one page at a time on the worker, after any pending renders, so a
    // long document stays responsive while it is searched.

    val canSearch = Features.builtIn

    private var searchQ: String? = null   // guarded by lock
    private var searchGen = 0             // guarded by lock
    private var searchNext = 0            // guarded by lock

    /** Called on the main thread with each page's matches (page-point rects), then done. */
    var onMatches: ((gen: Int, page: Int, rects: List<List<RectF>>) -> Unit)? = null
    var onSearchDone: ((gen: Int) -> Unit)? = null

    /** Start searching every page for [q] (null stops). Returns the search's id. */
    fun search(q: String?): Int = synchronized(lock) {
        searchGen++
        searchQ = q?.takeIf { it.isNotEmpty() && canSearch }
        searchNext = 0
        lock.notify()
        searchGen
    }

    private fun loop() {
        while (true) {
            var next: Req? = null
            var q: String? = null
            var qPage = 0
            var qGen = 0
            var task: (() -> Unit)? = null
            synchronized(lock) {
                while (!closed && queues.all { it.isEmpty() } && searchQ == null && tasks.isEmpty()) lock.wait()
                if (closed) {
                    openPage?.close(); renderer.close(); pfd.close()
                    return
                }
                if (tasks.isNotEmpty()) {
                    task = tasks.removeFirst()
                } else if (queues.any { it.isNotEmpty() }) {
                    next = queues.first { it.isNotEmpty() }.removeFirst()
                    busy.add(next!!.key)
                } else {
                    q = searchQ; qPage = searchNext++; qGen = searchGen
                    if (searchNext >= pageCount) searchQ = null
                }
            }
            val t = task
            if (t != null) {
                try { t() } catch (e: Throwable) { android.util.Log.w("ursa", "task failed", e) }
                continue
            }
            val job = next
            if (job == null) {
                if (q != null) searchPage(q!!, qPage, qGen, last = qPage >= pageCount - 1)
                continue
            }
            val r1 = job
            val bmp = try { render(r1) } catch (e: Throwable) { null }
            val r = r1
            main.post {
                synchronized(lock) { busy.remove(r.key) }
                if (closed) return@post
                if (bmp != null && r.gen == gen && r.pageGen == pageGen[r.page]) {
                    cacheFor(r.key).put(r.key, bmp)
                    stale -= r.key
                }
                notifyListeners()
            }
        }
    }

    /** Worker. */
    private fun page(i: Int): PdfRenderer.Page {
        if (openIdx != i) {
            openPage?.close(); openPage = null
            openPage = renderer.openPage(i)
            openIdx = i
        }
        return openPage!!
    }

    /** Worker: one page of a search. Each match is a list of rects (one per line it spans). */
    @android.annotation.SuppressLint("NewApi")
    private fun searchPage(q: String, i: Int, g: Int, last: Boolean) {
        val found = try {
            page(i).searchText(q).map { m -> m.bounds.map { RectF(it) } }
        } catch (e: Throwable) { emptyList() }
        main.post {
            if (closed) return@post
            if (found.isNotEmpty()) onMatches?.invoke(g, i, found)
            if (last) onSearchDone?.invoke(g)
        }
    }

    /** Worker. */
    private fun render(r: Req): Bitmap {
        page(r.page)
        val bmp = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(r.scale, r.scale)
        m.postTranslate(-r.cropL * pw[r.page] * r.scale - r.x, -r.cropT * ph[r.page] * r.scale - r.y)
        openPage!!.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp.prepareToDraw()   // start the GPU upload now, not on the frame that draws it
        return bmp
    }

    // Last, so every field the render thread touches already exists.
    init { Thread(::loop, "ursa-render").apply { priority = Thread.NORM_PRIORITY + 1; start() } }
}
