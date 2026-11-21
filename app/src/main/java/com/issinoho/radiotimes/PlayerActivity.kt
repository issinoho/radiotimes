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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen live TV. Up/down (or the channel keys) step through the group
 * the list was showing, OK shows what's on, ☰ (or Info) the full details
 * panel -- the programme's whole description and what's on later, with
 * Guide, Picture and Subtitles buttons, as Plex's player has -- and Back
 * returns to the list.
 *
 * Zapping waits [TUNE_DELAY_MS] after the last key press before opening a
 * stream: many IPTV providers allow one connection per account, so racing
 * through channels must not open (and abandon) one per channel passed.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private sealed interface Status {
        data object Tuning : Status
        data object Playing : Status
        data class Reconnecting(val attempt: Int) : Status
        data class Failed(val message: String) : Status
    }

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var lineup: List<Channel> = emptyList()
    private val index = mutableIntStateOf(0)
    private val status = mutableStateOf<Status>(Status.Tuning)
    private val bannerVisible = mutableStateOf(true)
    private val guideOpen = mutableStateOf(false)
    private val guideInfo = mutableStateOf<GuideInfo?>(null)
    /** The ☰ panel over the left of the picture. */
    private val panelOpen = mutableStateOf(false)
    /** Its details scroll with ↑/↓; back to the top each time it opens. */
    private val panelScroll = androidx.compose.foundation.ScrollState(0)
    private val picture = mutableStateOf(PictureMode.Fit)
    /** Whether the stream has any subtitle track (DVB, CEA-608, WebVTT in HLS), and whether one is showing. */
    private val textAvailable = mutableStateOf(false)
    private val textOn = mutableStateOf(false)
    private var tuneJob: Job? = null
    private var bannerJob: Job? = null
    private var tuneStarted = 0L

    private val channel: Channel? get() = lineup.getOrNull(index.intValue)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        // The list's group if the process is still the one that showed it;
        // otherwise the full line-up; otherwise just this channel.
        lineup = Tv.zapList.takeIf { list -> list.any { it.url == url } }
            ?: Tv.channels().takeIf { list -> list.any { it.url == url } }
            ?: listOf(Channel(0, intent.getStringExtra(EXTRA_NAME).orEmpty(), url, null, null))
        index.intValue = lineup.indexOfFirst { it.url == url }.coerceAtLeast(0)
        guideOpen.value = intent.getBooleanExtra(EXTRA_GUIDE, false)
        picture.value = PictureMode.load(this)

        setContent {
            RadiotimesTheme {
                val nowNext by Tv.nowNext.collectAsState()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // The guide is drawn underneath and the video stays the
                    // same AndroidView, just resized into the corner, so the
                    // stream keeps playing without re-attaching a surface.
                    if (guideOpen.value) {
                        Guide(
                            channels = lineup,
                            currentIndex = index.intValue,
                            info = guideInfo.value,
                            onInfo = { guideInfo.value = it },
                            onTune = ::preview,
                            onWatch = ::watch,
                        )
                    }
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                isFocusable = false
                                resizeMode = picture.value.resizeMode
                                followPicture { picture.value }
                                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                                player = this@PlayerActivity.player
                                view = this
                            }
                        },
                        modifier = if (guideOpen.value) {
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 20.dp, end = 32.dp)
                                .size(GuideVideoWidth, GuideVideoHeight)
                        } else {
                            Modifier.fillMaxSize()
                        },
                    )
                    val current = channel
                    AnimatedVisibility(
                        visible = !guideOpen.value && !panelOpen.value && (bannerVisible.value || status.value is Status.Failed),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        val line = when (val st = status.value) {
                            Status.Tuning -> "Connecting…"
                            is Status.Reconnecting -> "Lost the stream. Reconnecting… (attempt ${st.attempt})"
                            Status.Playing -> null
                            is Status.Failed -> st.message
                        }
                        val previous = previousUrl.value?.let { url -> lineup.firstOrNull { it.url == url } }
                        if (current != null) Banner(current, nowNext[current.url], line, failed = status.value is Status.Failed, previous = previous)
                    }
                    if (current != null && panelOpen.value && !guideOpen.value) {
                        LivePanel(
                            current, nowNext[current.url], Modifier.align(Alignment.CenterStart),
                            onGuide = ::openGuide,
                            picture = picture.value,
                            onPicture = ::cyclePicture,
                            subtitles = if (textAvailable.value) textOn.value else null,
                            onSubtitles = ::toggleSubtitles,
                            scroll = panelScroll,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(StreamResolver.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        // Start playing after 1 s of media rather than ExoPlayer's default
        // 2.5 s: channel changes are what live TV is judged on, and on the
        // Stick first frame was 2-6 s. After a stall it waits 2 s (default
        // 5 s) so a weak stream doesn't stutter straight back into one.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1_000,
                2_000,
            )
            .build()
        // Platform decoders first; FFmpeg (see build.gradle.kts) only for
        // formats they can't handle, such as MPEG Layer II audio.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, LiveExtractorsFactory()))
            .setLoadControl(loadControl)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val f = exo.videoFormat
                val a = exo.audioFormat
                Log.i(
                    TAG,
                    "play | first frame ${SystemClock.elapsedRealtime() - tuneStarted} ms · " +
                        "${f?.sampleMimeType} ${f?.width}x${f?.height} · codecs ${f?.codecs} · " +
                        "audio ${a?.let { "${it.sampleMimeType} ${it.channelCount}ch ${it.sampleRate} Hz" } ?: "none"}",
                )
                val wasReconnecting = status.value is Status.Reconnecting
                status.value = Status.Playing
                lastPlaying = System.currentTimeMillis()
                // A reconnect carries on the same watch; a new channel starts one.
                if (watching?.channel?.url != channel?.url) {
                    endWatch()
                    channel?.let { watching = Watching(it, lastPlaying, Tv.nowNext.value[it.url]?.now) }
                }
                settledUrl = channel?.url
                if (wasReconnecting) Log.i(TAG, "play | ${channel?.name}: reconnected after $reconnectAttempt attempt(s)")
                reconnectAttempt = 0
                reconnectSince = 0L
                showBanner()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                view?.let { applyPicture(it, picture.value) }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val text = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                textAvailable.value = text.any { it.isSupported }
                textOn.value = text.any { it.isSelected }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // A live channel never really ends: a progressive (MPEG-TS)
                // stream whose connection closes looks to ExoPlayer like a
                // file reaching its end, with no error -- the picture just
                // froze. Treat it as a drop.
                if (state == Player.STATE_ENDED && status.value == Status.Playing) reconnect("stream ended")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "play | ${channel?.name}: ${error.errorCodeName}: ${error.message}")
                when {
                    // Fell behind the live window (a pause, a slow link): rejoin live.
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    // Retrying can't help when the device can't play the format.
                    error.errorCode in UNRECOVERABLE -> status.value = Status.Failed(describe(error))
                    else -> reconnect(error.errorCodeName, describe(error))
                }
            }
        })
        exo.playWhenReady = true
        player = exo
        view?.player = exo
        tune(delayMs = 0)
        // Freeze watchdog: playing, yet the position hasn't moved for
        // STALL_MS (stuck buffering, or a provider that keeps the connection
        // open but stops sending) -> reconnect.
        stallJob = lifecycleScope.launch {
            var lastPosition = -1L
            var lastMoved = SystemClock.elapsedRealtime()
            while (true) {
                delay(2_000)
                val p = player ?: continue
                val now = SystemClock.elapsedRealtime()
                if (status.value == Status.Playing && p.currentPosition != lastPosition) {
                    lastPlaying = System.currentTimeMillis()
                    splitWatchAtProgrammeEnd()
                }
                if (status.value != Status.Playing || !p.playWhenReady || p.currentPosition != lastPosition) {
                    lastPosition = p.currentPosition
                    lastMoved = now
                } else if (now - lastMoved > STALL_MS) {
                    lastMoved = now
                    reconnect("no progress for ${STALL_MS / 1000} s")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        endWatch()
        tuneJob?.cancel()
        reconnectJob?.cancel()
        stallJob?.cancel()
        view?.player = null
        player?.release()
        player = null
    }

    /** The channel last actually playing (first frame seen), and the one before it -- for ◀. */
    private var settledUrl: String? = null
    private val previousUrl = mutableStateOf<String?>(null)

    /** Remember where we're leaving from, if it really played (not each channel raced past). */
    private fun noteLeaving(to: Int) {
        val settled = settledUrl ?: return
        if (lineup.getOrNull(to)?.url != settled) previousUrl.value = settled
    }

    /** ◀ / Last: back to the channel watched before this one. */
    private fun flipBack() {
        val i = lineup.indexOfFirst { it.url == previousUrl.value }
        if (i < 0 || i == index.intValue) {
            showBanner()
            return
        }
        noteLeaving(i)
        index.intValue = i
        channel?.let { Tv.played(this, it) }
        tune(delayMs = 0)
    }

    private fun zap(step: Int) {
        if (lineup.size < 2) return
        val to = Math.floorMod(index.intValue + step, lineup.size)
        noteLeaving(to)
        index.intValue = to
        channel?.let { Tv.played(this, it) }
        tune(delayMs = TUNE_DELAY_MS)
    }

    private fun tune(delayMs: Long, retry: Boolean = false) {
        val target = channel ?: return
        if (!retry) {
            endWatch()
            // A new channel (or a fresh start) begins with a clean slate.
            reconnectJob?.cancel()
            reconnectAttempt = 0
            reconnectSince = 0L
            status.value = Status.Tuning
        }
        showBanner()
        tuneJob?.cancel()
        // Stop the old stream at once: it frees the provider connection
        // during the delay, and the picture shouldn't keep showing the
        // channel we've left.
        player?.stop()
        tuneJob = lifecycleScope.launch {
            delay(delayMs)
            tuneStarted = SystemClock.elapsedRealtime()
            val resolved = try {
                withContext(Dispatchers.IO) { StreamResolver.resolve(target.url) }
            } catch (e: Exception) {
                Log.e(TAG, "play | ${target.name}: could not resolve: ${redactTokens(e.message.orEmpty())}")
                reconnect("resolve failed", "Couldn't reach the stream (${redactTokens(e.message.orEmpty())})")
                return@launch
            }
            Log.i(
                TAG,
                "play | ${target.name} → ${StreamResolver.redact(resolved.url)} after ${resolved.hops} redirect(s), " +
                    "type ${resolved.mimeType ?: "progressive (auto)"} by ${resolved.how}, " +
                    "${SystemClock.elapsedRealtime() - tuneStarted} ms",
            )
            val exo = player ?: return@launch
            exo.setMediaItem(
                MediaItem.Builder().setUri(resolved.url).apply { resolved.mimeType?.let { setMimeType(it) } }.build(),
            )
            exo.prepare()
        }
    }

    /** From the guide: tune that channel in the corner, guide stays open. */
    private fun preview(i: Int) {
        noteLeaving(i)
        index.intValue = i
        channel?.let { Tv.played(this, it) }
        tune(delayMs = 0)
    }

    /** From the guide: tune that channel (if it isn't already) and go back to full screen. */
    private fun watch(i: Int) {
        guideOpen.value = false
        guideInfo.value = null
        if (i == index.intValue && status.value != Status.Tuning && status.value !is Status.Failed) {
            showBanner()
            return
        }
        noteLeaving(i)
        index.intValue = i
        channel?.let { Tv.played(this, it) }
        tune(delayMs = 0)
    }

    private var reconnectJob: Job? = null
    private var stallJob: Job? = null

    /**
     * The live watch being timed for tvtimes' watch history: from the first
     * frame to leaving the channel (or the player), cut at programme ends
     * so each interval carries the right title. [lastPlaying] (wall clock,
     * refreshed while the picture moves) is where a watch ends, so time
     * spent reconnecting or frozen before leaving isn't counted.
     */
    private class Watching(val channel: Channel, var start: Long, var programme: Programme?)
    private var watching: Watching? = null
    private var lastPlaying = 0L

    private fun endWatch() {
        val w = watching ?: return
        watching = null
        val end = if (status.value == Status.Playing) System.currentTimeMillis() else lastPlaying
        Tv.recordWatch(w.channel, w.programme?.title, w.start, end)
    }

    private fun splitWatchAtProgrammeEnd() {
        val w = watching ?: return
        val stop = w.programme?.stop ?: return
        if (lastPlaying < stop) return
        Tv.recordWatch(w.channel, w.programme?.title, w.start, stop)
        val nn = Tv.nowNext.value[w.channel.url]
        w.start = stop
        w.programme = listOfNotNull(nn?.now, nn?.next).firstOrNull { it.start <= lastPlaying && it.stop > lastPlaying }
    }

    private var reconnectAttempt = 0
    /** When this outage began (elapsedRealtime), 0 while playing. */
    private var reconnectSince = 0L

    /**
     * Try the channel again after a back-off (1, 2, 4, 8, 15 s, then every
     * 30 s), re-resolving it each time -- tvtimes' redirect target can
     * change. Gives up after [GIVE_UP_MS] without a picture, showing
     * [failure]. A new channel resets it (tune without retry).
     */
    private fun reconnect(why: String, failure: String = "Couldn't keep the stream going") {
        val now = SystemClock.elapsedRealtime()
        if (reconnectSince == 0L) reconnectSince = now
        if (now - reconnectSince > GIVE_UP_MS) {
            Log.w(TAG, "play | ${channel?.name}: giving up after ${(now - reconnectSince) / 1000} s ($why)")
            status.value = Status.Failed(failure)
            return
        }
        reconnectAttempt++
        val wait = BACKOFF_MS[minOf(reconnectAttempt - 1, BACKOFF_MS.lastIndex)]
        Log.i(TAG, "play | ${channel?.name}: $why -> reconnecting in ${wait / 1000} s (attempt $reconnectAttempt)")
        status.value = Status.Reconnecting(reconnectAttempt)
        showBanner()
        player?.stop()
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(wait)
            tune(delayMs = 0, retry = true)
        }
    }

    private fun showBanner() {
        bannerVisible.value = true
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(BANNER_MS)
            if (status.value == Status.Playing) bannerVisible.value = false
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't connect to the stream"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The stream refused the connection (provider limit or channel offline?)"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The stream isn't in a format this player understands"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode the stream"
        else -> "Playback failed (${error.errorCodeName})"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val panelKey = event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO
        if (panelOpen.value) {
            when (event.keyCode) {
                // ☰, Info or Back: close it.
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) closePanel()
                    return true
                }
                // ↑/↓ scroll the details (the buttons sit in one row, so
                // the keys are otherwise unused), as in Plex's panel.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = PANEL_SCROLL_DP * resources.displayMetrics.density
                        // A plain step: animateScrollBy needs the
                        // composition's frame clock (it crashed the Plex player).
                        lifecycleScope.launch {
                            panelScroll.scrollBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) step else -step)
                        }
                    }
                    return true
                }
                // The channel keys still zap; the panel follows the channel.
                KeyEvent.KEYCODE_CHANNEL_UP -> { if (down(event)) zap(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down(event)) zap(-1); return true }
            }
            // The buttons take ← → and OK.
            return super.dispatchKeyEvent(event)
        }
        // Full screen: ☰ / Info open the panel (the guide is its first button).
        if (!guideOpen.value && panelKey) {
            if (event.action == KeyEvent.ACTION_UP) openPanel()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU || (guideOpen.value && event.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    // Back steps out of the info panel first.
                    event.keyCode == KeyEvent.KEYCODE_BACK && guideInfo.value != null -> guideInfo.value = null
                    else -> {
                        guideOpen.value = !guideOpen.value && event.keyCode == KeyEvent.KEYCODE_MENU
                        guideInfo.value = null
                        if (!guideOpen.value) showBanner()
                    }
                }
            }
            return true
        }
        // With the guide open, the D-pad and OK belong to it -- except that
        // the info panel freezes the grid under it (OK still reaches the
        // focused cell, which then means "watch").
        if (guideOpen.value) {
            val arrow = event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            )
            if (guideInfo.value != null && arrow) return true
            return super.dispatchKeyEvent(event)
        }
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (down(event)) zap(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down(event)) zap(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Hold OK: next picture size. A tap: the banner, or with it
                // up, its More button (the ☰ panel).
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
                    okHeld = true
                    cyclePicture()
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (okHeld) {
                        okHeld = false
                    } else if (bannerVisible.value || status.value is Status.Failed) {
                        // The banner's More button: the ☰ panel for remotes
                        // without ☰ (onn / Google TV, some Fire TV ones).
                        openPanel()
                    } else {
                        showBanner()
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (event.action == KeyEvent.ACTION_UP) flipBack()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_UP) showBanner()
                true
            }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    private var okHeld = false

    private fun cyclePicture() {
        val mode = picture.value.next()
        picture.value = mode
        PictureMode.save(this, mode)
        view?.let { applyPicture(it, mode) }
        // The panel's button shows the new size itself.
        if (!panelOpen.value) android.widget.Toast.makeText(this, "Picture: ${mode.label}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openPanel() {
        panelOpen.value = true
        bannerVisible.value = false
        lifecycleScope.launch { panelScroll.scrollTo(0) }
    }

    private fun closePanel() {
        panelOpen.value = false
        showBanner()
    }

    /** The panel's Guide button: the grid, with the channel playing in the corner. */
    private fun openGuide() {
        panelOpen.value = false
        guideInfo.value = null
        guideOpen.value = true
    }

    /** Panel: subtitles off, or back on (a track in the device's language, else any). */
    private fun toggleSubtitles() {
        val exo = player ?: return
        val on = !textOn.value
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !on)
            .apply {
                if (on) {
                    setPreferredTextLanguage(java.util.Locale.getDefault().language)
                    setSelectUndeterminedTextLanguage(true)
                }
            }
            .build()
    }

    /** Key down, including auto-repeat, so holding up/down keeps stepping. */
    private fun down(event: KeyEvent) = event.action == KeyEvent.ACTION_DOWN

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_GUIDE = "guide"
        private const val TUNE_DELAY_MS = 450L
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        private const val GIVE_UP_MS = 10 * 60 * 1000L
        private const val STALL_MS = 20_000L
        private val UNRECOVERABLE = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        private const val BANNER_MS = 5_000L
        /** About four lines of the description per ↑/↓ press. */
        private const val PANEL_SCROLL_DP = 92f

        fun intent(context: Context, channel: Channel, openGuide: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, channel.url)
                .putExtra(EXTRA_NAME, channel.name)
                .putExtra(EXTRA_GUIDE, openGuide)
    }
}

