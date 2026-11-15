package com.issinoho.radiotimes

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Process-wide TV state: the line-up, the guide and now/next, shared by the
 * channel list and the player. It outlives activities so returning from the
 * player doesn't reload anything.
 *
 * Startup is built around what Phase 0 measured on a Fire TV Stick: the
 * channel list is ~0.4 s, a guide from tvdinner's cache ~0.4 s, but a fresh
 * guide ~15 s. So the cached guide (however old) is shown at once and a
 * fresh one is fetched behind it when the cached one is older than
 * [REFRESH_AFTER_MS] or missing.
 */
object Tv {
    sealed interface Lineup {
        data object Idle : Lineup
        data object Loading : Lineup
        data class Ready(val channels: List<Channel>) : Lineup
        data class Failed(val message: String) : Lineup
    }

    /** `updated` is when the guide on screen was fetched from tvtimes (epoch ms). */
    data class Guide(
        val loaded: Boolean = false,
        val updated: Long? = null,
        val busy: Boolean = false,
        val problem: String? = null,
    )

    private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
    private const val TICK_MS = 60 * 1000L
    private const val RETRY_AFTER_MS = 15 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var retryAt = 0L

    private val _lineup = MutableStateFlow<Lineup>(Lineup.Idle)
    val lineup: StateFlow<Lineup> = _lineup.asStateFlow()

    private val _guide = MutableStateFlow(Guide())
    val guide: StateFlow<Guide> = _guide.asStateFlow()

    private val _nowNext = MutableStateFlow<Map<String, NowNext>>(emptyMap())
    val nowNext: StateFlow<Map<String, NowNext>> = _nowNext.asStateFlow()

    /** The channel last played; the list puts focus back on it. */
    private val _lastPlayed = MutableStateFlow<String?>(null)
    val lastPlayed: StateFlow<String?> = _lastPlayed.asStateFlow()

    /** The channels the player steps through with up/down: the group the list was showing. */
    var zapList: List<Channel> = emptyList()

    fun channels(): List<Channel> = (_lineup.value as? Lineup.Ready)?.channels.orEmpty()

    fun init(context: Context) {
        if (_lastPlayed.value == null) _lastPlayed.value = Prefs.lastPlayed(context)
    }

    fun played(context: Context, channel: Channel) {
        _lastPlayed.value = channel.url
        Prefs.setLastPlayed(context, channel.url)
    }

    /** (Re)connect to a tvtimes link. Safe to call again with a new link. */
    fun connect(tvtimesUrl: String, cacheDir: String) {
        job?.cancel()
        _lineup.value = Lineup.Loading
        _guide.value = Guide()
        _nowNext.value = emptyMap()
        retryAt = 0L
        job = scope.launch {
            val configured = Engine.call("configure", tvtimesUrl, cacheDir)
            bridgeError(configured)?.let { _lineup.value = Lineup.Failed(it); return@launch }

            val started = SystemClock.elapsedRealtime()
            val json = Engine.call("load_channels")
            bridgeError(json)?.let { _lineup.value = Lineup.Failed(it); return@launch }
            val channels = parseChannels(json)
            Log.i(TAG, "lineup | ${channels.size} channels in ${SystemClock.elapsedRealtime() - started} ms")
            _lineup.value = Lineup.Ready(channels)

            loadGuide("cached")
            if (needsRefresh()) loadGuide("refresh")

            while (isActive) {
                delay(TICK_MS)
                if (needsRefresh()) loadGuide("refresh") else refreshNowNext()
            }
        }
    }

    private fun needsRefresh(): Boolean {
        if (System.currentTimeMillis() < retryAt) return false
        val g = _guide.value
        val updated = g.updated ?: return true
        return System.currentTimeMillis() - updated > REFRESH_AFTER_MS
    }

    private suspend fun loadGuide(mode: String) {
        _guide.value = _guide.value.copy(busy = true)
        val json = Engine.call("load_guide", mode)
        val error = bridgeError(json)
        if (error != null) {
            Log.w(TAG, "guide | $mode failed: ${redactTokens(error)}")
            // Keep whatever guide is already showing and try again later,
            // not on every tick.
            retryAt = System.currentTimeMillis() + RETRY_AFTER_MS
            _guide.value = _guide.value.copy(busy = false, problem = error)
            return
        }
        val r = JSONObject(json)
        if (!r.optBoolean("loaded")) {
            _guide.value = _guide.value.copy(busy = false)
            return
        }
        logGuide(mode, r)
        val failed = r.optBoolean("network_failed")
        if (failed) retryAt = System.currentTimeMillis() + RETRY_AFTER_MS
        _guide.value = Guide(
            loaded = true,
            updated = if (r.isNull("updated")) null else r.optLong("updated"),
            busy = false,
            problem = if (failed) "Couldn't reach tvtimes for a fresh guide" else null,
        )
        refreshNowNext()
    }

    private fun logGuide(mode: String, r: JSONObject) {
        val peak = r.optLong("rss_kb", 0) / 1024
        val line = buildString {
            append("guide | $mode → ${r.optString("source")}")
            if (r.has("download_s")) {
                append(" · %.1f MB · download %.2f s · parse %.2f s".format(
                    r.optLong("bytes") / (1024.0 * 1024.0), r.optDouble("download_s"), r.optDouble("parse_s"),
                ))
            }
            append(" · total %.2f s · %d programmes · peak %d MB".format(r.optDouble("total_s"), r.optInt("programmes"), peak))
            if (r.optBoolean("network_failed")) append(" · NETWORK FAILED, kept stale copy")
        }
        Log.i(TAG, line)
    }

    private suspend fun refreshNowNext() {
        val json = Engine.call("now_next", System.currentTimeMillis())
        if (bridgeError(json) == null) _nowNext.value = parseNowNext(json)
    }
}

object Prefs {
    private const val FILE = "radiotimes"
    private const val KEY_URL = "tvtimes_url"
    private const val KEY_LAST = "last_played"
    private const val KEY_PLEX = "plex_links"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun tvtimesUrl(context: Context): String? = prefs(context).getString(KEY_URL, null)?.ifBlank { null }
    fun setTvtimesUrl(context: Context, url: String) = prefs(context).edit().putString(KEY_URL, url).apply()
    fun lastPlayed(context: Context): String? = prefs(context).getString(KEY_LAST, null)
    fun setLastPlayed(context: Context, url: String) = prefs(context).edit().putString(KEY_LAST, url).apply()
    fun plexLinks(context: Context): List<String> = splitLinks(prefs(context).getString(KEY_PLEX, null).orEmpty())
    fun setPlexLinks(context: Context, links: List<String>) =
        prefs(context).edit().putString(KEY_PLEX, links.joinToString("\n")).apply()

    /** Links typed or sent over adb, separated by spaces, commas or newlines. */
    fun splitLinks(text: String): List<String> = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
}
