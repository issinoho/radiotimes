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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
 * the list was showing, OK shows what's on, Back returns to the list.
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
        data class Failed(val message: String) : Status
    }

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var lineup: List<Channel> = emptyList()
    private val index = mutableIntStateOf(0)
    private val status = mutableStateOf<Status>(Status.Tuning)
    private val bannerVisible = mutableStateOf(true)
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

        setContent {
            RadiotimesTheme {
                val nowNext by Tv.nowNext.collectAsState()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                isFocusable = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                                player = this@PlayerActivity.player
                                view = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val current = channel
                    AnimatedVisibility(
                        visible = bannerVisible.value || status.value is Status.Failed,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        val line = when (val st = status.value) {
                            Status.Tuning -> "Connecting…"
                            Status.Playing -> null
                            is Status.Failed -> st.message
                        }
                        if (current != null) Banner(current, nowNext[current.url], line, failed = status.value is Status.Failed)
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
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val f = exo.videoFormat
                Log.i(
                    TAG,
                    "play | first frame ${SystemClock.elapsedRealtime() - tuneStarted} ms · " +
                        "${f?.sampleMimeType} ${f?.width}x${f?.height} · codecs ${f?.codecs}",
                )
                status.value = Status.Playing
                showBanner()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "play | ${channel?.name}: ${error.errorCodeName}: ${error.message}")
                status.value = Status.Failed(describe(error))
            }
        })
        exo.playWhenReady = true
        player = exo
        view?.player = exo
        tune(delayMs = 0)
    }

    override fun onStop() {
        super.onStop()
        tuneJob?.cancel()
        view?.player = null
        player?.release()
        player = null
    }

    private fun zap(step: Int) {
        if (lineup.size < 2) return
        index.intValue = Math.floorMod(index.intValue + step, lineup.size)
        channel?.let { Tv.played(this, it) }
        tune(delayMs = TUNE_DELAY_MS)
    }

    private fun tune(delayMs: Long) {
        val target = channel ?: return
        status.value = Status.Tuning
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
                status.value = Status.Failed("Couldn't reach the stream (${redactTokens(e.message.orEmpty())})")
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
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (down(event)) zap(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down(event)) zap(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (bannerVisible.value && status.value == Status.Playing) bannerVisible.value = false else showBanner()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                if (event.action == KeyEvent.ACTION_UP) showBanner()
                true
            }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    /** Key down, including auto-repeat, so holding up/down keeps stepping. */
    private fun down(event: KeyEvent) = event.action == KeyEvent.ACTION_DOWN

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val TUNE_DELAY_MS = 450L
        private const val BANNER_MS = 5_000L

        fun intent(context: Context, channel: Channel): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, channel.url)
                .putExtra(EXTRA_NAME, channel.name)
    }
}

@Composable
private fun Banner(channel: Channel, nowNext: NowNext?, statusLine: String?, failed: Boolean) {
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
            }
        }
    }
}

