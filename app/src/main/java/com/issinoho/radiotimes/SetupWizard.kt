package com.issinoho.radiotimes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * First run: Welcome → tvtimes → Plex (optional) → Ready. tvtimes can be
 * skipped too, for radiotimes as just a Plex player; finishing without a
 * tvtimes link records that choice ([Prefs.withoutTvtimes]).
 *
 * Each step leads with the code-based way (tvtimes pairing, plex.tv/link);
 * the phone page and typed links sit behind "Other ways to connect".
 *
 * Shown only on a fresh install (no tvtimes link yet). The phone setup page
 * ([SetupServer]) runs for the whole wizard, so a phone can send the
 * tvtimes link and any Plex links in one go; the steps just show what has
 * arrived. Each step's main button takes focus, never a text field.
 */
@Composable
fun SetupWizard(
    phoneUrl: String?,
    lineup: Tv.Lineup,
    plexServers: List<PlexServer>,
    onTvtimes: (String) -> Unit,
    onPlexLinked: (List<String>) -> Unit,
    onDone: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp)) {
        Steps(step)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> Welcome(onNext = { step = 1 })
                1 -> TvtimesStep(phoneUrl, lineup, onTvtimes, onNext = { step = 2 })
                2 -> PlexStep(phoneUrl, plexServers, onPlexLinked, onNext = { step = 3 })
                else -> Ready(lineup, plexServers, onDone)
            }
        }
    }
}

@Composable
private fun Steps(current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("radiotimes", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))
        listOf("Welcome", "tvtimes", "Plex", "Ready").forEachIndexed { i, label ->
            val done = i < current
            val here = i == current
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (here || done) Palette.accent else Palette.faint),
                contentAlignment = Alignment.Center,
            ) { Text(if (done) "✓" else "${i + 1}", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Text(label, color = if (here) Palette.text else Palette.dim, fontSize = 15.sp)
            if (i < 3) Text("—", color = Palette.faint, fontSize = 15.sp)
        }
    }
}

@Composable
private fun rememberFirstFocus(key: Any? = Unit): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    return requester
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val go = rememberFirstFocus()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.width(720.dp).padding(top = 40.dp)) {
        Text("tvdinner on your TV", color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            "Your tvtimes line-up and guide, with the picture still playing while you browse, and your Plex " +
                "libraries alongside. Setting up takes a minute, and you won't have to type a token with the remote: " +
                "the TV pairs with tvtimes and links to your Plex account with short codes.",
            color = Palette.dim, fontSize = 19.sp, lineHeight = 28.sp,
        )
        Text(
            "You'll need to be signed in to tvtimes on a phone or computer, a Plex account, or both.",
            color = Palette.dim, fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNext, modifier = Modifier.focusRequester(go).focusRing()) { Text("Get started", fontSize = 17.sp) }
    }
}

@Composable
private fun PhoneCard(phoneUrl: String?, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier.width(330.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (phoneUrl != null) {
            QrCode(phoneUrl, Modifier.size(190.dp).align(Alignment.CenterHorizontally))
            Text("Scan with your phone's camera, or open on a phone on the same Wi-Fi:", color = Palette.dim, fontSize = 13.sp)
            Text(phoneUrl.removePrefix("http://"), color = Palette.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text("Not available: this TV has no home-network address. Type the link instead.", color = Palette.dim, fontSize = 14.sp)
        }
    }
}

/** "Other ways…": the phone page and typing, kept as a fallback behind one button. */
@Composable
private fun OtherWays(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.focusRing()) { Text("Other ways to connect") }
}

/**
 * tvtimes: pairing first (no link or token to handle). The phone page and a
 * typed link are a fallback for a tvtimes without pairing or one the TV
 * can't find; links a phone sends still arrive while this step is open.
 */
@Composable
private fun TvtimesStep(phoneUrl: String?, lineup: Tv.Lineup, onTvtimes: (String) -> Unit, onNext: () -> Unit) {
    var fallback by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    val valid = (url.trim().startsWith("tvtimes://") || url.trim().startsWith("tvtimess://")) && "token=" in url
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect your tvtimes guide", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            // Shorter once "Other ways" opens: the phone card halves the width.
            Text(
                if (fallback) "Pair this TV: enter the code it shows in tvtimes under Settings → Devices."
                else "Pair this TV with tvtimes: it finds tvtimes on your network and shows a code to enter in " +
                    "tvtimes under Settings → Devices. No link or token to copy.",
                color = Palette.dim, fontSize = 17.sp, lineHeight = 26.sp,
            )
            if (lineup !is Tv.Lineup.Ready && lineup != Tv.Lineup.Loading) {
                PairWithTvtimes(focusFirst = !fallback, onLinked = onTvtimes, onBusy = { pairing = it })
            }
            Spacer(Modifier.height(4.dp))
            when (lineup) {
                Tv.Lineup.Idle -> {}
                Tv.Lineup.Loading -> Text("Connecting to tvtimes…", color = Palette.text, fontSize = 18.sp)
                is Tv.Lineup.Failed -> Text(
                    "That didn't work: ${redactTokens(lineup.message)}. Try pairing again, or another way.",
                    color = Palette.warn, fontSize = 17.sp,
                )
                is Tv.Lineup.Ready -> {
                    Text("Connected ✓  ${lineup.channels.size} channels", color = Palette.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    val next = rememberFirstFocus(lineup)
                    Button(onClick = onNext, modifier = Modifier.focusRequester(next).focusRing()) { Text("Next") }
                }
            }
            if (lineup !is Tv.Lineup.Ready && !pairing) {
                if (!fallback) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OtherWays { fallback = true }
                        // Plex only: no live TV. Hidden while a link is connecting.
                        if (lineup != Tv.Lineup.Loading) {
                            OutlinedButton(onClick = onNext, modifier = Modifier.focusRing()) { Text("Skip: Plex only") }
                        }
                    }
                } else {
                    Text(
                        "Or send your tvtimes link (Settings → Export feeds) from your phone with the code on the " +
                            "right, or type it.",
                        color = Palette.dim, fontSize = 15.sp, lineHeight = 22.sp,
                    )
                    if (!typing) {
                        val type = rememberFirstFocus()
                        OutlinedButton(onClick = { typing = true }, modifier = Modifier.focusRequester(type).focusRing()) {
                            Text("Type the link with the remote")
                        }
                    } else {
                        // Field and button on one row, so the button stays on screen.
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("tvtimes link") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (valid) onTvtimes(url.trim()) }),
                                visualTransformation = MaskToken,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { onTvtimes(url.trim()) }, enabled = valid, modifier = Modifier.focusRing()) { Text("Connect") }
                        }
                    }
                }
            }
        }
        if (fallback && lineup !is Tv.Lineup.Ready) PhoneCard(phoneUrl, "Your phone")
    }
}

