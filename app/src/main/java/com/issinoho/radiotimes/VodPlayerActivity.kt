package com.issinoho.radiotimes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Plex playback (direct play of the file Plex resolves to). Media3's own
 * controller gives the seek bar and transport; the remote's D-pad shows
 * it, play/pause/ffwd/rewind work as media keys.
 *
 * Resumes where Plex says you left off, reports progress to Plex every
 * 10 s (that's what moves its resume point and marks things watched), and
 * at the end of an episode starts the next one.
 */
@OptIn(UnstableApi::class)
class VodPlayerActivity : ComponentActivity() {
    private data class Marker(val startMs: Long, val endMs: Long)
    private data class Sidecar(val url: String, val mime: String, val language: String?, val label: String?)
    private data class Item(
        val title: String, val url: String, val ratingKey: String, val resumeSeconds: Double?,
        val seriesTitle: String?, val parentKey: String?, val grandparentKey: String?,
        val intro: Marker?, val credits: Marker?, val sidecars: List<Sidecar>,
    )

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var server = 0
    private lateinit var item: Item
    private var session = UUID.randomUUID().toString()
    private var reportJob: Job? = null
    private var started = 0L
    private var firstFrameLogged = false
    private val notice = mutableStateOf<String?>(null)
    private var noticeJob: Job? = null
    /** What's playing, for the info banner (OK) and details panel (☰). */
    private val detail = mutableStateOf<PlexDetail?>(null)
    private enum class Info { None, Banner, Full }
    private val info = mutableStateOf(Info.None)
    private val positionMs = mutableStateOf(0L)
    private val picture = mutableStateOf(PictureMode.Fit)
    private var okHeld = false
    /** The ☰ panel's subtitle search: null = showing the panel's buttons. */
    private val subtitleSearch = mutableStateOf<SubtitleSearch?>(null)
    private val durationMs = mutableStateOf(0L)
    /** "Skip intro" / "Skip credits" / "Next episode", while inside a Plex marker. */
    private val skip = mutableStateOf<String?>(null)
    private var markerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        server = intent.getIntExtra(EXTRA_SERVER, 0)
        val itemJson = intent.getStringExtra(EXTRA_ITEM) ?: return finish()
        item = parse(itemJson)
        detail.value = PlexDetail(itemJson)
        picture.value = PictureMode.load(this)
        if (intent.getBooleanExtra(EXTRA_FROM_START, false)) item = item.copy(resumeSeconds = null)

        setContent {
            RadiotimesTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                                setShowSubtitleButton(true)
                                controllerShowTimeoutMs = 5_000
                                resizeMode = picture.value.resizeMode
                                // The banner goes with the controller it was shown with.
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener { visibility ->
                                        if (visibility != android.view.View.VISIBLE && info.value == Info.Banner) info.value = Info.None
                                    },
                                )
                                player = this@VodPlayerActivity.player
                                view = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val d = detail.value
                    if (d != null && info.value == Info.Banner) {
                        PlexNowPlaying(d, positionMs.value, durationMs.value, Modifier.align(Alignment.TopCenter))
                    }
                    if (d != null && info.value == Info.Full) {
                        PlexNowPlayingFull(
                            d, positionMs.value, durationMs.value, Modifier.align(Alignment.CenterStart),
                            picture = picture.value,
                            onPicture = ::cyclePicture,
                            search = subtitleSearch.value,
                            onFindSubtitles = ::findSubtitles,
                            onPickSubtitle = ::attachSubtitle,
                        )
                    }
                    skip.value?.let {
                        Text(
                            "$it   ›  OK",
                            color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            // Above the controller's seek bar, which it would otherwise cover.
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp).clip(RoundedCornerShape(24.dp))
                                .background(Color.White).padding(horizontal = 22.dp, vertical = 12.dp),
                        )
                    }
                    notice.value?.let {
                        Column(
                            Modifier.align(Alignment.TopStart).padding(40.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(it, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    private fun parse(json: String): Item {
        val o = JSONObject(json)
        fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        fun marker(k: String) = o.optJSONObject(k)?.let { m ->
            Marker((m.optDouble("start") * 1000).toLong(), (m.optDouble("end") * 1000).toLong())
        }
        val subs = o.optJSONArray("sidecar_subtitles")
        return Item(
            intro = marker("intro"),
            credits = marker("credits"),
            sidecars = (0 until (subs?.length() ?: 0)).mapNotNull { i ->
                subs!!.optJSONObject(i)?.let { Sidecar(it.getString("url"), it.getString("mime"), it.optString("language").ifEmpty { null }, it.optString("label").ifEmpty { null }) }
            },
            title = o.optString("title"),
            url = o.getString("url"),
            ratingKey = o.getString("rating_key"),
            resumeSeconds = if (o.isNull("resume_seconds")) null else o.optDouble("resume_seconds"),
            seriesTitle = s("series_title"),
            parentKey = s("parent_rating_key"),
            grandparentKey = s("grandparent_rating_key"),
        )
    }

    override fun onStart() {
        super.onStart()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(StreamResolver.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Fires again after every seek; only the start is interesting.
                if (firstFrameLogged) return
                firstFrameLogged = true
                val f = exo.videoFormat
                val a = exo.audioFormat
                Log.i(
                    TAG,
                    "vod | ${item.title} first frame ${SystemClock.elapsedRealtime() - started} ms · " +
                        "${f?.sampleMimeType} ${f?.width}x${f?.height} · audio ${a?.sampleMimeType} ${a?.channelCount}ch",
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && exo.playbackState == Player.STATE_READY) report("paused")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    report("stopped")
                    playNextEpisode()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "vod | ${item.title}: ${error.errorCodeName}: ${redactTokens(error.message.orEmpty())}")
                showNotice("Can't play this: ${error.errorCodeName}", sticky = true)
            }
        })
        player = exo
        view?.player = exo
        load(item)
        reportJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (player?.isPlaying == true) report("playing")
            }
        }
        markerJob = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                skip.value = skipLabel()
                player?.let { p ->
                    positionMs.value = p.currentPosition
                    durationMs.value = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                }
            }
        }
    }

    private fun skipLabel(): String? {
        val pos = player?.currentPosition ?: return null
        item.intro?.let { if (pos in it.startMs until it.endMs) return "Skip intro" }
        item.credits?.let { if (pos in it.startMs until it.endMs) return if (item.parentKey != null) "Next episode" else "Skip credits" }
        return null
    }

    /** OK while a skip button shows: past the intro, or past the credits (to the next episode). */
    private fun doSkip() {
        val exo = player ?: return
        val pos = exo.currentPosition
        item.intro?.takeIf { pos in it.startMs until it.endMs }?.let { exo.seekTo(it.endMs); skip.value = null; return }
        item.credits?.takeIf { pos in it.startMs until it.endMs }?.let {
            skip.value = null
            if (item.parentKey != null) {
                report("stopped")
                playNextEpisode(immediately = true)
            } else {
                exo.seekTo(it.endMs)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        reportJob?.cancel()
        markerJob?.cancel()
        report("stopped")
        view?.player = null
        player?.release()
        player = null
    }

    private fun load(next: Item) {
        item = next
        session = UUID.randomUUID().toString()
        started = SystemClock.elapsedRealtime()
        firstFrameLogged = false
        val exo = player ?: return
        val resumeMs = next.resumeSeconds?.takeIf { it > 30 }?.let { (it * 1000).toLong() } ?: C.TIME_UNSET
        exo.setMediaItem(mediaItem(next, selected = null), resumeMs.takeIf { it != C.TIME_UNSET } ?: 0L)
        exo.playWhenReady = true
        exo.prepare()
        val title = listOfNotNull(next.seriesTitle, next.title).joinToString(" · ")
        showNotice(if (resumeMs != C.TIME_UNSET) "$title — resuming at ${hms(resumeMs)}" else title)
    }

    /**
     * The file plus Plex's sidecar subtitle files (not inside the media file;
     * they join the file's own tracks in the controller's CC menu). `selected`
     * is marked default, so ExoPlayer switches it on.
     */
    private fun mediaItem(it: Item, selected: String?): MediaItem {
        val subtitles = it.sidecars.map { sc ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sc.url))
                .setMimeType(sc.mime)
                .setLanguage(sc.language)
                .setLabel(sc.label)
                .setSelectionFlags(if (sc.url == selected) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder().setUri(it.url).setSubtitleConfigurations(subtitles).build()
    }

    private fun cyclePicture() {
        val mode = picture.value.next()
        picture.value = mode
        PictureMode.save(this, mode)
        view?.resizeMode = mode.resizeMode
        showNotice("Picture: ${mode.label}")
    }

    /** Plex's own online subtitle search, in the device's language. */
    private fun findSubtitles() {
        val language = java.util.Locale.getDefault().language.ifEmpty { "en" }
        subtitleSearch.value = SubtitleSearch.Searching
        lifecycleScope.launch {
            val json = Engine.callPlex("plex_subtitle_search", server, item.ratingKey, language)
            val error = bridgeError(json)
            subtitleSearch.value = if (error != null) {
                SubtitleSearch.Failed(redactTokens(error))
            } else {
                val arr = org.json.JSONArray(json)
                val found = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SubtitleResult(o.getString("key"), o.optString("title"), o.optString("provider"), o.optString("codec", "srt"), o.optString("language", language))
                }
                if (found.isEmpty()) SubtitleSearch.Failed("No subtitles found online for this.") else SubtitleSearch.Results(found)
            }
        }
    }

    /** Plex downloads the pick and attaches it to the item; play on with it switched on. */
    private fun attachSubtitle(pick: SubtitleResult) {
        subtitleSearch.value = SubtitleSearch.Attaching(pick.title)
        val before = item.sidecars.map { it.url }.toSet()
        lifecycleScope.launch {
            val json = Engine.callPlex("plex_subtitle_attach", server, item.ratingKey, pick.key, pick.codec, pick.language)
            bridgeError(json)?.let { subtitleSearch.value = SubtitleSearch.Failed(redactTokens(it)); return@launch }
            val arr = org.json.JSONArray(json)
            val sidecars = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Sidecar(it.getString("url"), it.getString("mime"), it.optString("language").ifEmpty { null }, it.optString("label").ifEmpty { null }) }
            }
            val added = sidecars.firstOrNull { it.url !in before } ?: sidecars.lastOrNull()
            if (added == null) {
                subtitleSearch.value = SubtitleSearch.Failed("Plex didn't attach a subtitle file.")
                return@launch
            }
            item = item.copy(sidecars = sidecars)
            val exo = player ?: return@launch
            val position = exo.currentPosition
            exo.setMediaItem(mediaItem(item, selected = added.url), position)
            exo.prepare()
            Log.i(TAG, "vod | subtitles attached from Plex's online search (${added.language ?: "?"})")
            subtitleSearch.value = null
            info.value = Info.None
            showNotice("Subtitles on: ${added.label ?: added.language ?: "online"}")
        }
    }

    /** Reported on the main thread's state; the network call happens on the Plex thread. */
    private fun report(state: String) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (state == "stopped" && exo.playbackState == Player.STATE_ENDED) duration else exo.currentPosition
        val key = item.ratingKey
        val sessionId = session
        lifecycleScope.launch {
            val json = Engine.callPlex("plex_report", server, sessionId, key, state, position / 1000.0, duration / 1000.0)
            bridgeError(json)?.let { Log.w(TAG, "vod | report $state failed: ${redactTokens(it)}") }
        }
    }

    private fun playNextEpisode(immediately: Boolean = false) {
        val parent = item.parentKey ?: return finish()
        val current = item.ratingKey
        val grandparent = item.grandparentKey
        lifecycleScope.launch {
            val nextJson = Engine.callPlex("plex_next_episode", server, current, parent, grandparent)
            if (bridgeError(nextJson) != null || nextJson.trim() == "null") return@launch finish()
            val node = parsePlexNode(JSONObject(nextJson))
            showNotice("Up next: ${node.title}", sticky = true)
            if (!immediately) delay(5_000)
            if (player == null) return@launch
            val resolved = Engine.callPlex("plex_resolve", server, node.json)
            if (bridgeError(resolved) != null) return@launch finish()
            detail.value = PlexDetail(resolved)
            load(parse(resolved))
        }
    }

    private fun showNotice(text: String, sticky: Boolean = false) {
        notice.value = text
        noticeJob?.cancel()
        if (!sticky) noticeJob = lifecycleScope.launch { delay(5_000); notice.value = null }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val v = view
        val ok = event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        // The remote's media keys work at any time. Left to PlayerView they
        // only reach it once it has focus, i.e. after OK showed the
        // controller -- so play/pause did nothing until OK was pressed.
        val media = intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        )
        val exo = player
        if (exo != null && event.keyCode in media) {
            val seek = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            // On the press only; ⏩/⏪ also on key repeat (held down).
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || seek)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> exo.playWhenReady = !exo.playWhenReady
                    KeyEvent.KEYCODE_MEDIA_PLAY -> exo.playWhenReady = true
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> exo.playWhenReady = false
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> exo.seekForward()
                    KeyEvent.KEYCODE_MEDIA_REWIND -> exo.seekBack()
                }
                v?.showController()
            }
            return true
        }
        // ☰ / Info: the full details panel, on and off.
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
            if (event.action == KeyEvent.ACTION_UP) {
                val open = info.value != Info.Full
                info.value = if (open) Info.Full else Info.None
                subtitleSearch.value = null
                if (open) v?.hideController()
            }
            return true
        }
        // Back steps out of the subtitle search, then closes the panel, then
        // the controller, then leaves.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && info.value == Info.Full) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (subtitleSearch.value != null) subtitleSearch.value = null else info.value = Info.None
            }
            return true
        }
        // The panel's buttons and results take the D-pad and OK.
        if (info.value == Info.Full) return super.dispatchKeyEvent(event)
        // A showing skip button takes OK (when the controller is hidden).
        if (skip.value != null && v != null && !v.isControllerFullyVisible && ok) {
            if (event.action == KeyEvent.ACTION_UP) doSkip()
            return true
        }
        if (v != null && v.isControllerFullyVisible && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) v.hideController()
            return true
        }
        // OK while watching: controller plus the info banner, like live TV's
        // banner. The controller's play/pause button has focus, so a second
        // OK pauses; the remote's play/pause key works throughout.
        // Hold OK (controller hidden): next picture size.
        if (v != null && !v.isControllerFullyVisible && ok) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
                okHeld = true
                cyclePicture()
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (okHeld) {
                    okHeld = false
                } else {
                    info.value = Info.Banner
                    v.showController()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_FROM_START = "from_start"

        fun intent(context: Context, server: Int, resolvedJson: String, fromStart: Boolean = false): Intent =
            Intent(context, VodPlayerActivity::class.java)
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_ITEM, resolvedJson)
                .putExtra(EXTRA_FROM_START, fromStart)
    }
}

