package com.issinoho.radiotimes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "A newer radiotimes is out": asks GitHub for the latest release at most
 * every [CHECK_EVERY_MS] (remembering the answer between launches) and
 * exposes its version when it's newer than this build. Updating is the
 * user's move: Downloader with [DOWNLOADER_CODE] always fetches the latest.
 */
object Updates {
    const val DOWNLOADER_CODE = "8716486"
    private const val LATEST = "https://api.github.com/repos/issinoho/radiotimes/releases/latest"
    // GitHub allows 60 unauthenticated calls an hour per address; a few TVs
    // checking hourly stay far below that, and a release shows within the hour.
    private const val CHECK_EVERY_MS = 60 * 60 * 1000L
    private const val FILE = "radiotimes"
    private const val KEY_TAG = "latest_release"
    private const val KEY_CHECKED = "latest_checked"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checking = false

    /** The newer version available ("0.6.0"), or null when this build is current. */
    private val _available = MutableStateFlow<String?>(null)
    val available: StateFlow<String?> = _available.asStateFlow()

    /** What Settings' "Check for updates" found. */
    sealed interface Found {
        data class Newer(val version: String) : Found
        data object Current : Found
        data class Failed(val message: String) : Found
    }

    sealed interface Install {
        /** The installer is up; the user confirms there. */
        data object Started : Install
        /** Fire OS needs "install unknown apps" allowed for radiotimes first; its settings page is open. */
        data object NeedsPermission : Install
        data class Failed(val message: String) : Install
    }

    /** Ask GitHub now, ignoring the hourly limit (Settings → Check for updates). */
    suspend fun checkNow(context: Context): Found {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val tag = withContext(Dispatchers.IO) { runCatching { fetchLatestTag() } }
        return tag.fold(
            onSuccess = {
                prefs.edit().putString(KEY_TAG, it).putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                Log.i(TAG, "update | checked on request: latest release $it, this is ${BuildConfig.VERSION_NAME}")
                publish(it)
                val latest = it.removePrefix("v")
                if (newer(latest, BuildConfig.VERSION_NAME)) Found.Newer(latest) else Found.Current
            },
            onFailure = { Found.Failed("Couldn't reach GitHub (${it.javaClass.simpleName})") },
        )
    }

    /**
     * Download that release's APK (GitHub, https only) into the cache and
     * open the system installer on it. The archive is checked first: same
     * package, newer version code. Android then refuses anything not signed
     * with this app's key, so nothing else can replace radiotimes this way.
     */
    suspend fun downloadAndInstall(context: Context, version: String, onProgress: (Int) -> Unit): Install {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return Install.NeedsPermission
        }
        val file = java.io.File(context.cacheDir, "updates/radiotimes-$version.apk")
        val downloaded = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.listFiles()?.forEach { it.delete() } // old downloads
                file.parentFile?.mkdirs()
                download("https://github.com/issinoho/radiotimes/releases/download/v$version/radiotimes.apk", file, onProgress)
            }
        }
        downloaded.exceptionOrNull()?.let {
            Log.w(TAG, "update | download failed: ${it.javaClass.simpleName} ${it.message}")
            return Install.Failed("The download failed (${it.javaClass.simpleName})")
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
        @Suppress("DEPRECATION")
        val installedCode = pm.getPackageInfo(context.packageName, 0).versionCode
        @Suppress("DEPRECATION")
        if (info == null || info.packageName != context.packageName || info.versionCode <= installedCode) {
            Log.w(TAG, "update | rejected download: ${info?.packageName} code ${info?.versionCode}, installed $installedCode")
            file.delete()
            return Install.Failed("That download isn't a newer radiotimes")
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        Log.i(TAG, "update | ${info.versionName} downloaded (${file.length() / 1024} KB), opening the installer")
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return Install.Started
    }

    private fun download(url: String, into: java.io.File, onProgress: (Int) -> Unit) {
        var target = URL(url)
        repeat(5) { // GitHub redirects to its download host; https only
            val conn = target.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "radiotimes/${BuildConfig.VERSION_NAME}")
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val next = URL(target, conn.getHeaderField("Location") ?: error("redirect without a location"))
                        check(next.protocol == "https") { "refusing a non-https redirect" }
                        target = next
                        return@repeat
                    }
                    200 -> {
                        val total = conn.contentLengthLong
                        var done = 0L
                        var lastPct = -1
                        conn.inputStream.use { input ->
                            into.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                                    }
                                }
                            }
                        }
                        check(total <= 0 || done == total) { "incomplete download" }
                        return
                    }
                    else -> error("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("too many redirects")
    }

    fun check(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        publish(prefs.getString(KEY_TAG, null))
        if (checking || System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) return
        checking = true
        scope.launch {
            val tag = withContext(Dispatchers.IO) { runCatching { fetchLatestTag() } }
            checking = false
            tag.onSuccess {
                prefs.edit().putString(KEY_TAG, it).putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                Log.i(TAG, "update | latest release $it, this is ${BuildConfig.VERSION_NAME}")
                publish(it)
            }.onFailure { Log.w(TAG, "update | couldn't check: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun publish(tag: String?) {
        val latest = tag?.removePrefix("v") ?: return
        _available.value = latest.takeIf { newer(it, BuildConfig.VERSION_NAME) }
    }

    private fun fetchLatestTag(): String {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "radiotimes/${BuildConfig.VERSION_NAME}")
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return JSONObject(conn.inputStream.bufferedReader().readText()).getString("tag_name")
        } finally {
            conn.disconnect()
        }
    }

    /** Dotted numeric versions; anything after a '-' (pre-release) ranks it below the plain one. */
    internal fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return !a.contains('-') && b.contains('-')
    }
}
