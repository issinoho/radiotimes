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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
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
    /** "Skip intro" / "Skip credits" / "Next episode", while inside a Plex marker. */
    private val skip = mutableStateOf<String?>(null)
    private var markerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        server = intent.getIntExtra(EXTRA_SERVER, 0)
        item = parse(intent.getStringExtra(EXTRA_ITEM) ?: return finish())
        if (intent.getBooleanExtra(EXTRA_FROM_START, false)) item = item.copy(resumeSeconds = null)

        setContent {
            RadiotimesTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                                setShowSubtitleButton(true)
                                controllerShowTimeoutMs = 4_000
                                player = this@VodPlayerActivity.player
                                view = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
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
        // Plex's sidecar subtitle files (not inside the media file) join the
        // file's own tracks in the controller's CC menu.
        val subtitles = next.sidecars.map { sc ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sc.url))
                .setMimeType(sc.mime)
                .setLanguage(sc.language)
                .setLabel(sc.label)
                .build()
        }
        val media = MediaItem.Builder().setUri(next.url).setSubtitleConfigurations(subtitles).build()
        exo.setMediaItem(media, resumeMs.takeIf { it != C.TIME_UNSET } ?: 0L)
        exo.playWhenReady = true
        exo.prepare()
        val title = listOfNotNull(next.seriesTitle, next.title).joinToString(" · ")
        showNotice(if (resumeMs != C.TIME_UNSET) "$title — resuming at ${hms(resumeMs)}" else title)
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
            load(parse(resolved))
        }
    }

    private fun showNotice(text: String, sticky: Boolean = false) {
        notice.value = text
        noticeJob?.cancel()
        if (!sticky) noticeJob = lifecycleScope.launch { delay(5_000); notice.value = null }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OK with the controller hidden toggles pause, like every TV player;
        // otherwise the controller (seek bar, buttons) takes the D-pad.
        val v = view
        // A showing skip button takes OK (when the controller is hidden).
        if (skip.value != null && v != null && !v.isControllerFullyVisible &&
            event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.action == KeyEvent.ACTION_UP) doSkip()
            return true
        }
        // Back hides the controller first, then leaves.
        if (v != null && v.isControllerFullyVisible && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) v.hideController()
            return true
        }
        if (v != null && !v.isControllerFullyVisible && event.action == KeyEvent.ACTION_UP &&
            event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        ) {
            player?.let { it.playWhenReady = !it.playWhenReady }
            v.showController()
            return true
        }
        if (v != null && !v.isControllerFullyVisible && event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
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
