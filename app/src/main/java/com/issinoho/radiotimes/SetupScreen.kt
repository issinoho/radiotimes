package com.issinoho.radiotimes

import androidx.compose.foundation.layout.Arrangement
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Settings: is tvtimes connected, is Plex connected -- said plainly, with
 * the code-based way to (re)connect each (tvtimes pairing, plex.tv/link).
 * The raw links and the phone page are under **Advanced**, a fallback for
 * experts; tokens are never shown (only a tvtimes host). Focus starts on a
 * button, never a text field (Fire TV's keyboard).
 */
@Composable
fun SetupScreen(
    initial: String,
    initialPlex: String,
    canCancel: Boolean,
    phoneUrl: String?,
    lineup: Tv.Lineup,
    plexServers: List<PlexServer>,
    onConnect: (url: String, plex: String) -> Unit,
    onPlexLinked: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = advanced) { advanced = false }
    if (advanced) {
        AdvancedLinks(initial, initialPlex, phoneUrl, onConnect, onPlexLinked, onDone = { advanced = false })
        return
    }
    var pairing by remember { mutableStateOf(false) }
    var plexBusy by remember { mutableStateOf(false) }
    val hasTvtimes = initial.isNotBlank()
    val hasPlex = initialPlex.isNotBlank()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)

        if (!plexBusy) Section("tvtimes") {
            val (status, ok) = when {
                !hasTvtimes -> "Not connected" to false
                lineup is Tv.Lineup.Ready -> "Connected  ·  ${lineup.channels.size} channels  ·  ${hostOf(initial)}" to true
                lineup is Tv.Lineup.Failed -> "Can't connect to ${hostOf(initial)}: ${redactTokens(lineup.message)}" to false
                else -> "Connecting to ${hostOf(initial)}…" to null
            }
            StatusLine(status, ok)
            PairWithTvtimes(
                focusFirst = !hasTvtimes || hasPlex,
                onLinked = { link -> onConnect(link, initialPlex) },
                onBusy = { pairing = it },
                label = if (hasTvtimes) "Pair again" else "Pair with tvtimes",
            )
        }

        if (!pairing) Section("Plex") {
            val usable = plexServers.filter { it.error == null }
            val broken = plexServers.filter { it.error != null }
            val (status, ok) = when {
                !hasPlex -> "Not connected" to false
                usable.isNotEmpty() -> ("Connected  ·  ${usable.joinToString(", ") { it.name }}" +
                    if (broken.isNotEmpty()) "  ·  ${broken.size} not reachable" else "") to true
                broken.isNotEmpty() -> "Can't reach the Plex server${if (broken.size > 1) "s" else ""}" to false
                else -> "Connecting…" to null
            }
            StatusLine(status, ok)
            PlexLink(
                focusFirst = hasTvtimes && !hasPlex,
                showTitle = false,
                label = if (hasPlex) "Link Plex account again" else "Link Plex account",
                onBusy = { plexBusy = it },
                onLinked = onPlexLinked,
            )
        }

        if (!pairing && !plexBusy) {
            UpdateCheck()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (canCancel) Button(onClick = onCancel, modifier = Modifier.focusRing()) { Text("Back to channels") }
                OutlinedButton(onClick = { advanced = true }, modifier = Modifier.focusRing()) { Text("Advanced") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** ✓ connected (accent), ✗ needs connecting (warn), or in progress (dim). */
@Composable
private fun StatusLine(text: String, ok: Boolean?) {
    val (mark, color) = when (ok) {
        true -> "✓  " to Palette.accent
        false -> "✗  " to Palette.warn
        null -> "" to Palette.dim
    }
    Text(mark + text, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
}

/** The host (and port) of a tvtimes link: never its token. */
private fun hostOf(link: String): String = runCatching {
    val u = java.net.URI(link.trim())
    "${u.host}${if (u.port > 0) ":${u.port}" else ""}"
}.getOrDefault("tvtimes")

/**
 * Advanced: the raw tvtimes link and Plex links (tokens masked), and the
 * phone page that can send them. For when pairing / plex.tv/link can't be
 * used -- an older tvtimes, a Plex server not on an account.
 */
@Composable
private fun AdvancedLinks(
    initial: String,
    initialPlex: String,
    phoneUrl: String?,
    onConnect: (url: String, plex: String) -> Unit,
    onPlexLinked: (List<String>) -> Unit,
    onDone: () -> Unit,
) {
    var url by remember { mutableStateOf(initial) }
    var plex by remember(initialPlex) { mutableStateOf(initialPlex) }
    val valid = url.trim().let { it.startsWith("tvtimes://") || it.startsWith("tvtimess://") } && "token=" in url
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Advanced", color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "The links themselves, for when pairing or plex.tv/link can't be used. The tvtimes link is under " +
                    "tvtimes → Settings → Export feeds; send it from your phone with the code on the right, or type it.",
                color = Palette.dim, fontSize = 16.sp, lineHeight = 23.sp,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("tvtimes link") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConnect(url.trim(), plex) }),
                visualTransformation = MaskToken,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = plex,
                onValueChange = { plex = it },
                label = { Text("Plex servers (plex:// links, optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                visualTransformation = MaskToken,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onConnect(url.trim(), plex) }, enabled = valid, modifier = Modifier.focusRing()) { Text("Save and connect") }
                OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(back).focusRing()) { Text("Back") }
            }
        }
        Column(
            Modifier.width(330.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Send from your phone", color = Palette.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (phoneUrl != null) {
                QrCode(phoneUrl, Modifier.size(170.dp).align(Alignment.CenterHorizontally))
                Text("Scan, or open on a phone on the same Wi-Fi:", color = Palette.dim, fontSize = 13.sp)
                Text(phoneUrl.removePrefix("http://"), color = Palette.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("Not available: the TV has no home-network address.", color = Palette.dim, fontSize = 14.sp)
            }
        }
    }
}

