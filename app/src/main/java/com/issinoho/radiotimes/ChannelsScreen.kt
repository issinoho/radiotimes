package com.issinoho.radiotimes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val ALL = "All channels"
private const val FAVOURITES = "★ Favourites"
private const val WATCHLIST = "Watchlist"

/** A row of the channel list; `note` replaces the now-playing line (watchlist airings). */
private data class ListRow(val channel: Channel, val note: String?, val key: String)

/** "All channels", tvtimes favourites and watchlist when there are any, then tvtimes' groups in order. */
private fun groupsOf(channels: List<Channel>, hasFavourites: Boolean, hasWatchlist: Boolean): List<String> =
    listOfNotNull(ALL, FAVOURITES.takeIf { hasFavourites }, WATCHLIST.takeIf { hasWatchlist }) +
        channels.mapNotNull { it.group }.distinct()

private fun norm(name: String) = name.trim().lowercase()

private fun rowsIn(
    channels: List<Channel>,
    group: String,
    favourites: Set<String>,
    watchlist: List<WatchEntry>,
    describe: (WatchEntry) -> String,
): List<ListRow> = when (group) {
    ALL -> channels.map { ListRow(it, null, it.url) }
    FAVOURITES -> {
        val names = favourites.map(::norm).toSet()
        channels.filter { norm(it.name) in names }.map { ListRow(it, null, it.url) }
    }
    WATCHLIST -> {
        val byUrl = channels.associateBy { it.url }
        val byName = channels.associateBy { norm(it.name) }
        watchlist.mapNotNull { e ->
            val ch = byUrl[e.channelUrl] ?: byName[norm(e.channelName)] ?: return@mapNotNull null
            ListRow(ch, describe(e), "${ch.url}@${e.start}")
        }
    }
    else -> channels.filter { it.group == group }.map { ListRow(it, null, it.url) }
}

