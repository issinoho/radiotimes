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

/**
 * Links, entered once. Three ways in, easiest first: scan the QR code and
 * paste on a phone ([SetupServer]); "Link Plex account" (plex.tv/link,
 * no tokens at all); or type with the remote. Focus starts on "Connect"
 * when a link is filled in, else on "Link Plex account" -- never a text
 * field, which opens the keyboard on focus on Android TV, and on Fire TV
 * opens a full-screen one that Back doesn't always dismiss.
 */
@Composable
fun SetupScreen(
    initial: String,
    initialPlex: String,
    canCancel: Boolean,
    phoneUrl: String?,
    onConnect: (url: String, plex: String) -> Unit,
    onPlexLinked: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var url by remember { mutableStateOf(initial) }
    var plex by remember(initialPlex) { mutableStateOf(initialPlex) }
    val valid = url.trim().let { it.startsWith("tvtimes://") || it.startsWith("tvtimess://") } && "token=" in url
    val field = remember { FocusRequester() }
    val connect = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("radiotimes", color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your tvtimes link is in tvtimes under Settings → Export feeds. The easiest way to get it here is " +
                    "from your phone: scan the code on the right.",
                color = Palette.dim, fontSize = 16.sp,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("tvtimes link") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConnect(url.trim(), plex) }),
                visualTransformation = MaskToken,
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
            OutlinedTextField(
                value = plex,
                onValueChange = { plex = it },
                label = { Text("Plex servers (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                visualTransformation = MaskToken,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onConnect(url.trim(), plex) }, enabled = valid, modifier = Modifier.focusRequester(connect).focusRing()) {
                    Text("Connect")
                }
                if (canCancel) OutlinedButton(onClick = onCancel, modifier = Modifier.focusRing()) { Text("Cancel") }
            }
            Text(
                "From a computer: adb shell am start -n com.issinoho.radiotimes/.MainActivity --es url '…' --es plex '…'",
                color = Palette.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
        Column(
            Modifier.width(330.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Set up from your phone", color = Palette.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (phoneUrl != null) {
                QrCode(phoneUrl, Modifier.size(170.dp).align(Alignment.CenterHorizontally))
                Text("Scan, or open on a phone on the same Wi-Fi:", color = Palette.dim, fontSize = 13.sp)
                Text(phoneUrl.removePrefix("http://"), color = Palette.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("Not available: the TV has no home-network address.", color = Palette.dim, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            PlexLink(focusFirst = !valid, onLinked = { links ->
                plex = links.joinToString(" ")
                onPlexLinked(links)
            })
        }
    }

    // Never a text field: on some devices focusing one opens the keyboard
    // straight away (Android TV does; Fire TV's can trap the remote).
    LaunchedEffect(Unit) {
        if (valid) runCatching { connect.requestFocus() }
    }
}

/** plex.tv/link: show Plex's 4-character code, wait for it to be entered, collect every server. */
@Composable
private fun PlexLink(focusFirst: Boolean, onLinked: (List<String>) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFirst) runCatching { first.requestFocus() } }
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("Plex", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    if (code == null) {
        OutlinedButton(onClick = { attempt++ }, modifier = Modifier.focusRequester(first).focusRing()) { Text("Link Plex account") }
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
private fun QrCode(text: String, modifier: Modifier) {
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
private object MaskToken : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = Regex("""(?i)(token=)([^&,\s]*)""").replace(text.text) { m ->
            m.groupValues[1] + "•".repeat(m.groupValues[2].length)
        }
        // Same length as the input, so offsets map one to one.
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
