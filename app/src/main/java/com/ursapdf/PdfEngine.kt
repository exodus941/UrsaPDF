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
import android.os.Handler
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
        Thread(::loop, "ursa-render").apply { priority = Thread.NORM_PRIORITY + 1; start() }
    }

    fun req(kind: Int, page: Int, level: Int, tx: Int, ty: Int, w: Int, h: Int, scale: Float, x: Float = 0f, y: Float = 0f) =
        if (kind == FULL) Req(key(kind, page, level, tx, ty), gen, page, w, h, scale, x, y, 0f, 0f)
        else Req(key(kind, page, level, tx, ty), gen, page, w, h, scale, x, y, crop.left, crop.top)

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
        base.evictAll(); tiles.evictAll(); thumbs.evictAll()
        synchronized(lock) { queues.forEach { it.clear() } }
        notifyListeners()
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun close() {
        closed = true
        listeners.clear()
        synchronized(lock) { lock.notify() }
    }

    private fun loop() {
        while (true) {
            val r: Req
            synchronized(lock) {
                while (!closed && queues.all { it.isEmpty() }) lock.wait()
                if (closed) {
                    openPage?.close(); renderer.close(); pfd.close()
                    return
                }
                r = queues.first { it.isNotEmpty() }.removeFirst()
                busy.add(r.key)
            }
            val bmp = try { render(r) } catch (e: Throwable) { null }
            main.post {
                synchronized(lock) { busy.remove(r.key) }
                if (closed) return@post
                if (bmp != null && r.gen == gen) cacheFor(r.key).put(r.key, bmp)
                notifyListeners()
            }
        }
    }

    /** Worker. */
    private fun render(r: Req): Bitmap {
        if (openIdx != r.page) {
            openPage?.close(); openPage = null
            openPage = renderer.openPage(r.page)
            openIdx = r.page
        }
        val bmp = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(r.scale, r.scale)
        m.postTranslate(-r.cropL * pw[r.page] * r.scale - r.x, -r.cropT * ph[r.page] * r.scale - r.y)
        openPage!!.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp.prepareToDraw()   // start the GPU upload now, not on the frame that draws it
        return bmp
    }
}