@Composable
fun ChannelsScreen(
    lineup: Tv.Lineup,
    guide: Tv.Guide,
    nowNext: Map<String, NowNext>,
    lastPlayed: String?,
    favourites: Set<String>,
    watchlist: List<WatchEntry>,
    onPlay: (Channel, List<Channel>) -> Unit,
    onFocusChannel: (Channel, List<Channel>) -> Unit,
    plexServers: List<PlexServer>,
    onPlex: (PlexServer) -> Unit,
    onRetry: () -> Unit,
    onChangeLink: () -> Unit,
) {
    val now = rememberNow()
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Header(guide, lineup, now)
        Spacer(Modifier.height(12.dp))
        when (lineup) {
            Tv.Lineup.Idle, Tv.Lineup.Loading -> Centred { Text("Connecting to tvtimes…", color = Palette.dim, fontSize = 20.sp) }
            is Tv.Lineup.Failed -> Centred {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Couldn't load the channel list", color = Palette.text, fontSize = 22.sp)
                    Text(redactTokens(lineup.message), color = Palette.dim, fontSize = 15.sp)
                    val retry = remember { FocusRequester() }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onRetry, modifier = Modifier.focusRequester(retry).focusRing()) { Text("Try again") }
                        Button(onClick = onChangeLink, modifier = Modifier.focusRing()) { Text("Change tvtimes link") }
                    }
                    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
                }
            }
            is Tv.Lineup.Ready -> Lineup(lineup.channels, nowNext, lastPlayed, favourites, watchlist, now, onPlay, onFocusChannel, plexServers, onPlex, onChangeLink)
        }
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Header(guide: Tv.Guide, lineup: Tv.Lineup, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("radiotimes", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        if (lineup is Tv.Lineup.Ready) {
            val status = when {
                guide.busy && !guide.loaded -> "Loading the guide…"
                guide.busy -> "Updating the guide…"
                !guide.loaded -> guide.problem?.let { "No guide: ${redactTokens(it)}" } ?: "No guide yet"
                else -> "Guide updated ${guide.updated?.let { ageText(it, now) } ?: ""}"
            }
            Text(status, color = if (guide.problem != null && !guide.busy) Palette.warn else Palette.dim, fontSize = 15.sp)
            if (guide.loaded && guide.problem != null && !guide.busy) {
                Text(" · ${redactTokens(guide.problem)}", color = Palette.warn, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("☰ guide", color = Palette.dim, fontSize = 13.sp)
        Spacer(Modifier.width(20.dp))
        Text(clockText(now), color = Palette.text, fontSize = 22.sp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Lineup(
    channels: List<Channel>,
    nowNext: Map<String, NowNext>,
    lastPlayed: String?,
    favourites: Set<String>,
    watchlist: List<WatchEntry>,
    now: Long,
    onPlay: (Channel, List<Channel>) -> Unit,
    onFocusChannel: (Channel, List<Channel>) -> Unit,
    plexServers: List<PlexServer>,
    onPlex: (PlexServer) -> Unit,
    onSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val describe = remember(context) {
        val time = android.text.format.DateFormat.getTimeFormat(context)
        val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val today = { ms: Long -> java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.DAY_OF_YEAR) }
        { e: WatchEntry ->
            val n = System.currentTimeMillis()
            val `when` = when {
                e.start <= n -> "On now"
                today(e.start) == today(n) -> "Today ${time.format(java.util.Date(e.start))}"
                today(e.start) == today(n + 86_400_000L) -> "Tomorrow ${time.format(java.util.Date(e.start))}"
                else -> "${day.format(java.util.Date(e.start))} ${time.format(java.util.Date(e.start))}"
            }
            "$`when` · ${e.title}"
        }
    }
    val groups = remember(channels, favourites, watchlist) { groupsOf(channels, favourites.isNotEmpty(), watchlist.isNotEmpty()) }
    var group by rememberSaveable { mutableStateOf(ALL) }
    if (group !in groups) group = ALL
    val rows = remember(channels, group, favourites, watchlist) { rowsIn(channels, group, favourites, watchlist, describe) }
    // The channels the player steps through: this group's, once each.
    val shown = remember(rows) { rows.map { it.channel }.distinctBy { it.url } }
    var focused by remember { mutableStateOf<Channel?>(null) }

    val listState = rememberLazyListState()
    // Focus lands on the channel last played (or the first one) when the
    // list appears, and again whenever the player hands back a different
    // channel than the one that started it.
    val target = remember(rows, lastPlayed) { rows.indexOfFirst { it.channel.url == lastPlayed }.coerceAtLeast(0) }
    val targetRequester = remember { FocusRequester() }
    // Keyed on lastPlayed only: moving through the group rail changes
    // `shown` too, and that must not pull focus out of the rail.
    LaunchedEffect(lastPlayed) {
        if (shown.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (target !in visible.drop(1).dropLast(1)) listState.scrollToItem((target - 3).coerceAtLeast(0))
        repeat(10) {
            if (runCatching { targetRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    // A different group starts at its top.
    var shownGroup by rememberSaveable { mutableStateOf(group) }
    LaunchedEffect(group) {
        if (group != shownGroup) {
            shownGroup = group
            listState.scrollToItem(0)
        }
    }

    // Coming back to the rail lands on the selected group, not whichever
    // group happens to sit level with the channel row. (Not Compose's
    // focusRestorer: with lazy lists in Compose 1.7 it crashes with
    // "Release should only be called once" on the first D-pad move in.)
    val railState = rememberLazyListState()
    val selectedGroupRequester = remember { FocusRequester() }
    val selectedIndex = groups.indexOf(group)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(
            state = railState,
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight()
                .focusProperties {
                    enter = {
                        if (railState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }) selectedGroupRequester
                        else FocusRequester.Default
                    }
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(groups, key = { _, g -> g }) { i, g ->
                val count = remember(channels, g, favourites, watchlist) { rowsIn(channels, g, favourites, watchlist, describe).size }
                GroupRow(
                    g, count, selected = g == group, onFocused = { group = g },
                    modifier = if (i == selectedIndex) Modifier.focusRequester(selectedGroupRequester) else Modifier,
                )
            }
            plexServers.filter { it.error == null }.forEachIndexed { i, server ->
                item(key = "plex${server.index}") {
                    RailButton("▶  ${server.name}", topGap = if (i == 0) 14.dp else 2.dp) { onPlex(server) }
                }
            }
            item(key = "settings") { RailButton("⚙  Settings", topGap = 14.dp, onClick = onSettings) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                val channel = row.channel
                ChannelRow(
                    channel = channel,
                    note = row.note,
                    nowNext = nowNext[channel.url],
                    now = now,
                    modifier = if (index == target) Modifier.focusRequester(targetRequester) else Modifier,
                    onFocused = {
                        focused = channel
                        onFocusChannel(channel, shown)
                    },
                    onClick = { onPlay(channel, shown) },
                )
            }
        }
        Details(focused?.takeIf { it in shown }, focused?.let { nowNext[it.url] }, now, Modifier.width(280.dp).fillMaxHeight())
    }
}

/** A rail entry that does something on OK rather than selecting a group (Plex servers, Settings). */
@Composable
private fun RailButton(label: String, topGap: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = topGap)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hasFocus) Palette.focus else Color.Transparent)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (hasFocus) Palette.text else Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupRow(name: String, count: Int, selected: Boolean, onFocused: () -> Unit, modifier: Modifier) {
    var hasFocus by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    hasFocus -> Palette.focus
                    selected -> Color(0x1AFFFFFF)
                    else -> Color.Transparent
                },
            )
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable { onFocused() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            name,
            color = if (selected || hasFocus) Palette.text else Palette.dim,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = Palette.dim, fontSize = 12.sp)
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    note: String?,
    nowNext: NowNext?,
    now: Long,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hasFocus) Palette.focus else Color.Transparent)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        Text("${channel.number}", color = Palette.dim, fontSize = 14.sp, modifier = Modifier.width(36.dp))
        ChannelLogo(channel, 64.dp, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(channel.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val current = nowNext?.now
            if (note != null) {
                Text(note, color = Palette.accent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.title, color = Palette.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ProgressBar(current.progress(now), Modifier.width(60.dp))
                }
            }
        }
        // No "next" column: at 960dp wide (1080p at TV density) it starved
        // the title column. The details panel shows what's next.
    }
}

@Composable
private fun Details(channel: Channel?, nowNext: NowNext?, now: Long, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(channel, 80.dp, 50.dp)
            Column {
                Text(channel.name, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                channel.group?.let { Text(it, color = Palette.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val current = nowNext?.now
        if (current == null) {
            Text("No guide information", color = Palette.dim, fontSize = 15.sp)
            return@Column
        }
        Text("${clockText(current.start)} – ${clockText(current.stop)}", color = Palette.dim, fontSize = 13.sp)
        Text(
            listOfNotNull(current.title, current.year?.let { "($it)" }).joinToString(" "),
            color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        ProgressBar(current.progress(now), Modifier.fillMaxWidth())
        current.category?.let { Text(it, color = Palette.accent, fontSize = 13.sp) }
        current.description?.let {
            Text(it, color = Palette.dim, fontSize = 14.sp, maxLines = 9, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        nowNext.next?.let { next ->
            Spacer(Modifier.height(4.dp))
            Text("Next · ${clockText(next.start)}", color = Palette.dim, fontSize = 13.sp)
            Text(next.title, color = Palette.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
