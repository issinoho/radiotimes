package com.issinoho.radiotimes

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One row of a Plex server's tree, as bridge.py's `_node_json` sends it. */
data class PlexNode(
    val ratingKey: String,
    val title: String,
    val kind: String,
    val container: Boolean,
    val subtitle: String?,
    val thumb: String?,
    val watched: Boolean,
    val progress: Float?,
    val year: String?,
    val seriesTitle: String?,
    /** An episode's season poster: a portrait image for grids, where its own 16:9 still would be cropped. */
    val seasonThumb: String?,
    /** Kept so the node can be handed back to the bridge unchanged. */
    val json: String,
) {
    val isEpisode get() = kind == "episode"
}

data class PlexServer(val index: Int, val name: String, val error: String?)

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

fun parsePlexNode(o: JSONObject) = PlexNode(
    ratingKey = o.getString("rating_key"),
    title = o.optString("title"),
    kind = o.optString("kind"),
    container = o.optBoolean("container"),
    subtitle = o.str("subtitle"),
    thumb = o.str("thumb"),
    watched = o.optBoolean("watched"),
    progress = if (o.isNull("progress")) null else o.optDouble("progress").toFloat(),
    year = o.str("year"),
    seriesTitle = o.str("series_title"),
    seasonThumb = o.str("season_thumb"),
    json = o.toString(),
)

fun parsePlexNodes(json: String): List<PlexNode> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { parsePlexNode(arr.getJSONObject(it)) }
}

/**
 * The configured Plex servers. Configured once per process (and again when
 * the links change); the bridge holds the credentials, Kotlin only ever
 * refers to a server by index.
 */
object Plex {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _servers = MutableStateFlow<List<PlexServer>>(emptyList())
    val servers: StateFlow<List<PlexServer>> = _servers.asStateFlow()
    private var configuredWith: List<String>? = null

    /**
     * End a server-side transcode. Process-wide scope: it must still go out
     * while the player activity is being torn down.
     */
    fun stopTranscode(server: Int, session: String) {
        scope.launch {
            bridgeError(Engine.callPlex("plex_transcode_stop", server, session))?.let {
                Log.w(TAG, "plex | couldn't stop transcode: ${redactTokens(it)}")
            }
        }
    }

    fun configure(links: List<String>, filesDir: String) {
        if (links == configuredWith) return
        configuredWith = links
        if (links.isEmpty()) {
            _servers.value = emptyList()
            return
        }
        scope.launch {
            val json = Engine.callPlex("plex_configure", JSONArray(links).toString(), filesDir)
            bridgeError(json)?.let {
                Log.w(TAG, "plex | configure failed: ${redactTokens(it)}")
                _servers.value = emptyList()
                return@launch
            }
            val arr = JSONArray(json)
            _servers.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlexServer(i, o.optString("name").ifEmpty { "Plex ${i + 1}" }, o.str("error"))
            }
            Log.i(TAG, "plex | ${_servers.value.joinToString { s -> s.error?.let { "#${s.index} invalid" } ?: s.name }}")
        }
    }
}
