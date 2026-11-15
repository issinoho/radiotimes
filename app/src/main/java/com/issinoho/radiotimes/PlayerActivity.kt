package com.issinoho.radiotimes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen playback of one channel. Phase 0 uses it to answer: does
 * ExoPlayer on the Stick play what tvtimes hands it, and how quickly? It
 * logs the resolve path, time to first frame and the decoded format so
 * the answer can be pasted from logcat.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            val resolved = try {
                withContext(Dispatchers.IO) { StreamResolver.resolve(url) }
            } catch (e: Exception) {
                fail("Could not reach $name: ${e.message}")
                return@launch
            }
            Log.i(
                TAG,
                "play | $name → ${StreamResolver.redact(resolved.url)} after ${resolved.hops} redirect(s), " +
                    "type ${resolved.mimeType ?: "progressive (auto)"} by ${resolved.how}, " +
                    "${SystemClock.elapsedRealtime() - started} ms",
            )
            start(resolved, name, started)
        }
    }

    private fun start(resolved: StreamResolver.Resolved, name: String, started: Long) {
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
                    "play | first frame ${SystemClock.elapsedRealtime() - started} ms · " +
                        "${f?.sampleMimeType} ${f?.width}x${f?.height} · codecs ${f?.codecs}",
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "play | ${error.errorCodeName}: ${error.message}", error)
                fail("$name: ${error.errorCodeName}")
            }
        })
        exo.setMediaItem(
            MediaItem.Builder().setUri(resolved.url).apply { resolved.mimeType?.let { setMimeType(it) } }.build(),
        )
        exo.playWhenReady = true
        exo.prepare()
        view.player = exo
        player = exo
    }

    private fun fail(message: String) {
        Log.e(TAG, "play | $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        view.player = null
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, url: String, name: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
    }
}