@Composable
private fun Banner(channel: Channel, nowNext: NowNext?, statusLine: String?, failed: Boolean, previous: Channel?) {
    val now = rememberNow()
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ChannelLogo(channel, 120.dp, 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channel.number > 0) {
                        Text("${channel.number}", color = Palette.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(channel.name, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Text(clockText(now), color = Palette.text, fontSize = 20.sp)
                }
                val current = nowNext?.now
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current.title, color = Palette.text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        Text("${clockText(current.start)} – ${clockText(current.stop)}", color = Palette.dim, fontSize = 15.sp)
                    }
                    ProgressBar(current.progress(now), Modifier.fillMaxWidth())
                } else {
                    Text("No guide information", color = Palette.dim, fontSize = 16.sp)
                }
                nowNext?.next?.let { next ->
                    Text("Next ${clockText(next.start)} · ${next.title}", color = Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (statusLine != null) Text(statusLine, color = if (failed) Palette.warn else Palette.dim, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Drawn, not focusable: focus stays with the activity,
                    // which opens the panel on OK while the banner shows.
                    Text(
                        "OK  More · Guide · Picture",
                        color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier
                            .border(1.5.dp, Palette.text, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                    previous?.let {
                        Text(
                            "◀  back to ${if (it.number > 0) "${it.number} " else ""}${it.name}",
                            color = Palette.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}


/**
 * ☰ while watching: everything known about what's on, over the left of
 * the picture, with the player's options -- Plex's ☰ panel for live TV.
 * The description and what's on later scroll with ↑/↓ (the activity
 * scrolls [scroll]), so the buttons always stay on screen.
 */
@Composable
private fun LivePanel(
    channel: Channel,
    nowNext: NowNext?,
    modifier: Modifier,
    onGuide: () -> Unit,
    picture: PictureMode,
    onPicture: () -> Unit,
    subtitles: Boolean?,
    onSubtitles: () -> Unit,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val now = rememberNow()
    val p = nowNext?.now
    // The rest of the evening on this channel, beyond now/next.
    var later by androidx.compose.runtime.remember(channel.url) { mutableStateOf<List<Programme>?>(null) }
    androidx.compose.runtime.LaunchedEffect(channel.url) {
        val from = System.currentTimeMillis()
        val json = Engine.call("schedule", channel.url, from, from + LATER_MS)
        later = if (bridgeError(json) == null) parseProgrammes(json) else emptyList()
    }
    Column(
        modifier
            .width(620.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF20B0A1F), Color(0xCC0B0A1F), Color.Transparent)))
            .padding(start = 48.dp, end = 40.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChannelLogo(channel, 64.dp, 38.dp)
                    Text(
                        "${if (channel.number > 0) "${channel.number}  " else ""}${channel.name}",
                        color = Palette.dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(clockText(now), color = Palette.text, fontSize = 18.sp)
                }
                Text(
                    p?.let { listOfNotNull(it.title, it.year?.let { y -> "($y)" }).joinToString(" ") } ?: "No guide information",
                    color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (p != null) {
                    Text(
                        listOfNotNull("${clockText(p.start)} – ${clockText(p.stop)}", "${(p.stop - p.start) / 60_000} min").joinToString("   ·   "),
                        color = Palette.text, fontSize = 15.sp,
                    )
                    p.category?.let { Text(it, color = Palette.accent, fontSize = 15.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        p.poster?.let {
                            coil.compose.AsyncImage(
                                model = it, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(100.dp, 150.dp).clip(RoundedCornerShape(6.dp)),
                            )
                        }
                        Text(p.description ?: "No description.", color = Palette.text, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.weight(1f))
                    }
                }
                // Later: the schedule once loaded, else just the guide's "next".
                val upcoming = later?.filter { it.start >= (p?.stop ?: now) }?.take(LATER_COUNT)
                    ?: listOfNotNull(nowNext?.next)
                if (upcoming.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Later on ${channel.name}", color = Palette.dim, fontSize = 14.sp)
                    upcoming.forEach {
                        Row {
                            Text(clockText(it.start), color = Palette.dim, fontSize = 15.sp, modifier = Modifier.width(70.dp))
                            Text(
                                listOfNotNull(it.title, it.category).joinToString("  ·  "),
                                color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (p != null && p.start <= now && now < p.stop) {
                ProgressBar(p.progress(now), Modifier.width(420.dp))
                Text("${(p.stop - now) / 60_000} min left  ·  ends at ${clockText(p.stop)}", color = Palette.dim, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            PanelButtons(onGuide, picture, onPicture, subtitles, onSubtitles)
        }
        val more = when {
            scroll.maxValue == 0 -> ""
            scroll.value == 0 -> "↓ more  ·  "
            scroll.value >= scroll.maxValue -> "↑ back up  ·  "
            else -> "↑↓ scroll  ·  "
        }
        Text("${more}Back to close", color = Palette.dim, fontSize = 13.sp)
    }
}

@Composable
private fun PanelButtons(
    onGuide: () -> Unit,
    picture: PictureMode,
    onPicture: () -> Unit,
    subtitles: Boolean?,
    onSubtitles: () -> Unit,
) {
    // Focus starts on Guide, so ☰ then OK is still the way to the grid.
    val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.OutlinedButton(
            onClick = onGuide,
            modifier = Modifier.focusRequester(first).focusRing(),
        ) { Text("Guide") }
        androidx.compose.material3.OutlinedButton(onClick = onPicture, modifier = Modifier.focusRing()) {
            Text("Picture: ${picture.label}")
        }
        // Only when the stream carries a subtitle track to show or hide.
        if (subtitles != null) {
            androidx.compose.material3.OutlinedButton(onClick = onSubtitles, modifier = Modifier.focusRing()) {
                Text(if (subtitles) "Subtitles: On" else "Subtitles: Off")
            }
        }
    }
}

/** How far ahead the panel's "Later on" looks, and how many it lists. */
private const val LATER_MS = 12 * 60 * 60 * 1000L
private const val LATER_COUNT = 6
