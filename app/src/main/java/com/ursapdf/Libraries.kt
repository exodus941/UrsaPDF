package com.ursapdf

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.zip.ZipInputStream

/**
 * The downloaded engine (PDFium plus engine/ursa.c) for phones without built-in search
 * and forms. It ships as `ursa-engine-a<api>-v<version>-<abi>.zip` on every GitHub
 * release, with a `.sig` made by the app's own release key: the app checks it against
 * its own signing certificate, so only an engine signed like the app itself loads.
 *
 * Installed into filesDir/engine/v<version>, files read-only (Android 14 requires that
 * of loaded code), and loaded straight into the running app: no install, no restart.
 */
object Libraries {
    val needed get() = !Features.builtIn
    var ready = false; private set
    private var loadedVersion = 0

    private fun root(c: Context) = File(c.filesDir, "engine")
    private fun prefs(c: Context) = c.getSharedPreferences("ursa", Context.MODE_PRIVATE)
    fun installedVersion(c: Context) = prefs(c).getInt("engineVersion", 0)

    private val abi: String get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x64"
        "x86" -> "x86"
        else -> "arm64"
    }

    /** Load the installed engine if there is one; true when the engine can be used. */
    @Synchronized
    fun load(c: Context): Boolean {
        if (ready) return true
        val v = installedVersion(c)
        if (v == 0) return false
        val dir = File(root(c), "v$v")
        return try {
            System.load(File(dir, "libursa.so").absolutePath)
            if (Pdfium.api() != Pdfium.API) return false
            val err = Pdfium.init(File(dir, "libpdfium.so").absolutePath)
            if (err != null) { android.util.Log.w("ursa", "engine: $err"); return false }
            ready = true; loadedVersion = v
            // Older versions are left from a previous update; nothing has them loaded now.
            root(c).listFiles()?.filter { it.name != "v$v" }?.forEach { it.deleteRecursively() }
            true
        } catch (e: Throwable) {
            android.util.Log.w("ursa", "engine load failed", e); false
        }
    }

    class Asset(val version: Int, val zip: String, val sig: String, val size: Long)

    /** The engine on the latest release for this phone, or null. Network; off the main thread. */
    fun latest(): Asset? {
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        val assets = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("assets")
        val urls = HashMap<String, Pair<String, Long>>()
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            urls[a.getString("name")] = a.getString("browser_download_url") to a.optLong("size")
        }
        val re = Regex("ursa-engine-a${Pdfium.API}-v(\\d+)-$abi\\.zip")
        val best = urls.keys.mapNotNull { n -> re.matchEntire(n)?.let { it.groupValues[1].toInt() to n } }.maxByOrNull { it.first } ?: return null
        val sig = urls["${best.second}.sig"] ?: return null
        val zip = urls.getValue(best.second)
        return Asset(best.first, zip.first, sig.first, zip.second)
    }

    /** Download, verify and unpack [a]. Network; off the main thread. Throws with a reason. */
    fun install(c: Context, a: Asset, progress: (done: Long, total: Long) -> Unit) {
        val tmp = File(c.cacheDir, "engine.zip")
        fetch(a.zip, tmp, a.size, progress)
        val sig = fetchBytes(a.sig)
        if (!verified(c, tmp, sig)) { tmp.delete(); error("The download failed its signature check") }

        val dir = File(root(c), "v${a.version}")
        dir.deleteRecursively(); dir.mkdirs()
        ZipInputStream(tmp.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(dir, e.name).canonicalFile
                if (!f.path.startsWith(dir.canonicalPath)) error("Bad entry ${e.name}")
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                f.outputStream().use { z.copyTo(it) }
                f.setWritable(false, false)
            }
        }
        tmp.delete()
        if (!File(dir, "libursa.so").exists() || !File(dir, "libpdfium.so").exists()) error("The download is incomplete")
        prefs(c).edit().putInt("engineVersion", a.version).apply()
    }

    private fun fetch(url: String, to: File, size: Long, progress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: size
        conn.inputStream.use { i ->
            to.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        return conn.inputStream.use { it.readBytes() }
    }

    /** SHA256withRSA over the zip, by the key that signed this very app. */
    @Suppress("DEPRECATION")
    private fun verified(c: Context, zip: File, sig: ByteArray): Boolean {
        val certBytes = if (Build.VERSION.SDK_INT >= 28) {
            c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        } ?: return false
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certBytes.inputStream())
        val v = Signature.getInstance("SHA256withRSA")
        v.initVerify(cert.publicKey)
        zip.inputStream().buffered().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; v.update(buf, 0, n) }
        }
        return v.verify(sig)
    }
}