/**
 * "Check for updates": asks GitHub now (not waiting for the hourly check)
 * and, when there's a newer release, downloads it and opens the system
 * installer. The first time, Fire OS asks to allow radiotimes to install
 * apps; after allowing it, press Update again.
 */
@Composable
private fun UpdateCheck() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var newer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The Check button turns into Update: keep focus on it rather than
    // letting it fall back to the top of the screen.
    val update = remember { FocusRequester() }
    LaunchedEffect(newer) { if (newer != null) runCatching { update.requestFocus() } }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("radiotimes ${BuildConfig.VERSION_NAME}", color = Palette.dim, fontSize = 14.sp)
        val version = newer
        if (version == null) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "Checking…"
                    scope.launch {
                        status = when (val found = Updates.checkNow(context)) {
                            is Updates.Found.Newer -> { newer = found.version; "radiotimes ${found.version} is available." }
                            Updates.Found.Current -> "You have the latest version."
                            is Updates.Found.Failed -> found.message
                        }
                        busy = false
                    }
                },
                modifier = Modifier.focusRing(),
            ) { Text("Check for updates") }
        } else {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "Downloading $version…"
                    scope.launch {
                        val result = Updates.downloadAndInstall(context, version) { pct -> status = "Downloading $version…  $pct%" }
                        status = when (result) {
                            Updates.Install.Started -> "Confirm the update on the next screen."
                            Updates.Install.NeedsPermission -> "Allow radiotimes to install apps, then press Update again."
                            is Updates.Install.Failed -> result.message
                        }
                        busy = false
                    }
                },
                modifier = Modifier.focusRequester(update).focusRing(),
            ) { Text("Update to $version") }
        }
        status?.let { Text(it, color = Palette.dim, fontSize = 14.sp, maxLines = 2) }
    }
}

/** plex.tv/link: show Plex's 4-character code, wait for it to be entered, collect every server. */
@Composable
internal fun PlexLink(
    focusFirst: Boolean,
    showTitle: Boolean = true,
    label: String = "Link Plex account",
    onBusy: (Boolean) -> Unit = {},
    onLinked: (List<String>) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFirst) runCatching { first.requestFocus() } }
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // While a code is up the caller can make room (Settings hides the rest).
    LaunchedEffect(code) { onBusy(code != null) }
    if (showTitle) Text("Plex", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    if (code == null) {
        OutlinedButton(onClick = { attempt++ }, modifier = Modifier.focusRequester(first).focusRing()) { Text(label) }
    } else {
        Text("Go to plex.tv/link and enter", color = Palette.dim, fontSize = 14.sp)
        Text(code!!, color = Palette.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
    }
    status?.let { Text(it, color = Palette.dim, fontSize = 13.sp) }
    if (attempt > 0) {
        LaunchedEffect(attempt) {
            status = "Asking Plex for a code…"
            val start = Engine.callPlex("plex_link_start", context.filesDir.absolutePath)
            bridgeError(start)?.let { status = "Couldn't reach plex.tv: ${redactTokens(it)}"; return@LaunchedEffect }
            val pin = org.json.JSONObject(start)
            code = pin.getString("code")
            status = "Waiting for you to enter it…"
            repeat(300) { // Plex's codes last 15 minutes
                kotlinx.coroutines.delay(3_000)
                val poll = Engine.callPlex("plex_link_poll", pin.getLong("id"))
                bridgeError(poll)?.let { code = null; status = redactTokens(it); return@LaunchedEffect }
                val r = org.json.JSONObject(poll)
                if (r.optBoolean("done")) {
                    val servers = r.getJSONArray("servers")
                    val links = (0 until servers.length()).map { servers.getJSONObject(it).getString("link") }
                    val names = (0 until servers.length()).map { servers.getJSONObject(it).optString("name") }
                    code = null
                    status = if (links.isEmpty()) "Linked, but this account has no servers." else "Linked ✓  ${names.joinToString(", ")}"
                    Log.i(TAG, "setup | plex.tv linked: ${links.size} server(s)")
                    if (links.isNotEmpty()) onLinked(links)
                    return@LaunchedEffect
                }
            }
            code = null
            status = "The code expired. Try again."
        }
    }
}

/** A QR code drawn from ZXing's bit matrix (no image files). */
@Composable
internal fun QrCode(text: String, modifier: Modifier) {
    val bitmap = remember(text) {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val bmp = android.graphics.Bitmap.createBitmap(matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp.asImageBitmap()
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap, contentDescription = "QR code for phone setup",
        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    )
}

/** The token is never shown (CLAUDE.md), only its length; editing still works. */
internal object MaskToken : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = Regex("""(?i)(token=)([^&,\s]*)""").replace(text.text) { m ->
            m.groupValues[1] + "•".repeat(m.groupValues[2].length)
        }
        // Same length as the input, so offsets map one to one.
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
