package com.issinoho.radiotimes

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

/**
 * Setup (once) and the channel list. The ☰ menu key reopens setup to
 * change the tvtimes link.
 *
 * The link can also be sent from a computer, which saves typing a token
 * with the remote; this works whether or not the app is already open:
 *   adb shell am start -n com.issinoho.radiotimes/.MainActivity \
 *     --es url 'tvtimess://host?token=…'
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { Setup, Channels }

    private val screen = mutableStateOf(Screen.Channels)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tv.init(this)
        if (!acceptLinkFrom(intent)) {
            val saved = Prefs.tvtimesUrl(this)
            if (saved == null) {
                screen.value = Screen.Setup
            } else if (Tv.lineup.value == Tv.Lineup.Idle) {
                Tv.connect(saved, cacheDir.absolutePath)
            }
        }

        setContent {
            RadiotimesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.background) {
                    when (screen.value) {
                        Screen.Setup -> {
                            val canCancel = Prefs.tvtimesUrl(this) != null
                            BackHandler(enabled = canCancel) { screen.value = Screen.Channels }
                            SetupScreen(
                                initial = Prefs.tvtimesUrl(this).orEmpty(),
                                canCancel = canCancel,
                                onConnect = { connect(it) },
                                onCancel = { screen.value = Screen.Channels },
                            )
                        }
                        Screen.Channels -> {
                            val lineup by Tv.lineup.collectAsState()
                            val guide by Tv.guide.collectAsState()
                            val nowNext by Tv.nowNext.collectAsState()
                            val lastPlayed by Tv.lastPlayed.collectAsState()
                            ChannelsScreen(
                                lineup = lineup,
                                guide = guide,
                                nowNext = nowNext,
                                lastPlayed = lastPlayed,
                                onPlay = { channel, list -> play(channel, list) },
                                onRetry = { Prefs.tvtimesUrl(this)?.let { connect(it) } },
                                onChangeLink = { screen.value = Screen.Setup },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptLinkFrom(intent)
    }

    /** Takes a `--es url` link sent over adb. Returns whether there was one. */
    private fun acceptLinkFrom(intent: Intent?): Boolean {
        val url = intent?.getStringExtra(EXTRA_URL)?.trim()?.ifEmpty { null } ?: return false
        intent.removeExtra(EXTRA_URL)
        connect(url)
        return true
    }

    private fun connect(url: String) {
        Prefs.setTvtimesUrl(this, url)
        Tv.connect(url, cacheDir.absolutePath)
        screen.value = Screen.Channels
    }

    private fun play(channel: Channel, list: List<Channel>) {
        Tv.zapList = list
        Tv.played(this, channel)
        startActivity(PlayerActivity.intent(this, channel))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU && screen.value == Screen.Channels) {
            if (event.action == KeyEvent.ACTION_UP) screen.value = Screen.Setup
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
