package com.issinoho.radiotimes

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val MINUTE = 60_000L
private const val STEP = 30 * MINUTE
private const val WINDOW = 120 * MINUTE
private const val AHEAD = 24 * 60 * MINUTE

private val ChannelColumn = 210.dp
private val RowHeight = 46.dp

/** Size of the live picture in the guide's top-right corner. */
val GuideVideoWidth = 352.dp
val GuideVideoHeight = 198.dp


/**
 * The programme guide, drawn full screen with the top-right corner left
 * empty for the live picture (the caller places the video there, so the
 * player keeps running while the guide is open).
 *
 * Rows are the channels the player zaps through; columns are a 2-hour
 * window that moves in 30-minute steps when focus runs off either edge.
 *
 * OK on a programme tunes the corner picture to that channel (the guide
 * stays open, so you can preview); OK again on the channel now playing
 * opens [InfoPanel] with everything known about the programme. OK there
 * watches full screen. Back is handled by the activity: info → guide →
 * full screen.
 */
data class GuideInfo(val index: Int, val channel: Channel, val programme: Programme?)

@Composable
fun Guide(
    channels: List<Channel>,
    currentIndex: Int,
    info: GuideInfo?,
    onInfo: (GuideInfo?) -> Unit,
    onTune: (Int) -> Unit,
    onWatch: (Int) -> Unit,
) {
    val now = rememberNow()
    val earliest = remember { System.currentTimeMillis() / STEP * STEP }
    var windowStart by remember { mutableLongStateOf(earliest) }
    val schedules = remember { mutableStateMapOf<String, List<Programme>>() }
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val startRequester = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(GuideVideoHeight)) {
            Details(focused, now, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(16.dp))
            Spacer(Modifier.width(GuideVideoWidth)) // the live picture sits here
        }
        Spacer(Modifier.height(10.dp))
        TimeHeader(windowStart, now)
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(channels, key = { _, c -> c.url }) { index, channel ->
                // A row's whole 24 h loads once; moving the window only
                // filters it. Reloading per window made the focused cell
                // vanish for a moment and focus jump to another row.
                LaunchedEffect(channel.url) {
                    if (channel.url !in schedules) {
                        val json = Engine.call("schedule", channel.url, earliest, earliest + AHEAD + WINDOW)
                        schedules[channel.url] = if (bridgeError(json) == null) parseProgrammes(json) else emptyList()
                    }
                }
                GuideRow(
                    channel = channel,
                    programmes = schedules[channel.url],
                    windowStart = windowStart,
                    now = now,
                    isCurrent = index == currentIndex,
                    startRequester = if (index == currentIndex) startRequester else null,
                    onFocus = { programme -> focused = channel to programme },
                    onSelect = { programme ->
                        when {
                            // The panel is only drawn over the grid; focus
                            // stays on the cell, so this OK is "watch".
                            info != null -> onWatch(info.index)
                            index == currentIndex -> onInfo(GuideInfo(index, channel, programme))
                            else -> onTune(index)
                        }
                    },
                    onEdge = { forward ->
                        val next = windowStart + if (forward) STEP else -STEP
                        if (next in earliest..(earliest + AHEAD)) {
                            windowStart = next
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
    }
    if (info != null) {
        InfoPanel(info, schedules[info.channel.url].orEmpty(), now)
    }
    }

    // Focus starts on what's on now on the channel being watched, once its
    // row has loaded.
    LaunchedEffect(Unit) {
        repeat(100) {
            if (runCatching { startRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
}

@Composable
private fun Details(focused: Pair<Channel, Programme?>?, now: Long, modifier: Modifier) {
    Column(modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Guide", color = Palette.dim, fontSize = 14.sp)
        val (channel, programme) = focused ?: return@Column
        Text(
            "${channel.number}  ${channel.name}",
            color = Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (programme == null) {
            Text("No guide information", color = Palette.text, fontSize = 22.sp)
            return@Column
        }
        Text(
            listOfNotNull(programme.title, programme.year?.let { "($it)" }).joinToString(" "),
            color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${clockText(programme.start)} – ${clockText(programme.stop)}", color = Palette.dim, fontSize = 14.sp)
            programme.category?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, color = Palette.accent, fontSize = 14.sp)
            }
        }
        if (programme.start <= now && now < programme.stop) ProgressBar(programme.progress(now), Modifier.width(240.dp))
        programme.description?.let {
            Text(it, color = Palette.dim, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long, now: Long) {
    Row(Modifier.fillMaxWidth().height(26.dp)) {
        val sameDay = localDay(windowStart) == localDay(now)
        Text(if (sameDay) "Today" else "Tomorrow", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.width(ChannelColumn))
        BoxWithConstraints(Modifier.weight(1f)) {
            for (i in 0 until (WINDOW / STEP).toInt()) {
                Text(
                    clockText(windowStart + i * STEP),
                    color = Palette.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.offset(x = maxWidth * (i * STEP.toFloat() / WINDOW)),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>?,
    windowStart: Long,
    now: Long,
    isCurrent: Boolean,
    startRequester: FocusRequester?,
    onFocus: (Programme?) -> Unit,
    onSelect: (Programme?) -> Unit,
    onEdge: (forward: Boolean) -> Boolean,
) {
    val windowEnd = windowStart + WINDOW
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(ChannelColumn).fillMaxHeight().padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${channel.number}", color = if (isCurrent) Palette.accent else Palette.dim, fontSize = 13.sp,
                modifier = Modifier.width(26.dp),
            )
            ChannelLogo(channel, 50.dp, 32.dp)
            Text(channel.name, color = Palette.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val perMs = maxWidth / WINDOW.toFloat()
            val visible = programmes?.filter { it.stop > windowStart && it.start < windowEnd }.orEmpty()
            if (programmes != null && visible.isEmpty()) {
                Cell(
                    title = "No guide information", x = 0.dp, width = maxWidth, onAir = false,
                    requester = startRequester, onFocus = { onFocus(null) }, onClick = { onSelect(null) }, onEdge = { false },
                )
            }
            val onNowIndex = visible.indexOfFirst { it.start <= now && now < it.stop }.let { if (it < 0) 0 else it }
            visible.forEachIndexed { i, p -> key(p.start) {
                val from = maxOf(p.start, windowStart)
                val to = minOf(p.stop, windowEnd)
                Cell(
                    title = p.title,
                    x = perMs * (from - windowStart).toFloat(),
                    width = perMs * (to - from).toFloat(),
                    onAir = p.start <= now && now < p.stop,
                    requester = if (i == onNowIndex) startRequester else null,
                    onFocus = { onFocus(p) },
                    onClick = { onSelect(p) },
                    onEdge = { forward ->
                        when {
                            forward && p.stop >= windowEnd -> onEdge(true)
                            !forward && p.start <= windowStart -> onEdge(false)
                            else -> false
                        }
                    },
                )
            } }
            // "Now" marker.
            if (now in windowStart until windowEnd) {
                Box(
                    Modifier
                        .offset(x = perMs * (now - windowStart).toFloat())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Palette.accent),
                )
            }
        }
    }
}

@Composable
private fun Cell(
    title: String,
    x: Dp,
    width: Dp,
    onAir: Boolean,
    requester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onEdge: (forward: Boolean) -> Boolean,
) {
    var hasFocus by remember { mutableStateOf(false) }
    Box(
        Modifier
            .offset(x = x)
            .width((width - 2.dp).coerceAtLeast(4.dp))
            .fillMaxHeight()
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    hasFocus -> Palette.focus
                    onAir -> Palette.surface
                    else -> Color(0x0DFFFFFF)
                },
            )
            .border(2.dp, if (hasFocus) Palette.text else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocus()
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onEdge(true)
                    KeyEvent.KEYCODE_DPAD_LEFT -> onEdge(false)
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            color = if (hasFocus || onAir) Palette.text else Palette.dim,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun localDay(epochMs: Long): Int =
    java.util.Calendar.getInstance().apply { timeInMillis = epochMs }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }

/**
 * Everything known about one programme, drawn over the guide (the live
 * picture stays in its corner). Display only: focus stays on the guide
 * cell underneath -- moving it to the panel and back lost the cell when
 * the panel closed -- and the activity swallows the D-pad while it's up.
 */
@Composable
private fun InfoPanel(info: GuideInfo, schedule: List<Programme>, now: Long) {
    val p = info.programme
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(GuideVideoHeight)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChannelLogo(info.channel, 56.dp, 34.dp)
                    Text("${info.channel.number}  ${info.channel.name}", color = Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    p?.let { listOfNotNull(it.title, it.year?.let { y -> "($y)" }).joinToString(" ") } ?: "No guide information",
                    color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (p != null) {
                    val minutes = (p.stop - p.start) / MINUTE
                    val day = if (localDay(p.start) == localDay(now)) "Today" else if (localDay(p.start) == localDay(now) + 1) "Tomorrow" else dayText(p.start)
                    Text(
                        listOfNotNull("$day ${clockText(p.start)} – ${clockText(p.stop)}", "$minutes min", p.category).joinToString("  ·  "),
                        color = Palette.dim, fontSize = 15.sp,
                    )
                    if (p.start <= now && now < p.stop) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressBar(p.progress(now), Modifier.width(260.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("${(p.stop - now) / MINUTE} min left", color = Palette.dim, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Spacer(Modifier.width(GuideVideoWidth))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            p?.poster?.let { url ->
                coil.compose.AsyncImage(
                    model = url, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    p?.description ?: "No description.",
                    color = Palette.text, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                )
                val after = p?.let { cur -> schedule.filter { it.start >= cur.stop }.take(4) }.orEmpty()
                if (after.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Later on ${info.channel.name}", color = Palette.dim, fontSize = 14.sp)
                    after.forEach {
                        Row {
                            Text(clockText(it.start), color = Palette.dim, fontSize = 15.sp, modifier = Modifier.width(70.dp))
                            Text(it.title, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Text("OK  watch full screen      Back  guide", color = Palette.dim, fontSize = 13.sp)
    }
}

private fun dayText(epochMs: Long): String =
    java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