/**
 * Plex: plex.tv/link first (every server on the account, no tokens). Sending
 * plex:// links from the phone, or typing them, is the fallback.
 */
@Composable
private fun PlexStep(phoneUrl: String?, servers: List<PlexServer>, onPlexLinked: (List<String>) -> Unit, onNext: () -> Unit) {
    val usable = servers.filter { it.error == null }
    var fallback by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf("") }
    val validLinks = Prefs.splitLinks(links).let { l -> l.isNotEmpty() && l.all { it.startsWith("plex://") && "token=" in it.lowercase() } }
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add Plex (optional)", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                if (fallback) "Link your Plex account: enter the code the TV shows at plex.tv/link."
                else "Link your Plex account: go to plex.tv/link on your phone or computer and enter the code the TV " +
                    "shows. Every server on the account appears next to your channels, with resume and next episode " +
                    "kept in step with your other Plex apps.",
                color = Palette.dim, fontSize = 17.sp, lineHeight = 26.sp,
            )
            if (usable.isNotEmpty()) {
                Text("Found ✓  ${usable.joinToString(", ") { it.name }}", color = Palette.accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlexLink(focusFirst = usable.isEmpty() && !fallback, showTitle = false, onLinked = onPlexLinked)
            }
            Spacer(Modifier.height(6.dp))
            val next = rememberFirstFocus(usable.size)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (usable.isNotEmpty()) {
                    Button(onClick = onNext, modifier = Modifier.focusRequester(next).focusRing()) { Text("Next") }
                } else {
                    OutlinedButton(onClick = onNext, modifier = Modifier.focusRing()) { Text("Skip Plex") }
                }
                if (!fallback) OtherWays { fallback = true }
            }
            if (fallback) {
                Text(
                    "Or send plex:// links from your phone with the code on the right, or type them.",
                    color = Palette.dim, fontSize = 15.sp, lineHeight = 22.sp,
                )
                if (!typing) {
                    val type = rememberFirstFocus()
                    OutlinedButton(onClick = { typing = true }, modifier = Modifier.focusRequester(type).focusRing()) {
                        Text("Type Plex links with the remote")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = links,
                            onValueChange = { links = it },
                            label = { Text("Plex links, separated by spaces") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (validLinks) onPlexLinked(Prefs.splitLinks(links)) }),
                            visualTransformation = MaskToken,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { onPlexLinked(Prefs.splitLinks(links)) }, enabled = validLinks, modifier = Modifier.focusRing()) { Text("Add") }
                    }
                }
            }
        }
        if (fallback) PhoneCard(phoneUrl, "Your phone")
    }
}

@Composable
private fun Ready(lineup: Tv.Lineup, servers: List<PlexServer>, onDone: () -> Unit) {
    val go = rememberFirstFocus()
    val channels = (lineup as? Tv.Lineup.Ready)?.channels?.size
    val usable = servers.filter { it.error == null }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(760.dp).padding(top = 20.dp)) {
        Text("You're all set", color = Palette.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(
            if (channels == null) "–  tvtimes: not added (Plex only; you can pair it later in Settings)" else "✓  tvtimes: $channels channels",
            color = Palette.text, fontSize = 18.sp,
        )
        Text(
            if (usable.isEmpty()) "–  Plex: not added (you can link it later in Settings)" else "✓  Plex: ${usable.joinToString(", ") { it.name }}",
            color = Palette.text, fontSize = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("A few things worth knowing", color = Palette.dim, fontSize = 16.sp)
        listOfNotNull(
            "☰  opens the guide over the picture; the channel keeps playing in the corner".takeIf { channels != null },
            "OK  on a programme previews it; OK again shows everything about it".takeIf { channels != null },
            "Settings  at the bottom of the channel groups, to change links later",
        ).forEach { Text(it, color = Palette.text, fontSize = 17.sp) }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onDone, modifier = Modifier.focusRequester(go).focusRing()) { Text("Start watching", fontSize = 17.sp) }
    }
}
