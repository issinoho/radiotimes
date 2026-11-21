package com.issinoho.radiotimes

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

/**
 * Setup (once) and the channel list. ☰ opens the guide (over the focused
 * channel, playing); Settings at the foot of the group rail reopens setup.
 *
 * The links can also be sent from a computer, which saves typing tokens
 * with the remote; this works whether or not the app is already open:
 *   adb shell am start -n com.issinoho.radiotimes/.MainActivity \
 *     --es url 'tvtimess://host?token=…' \
 *     --es plex 'plex://host:32400?X-Plex-Token=… plex://other:32400?X-Plex-Token=…'
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { Wizard, Setup, Channels }

    private val screen = mutableStateOf(Screen.Channels)
    /** Bumped on every Plex link change (plex.tv/link, phone, adb, Advanced) so Settings re-reads them. */
    private val plexVersion = mutableStateOf(0)
    private var focusedChannel: Pair<Channel, List<Channel>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tv.init(this)
        Updates.check(this)
        Plex.configure(Prefs.plexLinks(this), filesDir.absolutePath)
        if (!acceptLinkFrom(intent)) {
            val saved = Prefs.tvtimesUrl(this)
            if (saved == null) {
                // A fresh install: the step-by-step wizard. (Settings later
                // opens the one-page setup screen instead.)
                screen.value = Screen.Wizard
            } else if (Tv.lineup.value == Tv.Lineup.Idle) {
                Tv.connect(saved, cacheDir.absolutePath)
            }
        }

        setContent {
            RadiotimesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.background) {
                    when (screen.value) {
                        Screen.Wizard -> {
                            // The phone page runs for the whole wizard; what it
                            // receives is applied without leaving the wizard,
                            // whose steps show it arriving.
                            var phoneUrl by remember { mutableStateOf<String?>(null) }
                            DisposableEffect(Unit) {
                                val server = SetupServer { tvtimes, plex ->
                                    if (plex.isNotEmpty()) setPlex(plex)
                                    tvtimes?.let { useTvtimes(it) }
                                }
                                phoneUrl = server.start()
                                onDispose { server.stop() }
                            }
                            val lineup by Tv.lineup.collectAsState()
                            val plexServers by Plex.servers.collectAsState()
                            SetupWizard(
                                phoneUrl = phoneUrl,
                                lineup = lineup,
                                plexServers = plexServers,
                                onTvtimes = { useTvtimes(it) },
                                onPlexLinked = { setPlex(it) },
                                onDone = { screen.value = Screen.Channels },
                            )
                        }
                        Screen.Setup -> {
                            val canCancel = Prefs.tvtimesUrl(this) != null
                            BackHandler(enabled = canCancel) { screen.value = Screen.Channels }
                            // The phone setup page lives exactly as long as this screen.
                            var phoneUrl by remember { mutableStateOf<String?>(null) }
                            DisposableEffect(Unit) {
                                val server = SetupServer { tvtimes, plex ->
                                    if (plex.isNotEmpty()) setPlex(plex)
                                    when {
                                        tvtimes != null -> connect(tvtimes)
                                        Prefs.tvtimesUrl(this@MainActivity) != null -> screen.value = Screen.Channels
                                    }
                                }
                                phoneUrl = server.start()
                                onDispose { server.stop() }
                            }
                            val lineup by Tv.lineup.collectAsState()
                            val plexServers by Plex.servers.collectAsState()
                            SetupScreen(
                                initial = Prefs.tvtimesUrl(this).orEmpty(),
                                lineup = lineup,
                                plexServers = plexServers,
                                initialPlex = remember(plexVersion.value) { Prefs.plexLinks(this).joinToString(" ") },
                                canCancel = canCancel,
                                phoneUrl = phoneUrl,
                                onPlexLinked = { setPlex(it) },
                                onConnect = { url, plex ->
                                    setPlex(Prefs.splitLinks(plex))
                                    connect(url)
                                },
                                onCancel = { screen.value = Screen.Channels },
                            )
                        }
                        Screen.Channels -> {
                            val lineup by Tv.lineup.collectAsState()
                            val guide by Tv.guide.collectAsState()
                            val nowNext by Tv.nowNext.collectAsState()
                            val lastPlayed by Tv.lastPlayed.collectAsState()
                            val plexServers by Plex.servers.collectAsState()
                            val favourites by Tv.favourites.collectAsState()
                            val watchlist by Tv.watchlist.collectAsState()
                            ChannelsScreen(
                                lineup = lineup,
                                guide = guide,
                                nowNext = nowNext,
                                lastPlayed = lastPlayed,
                                favourites = favourites,
                                watchlist = watchlist,
                                onPlay = { channel, list -> play(channel, list) },
                                onFocusChannel = { channel, list -> focusedChannel = channel to list },
                                plexServers = plexServers,
                                onPlex = { startActivity(PlexActivity.intent(this, it)) },
                                onRetry = { Prefs.tvtimesUrl(this)?.let { connect(it) } },
                                onChangeLink = { screen.value = Screen.Setup },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The app can sit open for days on a TV; look again (at most hourly).
        Updates.check(this)
        // ...and for channels added in tvtimes meanwhile (at most every 5 min).
        Tv.foregrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptLinkFrom(intent)
    }

    /** Takes `--es url` / `--es plex` links sent over adb. Returns whether there was a tvtimes link. */
    private fun acceptLinkFrom(intent: Intent?): Boolean {
        intent?.getStringExtra(EXTRA_PLEX)?.let {
            intent.removeExtra(EXTRA_PLEX)
            setPlex(Prefs.splitLinks(it))
        }
        val url = intent?.getStringExtra(EXTRA_URL)?.trim()?.ifEmpty { null } ?: return false
        intent.removeExtra(EXTRA_URL)
        connect(url)
        return true
    }

    private fun setPlex(links: List<String>) {
        Prefs.setPlexLinks(this, links)
        plexVersion.value++
        Plex.configure(links, filesDir.absolutePath)
    }

    private fun connect(url: String) {
        useTvtimes(url)
        screen.value = Screen.Channels
    }

    private fun useTvtimes(url: String) {
        Prefs.setTvtimesUrl(this, url)
        Tv.connect(url, cacheDir.absolutePath)
    }

    private fun play(channel: Channel, list: List<Channel>, openGuide: Boolean = false) {
        Tv.zapList = list
        Tv.played(this, channel)
        startActivity(PlayerActivity.intent(this, channel, openGuide))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ☰ on the channel list: watch the focused channel with the guide up.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && screen.value == Screen.Channels) {
            if (event.action == KeyEvent.ACTION_UP) focusedChannel?.let { (c, list) -> play(c, list, openGuide = true) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PLEX = "plex"
    }
}
