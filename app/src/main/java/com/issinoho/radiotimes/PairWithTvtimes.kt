package com.issinoho.radiotimes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface PairState {
    data object Idle : PairState
    data object Searching : PairState
    data class Found(val servers: List<Pairing.Server>) : PairState
    data object Typing : PairState
    data class Checking(val typed: String) : PairState
    data class Code(val server: Pairing.Server, val started: Pairing.Started) : PairState
    data object Finishing : PairState
    data class Problem(val message: String) : PairState
}

/**
 * "Pair with tvtimes": find it on the network (or take its address), show
 * the code tvtimes issues, and wait for someone to enter it under tvtimes'
 * Settings → Devices. [onLinked] gets the finished tvtimes link.
 *
 * Focus starts on a button, never the address field (Fire TV's keyboard).
 */
@Composable
fun PairWithTvtimes(focusFirst: Boolean, onLinked: (String) -> Unit, onBusy: (Boolean) -> Unit = {}) {
    var state by remember { mutableStateOf<PairState>(PairState.Idle) }
    // Past the first button, the pairing needs the room: callers hide their alternatives.
    LaunchedEffect(state) { onBusy(state != PairState.Idle) }
    var typed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    fun search() {
        state = PairState.Searching
        scope.launch {
            val found = Pairing.discover()
            state = PairState.Found(found)
        }
    }

    fun pair(server: Pairing.Server) {
        if (!server.canPair) {
            state = PairState.Problem("That tvtimes (${server.version}) can't pair: it needs tvtimes 1.4.0 or newer. Use your phone or the link instead.")
            return
        }
        scope.launch {
            state = try {
                PairState.Code(server, Pairing.start(server, Tv.deviceName))
            } catch (e: Exception) {
                PairState.Problem("tvtimes didn't give a code: ${redactTokens(e.message.orEmpty())}")
            }
        }
    }

    fun checkTyped() {
        val t = typed.trim()
        if (t.isEmpty()) return
        state = PairState.Checking(t)
        scope.launch {
            val server = Pairing.check(t)
            if (server == null) state = PairState.Problem("No tvtimes answered at $t.") else pair(server)
        }
    }

    when (val s = state) {
        PairState.Idle -> {
            Button(onClick = { search() }, modifier = Modifier.focusRequester(first).focusRing()) { Text("Pair with tvtimes") }
            LaunchedEffect(Unit) { if (focusFirst) runCatching { first.requestFocus() } }
        }
        PairState.Searching -> Text("Looking for tvtimes on your network…", color = Palette.text, fontSize = 17.sp)
        is PairState.Found -> {
            val pick = remember { FocusRequester() }
            if (s.servers.isEmpty()) {
                Text("Didn't find tvtimes on this network.", color = Palette.text, fontSize = 17.sp)
            } else {
                Text("Found tvtimes:", color = Palette.dim, fontSize = 15.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.servers.forEachIndexed { i, server ->
                    Button(
                        onClick = { pair(server) },
                        modifier = (if (i == 0) Modifier.focusRequester(pick) else Modifier).focusRing(),
                    ) {
                        Text("${server.base.removePrefix("http://")}${if (server.version.isNotEmpty()) "  ·  v${server.version}" else ""}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { state = PairState.Typing },
                        modifier = (if (s.servers.isEmpty()) Modifier.focusRequester(pick) else Modifier).focusRing(),
                    ) { Text("Enter its address") }
                    OutlinedButton(onClick = { search() }, modifier = Modifier.focusRing()) { Text("Search again") }
                }
            }
            LaunchedEffect(s) { runCatching { pick.requestFocus() } }
        }
        PairState.Typing, is PairState.Checking -> {
            val go = remember { FocusRequester() }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("tvtimes address (e.g. 192.168.0.5:8888 or tv.example.com)") },
                singleLine = true,
                enabled = s is PairState.Typing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { checkTyped() }),
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            )
            if (s is PairState.Checking) {
                Text("Checking ${s.typed}…", color = Palette.dim, fontSize = 15.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { checkTyped() }, enabled = typed.isNotBlank(), modifier = Modifier.focusRequester(go).focusRing()) { Text("Pair") }
                    OutlinedButton(onClick = { search() }, modifier = Modifier.focusRing()) { Text("Search instead") }
                }
                // The button, not the field: the field would open the keyboard.
                LaunchedEffect(Unit) { runCatching { go.requestFocus() } }
            }
        }
        is PairState.Code -> {
            val cancel = remember { FocusRequester() }
            Text("In tvtimes, open Settings → Devices and enter:", color = Palette.dim, fontSize = 16.sp)
            Text(
                s.started.code.chunked(4).joinToString(" "),
                color = Palette.accent, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp, fontFamily = FontFamily.Monospace,
            )
            Text("${s.server.base.removePrefix("http://").removePrefix("https://")}/settings", color = Palette.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            var left by remember { mutableStateOf("") }
            Text("Waiting for approval…  $left", color = Palette.dim, fontSize = 14.sp)
            OutlinedButton(onClick = { state = PairState.Idle }, modifier = Modifier.focusRequester(cancel).focusRing()) { Text("Cancel") }
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
            LaunchedEffect(s) {
                while (true) {
                    val remaining = (s.started.expiresAt - System.currentTimeMillis()) / 1000
                    if (remaining <= 0) { state = PairState.Problem("The code expired. Try again."); return@LaunchedEffect }
                    left = "(code expires in ${(remaining + 59) / 60} min)"
                    delay(s.started.intervalMs)
                    val result = try {
                        Pairing.poll(s.server, s.started)
                    } catch (e: Exception) {
                        null // a blip; keep polling until the code expires
                    }
                    when (result) {
                        is Pairing.Poll.Approved -> {
                            state = PairState.Finishing
                            onLinked(Pairing.linkFor(s.server, result))
                            return@LaunchedEffect
                        }
                        Pairing.Poll.Expired -> { state = PairState.Problem("The code expired. Try again."); return@LaunchedEffect }
                        else -> {}
                    }
                }
            }
        }
        PairState.Finishing -> Text("Approved ✓  Connecting…", color = Palette.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        is PairState.Problem -> {
            val again = remember { FocusRequester() }
            Text(s.message, color = Palette.warn, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { search() }, modifier = Modifier.focusRequester(again).focusRing()) { Text("Search again") }
                OutlinedButton(onClick = { state = PairState.Typing }, modifier = Modifier.focusRing()) { Text("Enter its address") }
            }
            LaunchedEffect(s) { runCatching { again.requestFocus() } }
        }
    }
}
