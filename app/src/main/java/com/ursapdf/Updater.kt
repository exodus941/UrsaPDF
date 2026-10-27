package com.ursapdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Reads the latest GitHub release, downloads its APK and hands it to the installer. */
object Updater {

    private var pendingInstall = false

    fun apkFile(a: Activity) = File(a.cacheDir, "update/UrsaPDF.apk")

    fun check(a: Activity) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                if (c.responseCode == 404) Result.failure(Exception("No release published yet"))
                else {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val tag = j.getString("tag_name").removePrefix("v")
                    val assets = j.getJSONArray("assets")
                    var url: String? = null
                    for (i in 0 until assets.length()) {
                        val as_ = assets.getJSONObject(i)
                        if (as_.getString("name").endsWith(".apk")) url = as_.getString("browser_download_url")
                    }
                    if (url == null) Result.failure(Exception("Release $tag has no APK")) else Result.success(tag to url)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                result.onFailure { Toast.makeText(a, "Update check failed: ${it.message}", Toast.LENGTH_LONG).show() }
                result.onSuccess { (tag, url) ->
                    val newer = newer(tag, BuildConfig.VERSION_NAME)
                    AlertDialog.Builder(a, Theme.dialogTheme())
                        .setTitle(if (newer) "Update available" else "Up to date")
                        .setMessage(if (newer) "UrsaPDF $tag is available. You have ${BuildConfig.VERSION_NAME}."
                            else "You have the latest version (${BuildConfig.VERSION_NAME}).")
                        .setPositiveButton(if (newer) "Download" else "OK") { _, _ -> if (newer) download(a, url) }
                        .apply { if (!newer) setNeutralButton("Reinstall") { _, _ -> download(a, url) } }
                        .show()
                }
            }
        }.start()
    }

    /** True when [a] is a higher x.y.z than [b]. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    private fun download(a: Activity, url: String) {
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(a.dpi(24), a.dpi(16), a.dpi(24), a.dpi(8))
        }
        val dlg = AlertDialog.Builder(a, Theme.dialogTheme()).setTitle("Downloading UrsaPDF").setView(bar).setCancelable(false).show()
        Thread {
            val ok = try {
                val f = apkFile(a)
                f.parentFile?.mkdirs()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 30_000
                val total = c.contentLengthLong
                c.inputStream.use { i ->
                    f.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) a.runOnUiThread { bar.progress = (done * 100 / total).toInt() }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
                false
            }
            a.runOnUiThread {
                dlg.dismiss()
                if (ok) install(a)
            }
        }.start()
    }

    private fun install(a: Activity) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            Toast.makeText(a, "Allow UrsaPDF to install updates, then come back", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        pendingInstall = false
        a.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.ursapdf.apk/UrsaPDF.apk"), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Called from onResume: finish an install that waited on the permission screen. */
    fun resumePending(a: Activity) {
        if (pendingInstall && a.packageManager.canRequestPackageInstalls() && apkFile(a).exists()) install(a)
    }
}