private fun hms(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun clockMs(ms: Long): String = hms(ms)

data class SubtitleResult(val key: String, val title: String, val provider: String, val codec: String, val language: String)

sealed interface SubtitleSearch {
    data object Searching : SubtitleSearch
    data class Results(val found: List<SubtitleResult>) : SubtitleSearch
    data class Attaching(val title: String) : SubtitleSearch
    data class Failed(val message: String) : SubtitleSearch
}

/** The top banner shown with the controller: what this is and where you are in it. */
@androidx.compose.runtime.Composable
private fun PlexNowPlaying(d: PlexDetail, positionMs: Long, durationMs: Long, modifier: Modifier) {
    val now = rememberNow()
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 56.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
    ) {
        d.poster?.let {
            coil.compose.AsyncImage(
                model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(84.dp, 126.dp).clip(RoundedCornerShape(6.dp)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            val heading = listOfNotNull(
                d.seriesTitle,
                if (d.season != null && d.episode != null) "S${d.season} E${d.episode}" else null,
            ).joinToString("  ·  ")
            if (heading.isNotEmpty()) Text(heading, color = Palette.dim, fontSize = 15.sp)
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.title, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(clockText(now), color = Palette.text, fontSize = 20.sp)
            }
            Text(
                listOfNotNull(d.year, d.contentRating, d.rating?.let { "★ $it" }, d.genres.take(3).joinToString(" · ").ifEmpty { null })
                    .joinToString("   ·   "),
                color = Palette.dim, fontSize = 14.sp,
            )
            d.description?.let {
                Text(it, color = Palette.text, fontSize = 15.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (durationMs > 0) {
                val left = (durationMs - positionMs).coerceAtLeast(0)
                Text(
                    "${clockMs(positionMs)} / ${clockMs(durationMs)}   ·   ${left / 60_000} min left   ·   ends at ${clockText(now + left)}",
                    color = Palette.dim, fontSize = 14.sp,
                )
            }
            Text("☰  more about this", color = Palette.dim, fontSize = 13.sp)
        }
    }
}

/** ☰: everything about what's playing, over the left of the picture. */
@androidx.compose.runtime.Composable
private fun PlexNowPlayingFull(
    d: PlexDetail,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier,
    picture: PictureMode,
    onPicture: () -> Unit,
    search: SubtitleSearch?,
    onFindSubtitles: () -> Unit,
    onPickSubtitle: (SubtitleResult) -> Unit,
) {
    val now = rememberNow()
    Column(
        modifier
            .width(560.dp)
            .fillMaxHeight()
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF20B0A1F), Color(0xCC0B0A1F), Color.Transparent)))
            .padding(start = 48.dp, end = 40.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        val heading = listOfNotNull(
            d.seriesTitle,
            d.season?.let { "Season $it" }, d.episode?.let { "Episode $it" },
        ).joinToString("  ·  ")
        if (heading.isNotEmpty()) Text(heading, color = Palette.dim, fontSize = 16.sp)
        Text(d.title, color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(
            listOfNotNull(d.year, d.contentRating, d.durationMs?.let { "${it / 60_000} min" }, d.rating?.let { "★ $it" }).joinToString("   ·   "),
            color = Palette.text, fontSize = 15.sp,
        )
        if (d.genres.isNotEmpty()) Text(d.genres.joinToString(" · "), color = Palette.accent, fontSize = 15.sp)
        d.tagline?.let { Text(it, color = Palette.dim, fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
        // Bounded so the panel's buttons stay on screen (1080p is 540dp tall).
        d.description?.let { Text(it, color = Palette.text, fontSize = 16.sp, lineHeight = 23.sp, maxLines = if (search == null) 5 else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        d.director?.let { Text("Director  $it", color = Palette.dim, fontSize = 14.sp) }
        if (d.cast.isNotEmpty()) Text("Cast  ${d.cast.joinToString(", ")}", color = Palette.dim, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        d.studio?.let { Text("Studio  $it", color = Palette.dim, fontSize = 14.sp) }
        listOfNotNull(d.video, d.audio).takeIf { it.isNotEmpty() }?.let { Text("File  ${it.joinToString("  ·  ")}", color = Palette.dim, fontSize = 14.sp) }
        if (durationMs > 0) {
            val left = (durationMs - positionMs).coerceAtLeast(0)
            ProgressBar((positionMs.toFloat() / durationMs).coerceIn(0f, 1f), Modifier.width(420.dp))
            Text("${left / 60_000} min left  ·  ends at ${clockText(now + left)}", color = Palette.dim, fontSize = 14.sp)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        PanelOptions(picture, onPicture, search, onFindSubtitles, onPickSubtitle)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(if (search != null) "Back: return" else "☰ or Back to close", color = Palette.dim, fontSize = 13.sp)
    }
}

/** The ☰ panel's buttons, or the online subtitle search in their place. */
@androidx.compose.runtime.Composable
private fun PanelOptions(
    picture: PictureMode,
    onPicture: () -> Unit,
    search: SubtitleSearch?,
    onFindSubtitles: () -> Unit,
    onPickSubtitle: (SubtitleResult) -> Unit,
) {
    val first = androidx.compose.runtime.remember(search == null) { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(search == null, search is SubtitleSearch.Results) {
        repeat(20) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    when (search) {
        null -> androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onPicture,
                modifier = Modifier.focusRequester(first).focusRing(),
            ) { Text("Picture: ${picture.label}") }
            androidx.compose.material3.OutlinedButton(onClick = onFindSubtitles, modifier = Modifier.focusRing()) {
                Text("Find subtitles online")
            }
        }
        SubtitleSearch.Searching -> Text("Searching Plex's online subtitles…", color = Palette.text, fontSize = 15.sp)
        is SubtitleSearch.Attaching -> Text("Downloading “${search.title}”…", color = Palette.text, fontSize = 15.sp, maxLines = 1)
        is SubtitleSearch.Failed -> Text(search.message, color = Palette.warn, fontSize = 15.sp)
        is SubtitleSearch.Results -> {
            Text("Choose subtitles (from ${search.found.first().provider.ifEmpty { "Plex" }})", color = Palette.dim, fontSize = 14.sp)
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.height(150.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                items(search.found.size) { i ->
                    val r = search.found[i]
                    var focused by androidx.compose.runtime.remember { mutableStateOf(false) }
                    Text(
                        r.title,
                        color = Palette.text, fontSize = 14.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (focused) Palette.focus else Color.Transparent)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onPickSubtitle(r) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
