package com.ursapdf

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the latest GitHub release, downloads its APK and installs it through a
 * PackageInstaller session. Android asks the user to confirm unless this app installed
 * the copy being replaced (Android 12+); after the first self-update it can go silent.
 *
 * Automatic checks: on launch, then hourly while an activity is on screen. A found
 * update downloads in the background and installs the next time the home list is on
 * screen, because installing replaces the app and closes it.
 */
object Updater {

    private const val HOUR = 3_600_000L
    private val main = Handler(Looper.getMainLooper())
    private var foreground: Activity? = null
    private var lastCheck = 0L
    private var checking = false
    private var ready: File? = null       // downloaded, waiting for the home list
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            foreground?.let { auto(it) }
            main.postDelayed(this, HOUR)
        }
    }

    private fun apkFile(c: Context) = File(c.cacheDir, "update/UrsaPDF.apk")

    /** Call from every activity's onResume; [home] is true for the home list. */
    fun resumed(a: Activity, home: Boolean) {
        foreground = a
        if (!ticking) { ticking = true; main.postDelayed(tick, HOUR) }
        announceIfUpdated(a)
        if (home) ready?.let { ready = null; install(a, it) }
        if (lastCheck == 0L || System.currentTimeMillis() - lastCheck >= HOUR) auto(a)
    }

    fun paused(a: Activity) {
        if (foreground === a) foreground = null
    }

    /** After a self-update the app restarts on the new version; say so once. */
    private fun announceIfUpdated(a: Activity) {
        val p = a.getSharedPreferences("ursa", Context.MODE_PRIVATE)
        val seen = p.getString("version", null)
        if (seen != BuildConfig.VERSION_NAME) {
            p.edit().putString("version", BuildConfig.VERSION_NAME).apply()
            if (seen != null && newer(BuildConfig.VERSION_NAME, seen))
                Toast.makeText(a, "UrsaPDF updated to ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
        }
    }

    private fun latest(): Pair<String, String> {
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        if (c.responseCode == 404) error("No release published yet")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = j.getString("tag_name").removePrefix("v")
        val assets = j.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) return tag to a.getString("browser_download_url")
        }
        error("Release $tag has no APK")
    }

    private fun fetch(c: Context, url: String, progress: ((Int) -> Unit)? = null): File {
        val f = apkFile(c)
        f.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        val total = conn.contentLengthLong
        conn.inputStream.use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) progress?.invoke((done * 100 / total).toInt())
                }
            }
        }
        return f
    }

    /** Silent check: no messages unless an update is found and ready. */
    private fun auto(a: Activity) {
        if (checking) return
        checking = true
        lastCheck = System.currentTimeMillis()
        val app = a.applicationContext
        Thread {
            val file = try {
                val (tag, url) = latest()
                if (newer(tag, BuildConfig.VERSION_NAME)) fetch(app, url) else null
            } catch (e: Exception) {
                null
            }
            main.post {
                checking = false
                if (file == null) return@post
                val f = foreground
                if (f is HomeActivity) install(f, file) else ready = file
            }
        }.start()
    }

    /** The menu item: says what it finds, shows download progress, installs at once. */
    fun check(a: Activity) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show()
        lastCheck = System.currentTimeMillis()
        Thread {
            val result = try { Result.success(latest()) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                result.onFailure { Toast.makeText(a, "Update check failed: ${it.message}", Toast.LENGTH_LONG).show() }
                result.onSuccess { (tag, url) ->
                    val isNewer = newer(tag, BuildConfig.VERSION_NAME)
                    AlertDialog.Builder(a, Theme.dialogTheme())
                        .setTitle(if (isNewer) "Update available" else "Up to date")
                        .setMessage(if (isNewer) "UrsaPDF $tag is available. You have ${BuildConfig.VERSION_NAME}."
                            else "You have the latest version (${BuildConfig.VERSION_NAME}).")
                        .setPositiveButton(if (isNewer) "Download" else "OK") { _, _ -> if (isNewer) download(a, url) }
                        .apply { if (!isNewer) setNeutralButton("Reinstall") { _, _ -> download(a, url) } }
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
            val file = try {
                fetch(a.applicationContext, url) { p -> a.runOnUiThread { bar.progress = p } }
            } catch (e: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
                null
            }
            a.runOnUiThread {
                dlg.dismiss()
                if (file != null) install(a, file)
            }
        }.start()
    }

    /** Hand the APK to PackageInstaller; [InstallReceiver] shows a confirmation if one is needed. */
    private fun install(a: Activity, file: File) {
        try {
            val pi = a.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(a.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("UrsaPDF.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(a, id, Intent(a, InstallReceiver::class.java), flags)
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Toast.makeText(a, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Install results. A confirmation the system needs is shown at once. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            // The update closed the app; updates install from the home list, so reopen it.
            PackageInstaller.STATUS_SUCCESS -> try {
                c.startActivity(Intent(c, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            else -> Toast.makeText(c, "Update failed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG).show()
        }
    }
}
