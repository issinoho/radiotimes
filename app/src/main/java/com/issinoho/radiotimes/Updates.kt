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
