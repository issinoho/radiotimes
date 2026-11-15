package com.issinoho.radiotimes

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Phase 0: the engine probe.
 *
 * Not the app -- a measurement. It runs bridge.probe() against a real
 * tvtimes feed on the Stick and shows how long each stage took, so the
 * decision to build the UI on embedded tvdinner rests on numbers from the
 * actual hardware. "Play first channel" checks the other half: that
 * ExoPlayer on the Stick decodes what tvtimes hands it.
 *
 * Typing a token with a remote is miserable, so the link can be passed in:
 *   adb shell am start -n com.issinoho.radiotimes/.MainActivity \
 *     --es url 'tvtimess://host?token=…' --ez autorun true
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        intent.getStringExtra(EXTRA_URL)?.let { prefs.edit().putString(KEY_URL, it).apply() }
        val initialUrl = prefs.getString(KEY_URL, "").orEmpty()
        val autorun = intent.getBooleanExtra(EXTRA_AUTORUN, false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0A1F)) {
                    ProbeScreen(
                        initialUrl = initialUrl,
                        autorun = autorun,
                        cacheDir = cacheDir.absolutePath,
                        onUrlUsed = { prefs.edit().putString(KEY_URL, it).apply() },
                        onPlay = { url, name -> startActivity(PlayerActivity.intent(this, url, name)) },
                    )
                }
            }
        }
    }

    companion object {
        const val PREFS = "radiotimes"
        const val KEY_URL = "tvtimes_url"
        const val EXTRA_URL = "url"
        const val EXTRA_AUTORUN = "autorun"
    }
}

private fun deviceSummary(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
        " · ${Build.SUPPORTED_ABIS.firstOrNull()} · heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB"

/** One line per stage, readable at TV distance, from the probe's JSON. */
private fun summarise(report: JSONObject): String = buildString {
    appendLine(deviceSummary())
    appendLine(
        "python ${report.optString("python")} (${report.optString("machine")}) · tvdinner ${report.optString("tvdinner")}" +
            " · start ${RadiotimesApp.pythonStartMs} ms · import ${Engine.importMs} ms",
    )
    val steps = report.optJSONArray("steps")
    for (i in 0 until (steps?.length() ?: 0)) {
        val s = steps!!.getJSONObject(i)
        val rss = s.optLong("rss_kb", 0) / 1024
        append("${s.optString("step").padEnd(11)} ${"%6.2f".format(s.optDouble("seconds"))} s   peak ${rss} MB")
        if (s.has("error")) append("   ERROR ${s.optString("error")}")
        appendLine()
    }
    if (report.has("channels")) appendLine("channels: ${report.optInt("channels")} · with guide: ${report.optInt("channels_with_guide")}")
    report.optJSONObject("guide")?.let { g ->
        val mb = report.optLong("epg_bytes", 0) / (1024.0 * 1024.0)
        appendLine("guide: ${g.optInt("programmes")} programmes · ${g.optInt("epg_channels")} EPG channels · ${"%.1f".format(mb)} MB XMLTV")
    }
    if (report.has("now_next_seconds")) appendLine("now/next for all channels: ${report.optDouble("now_next_seconds")} s")
    report.optJSONArray("samples")?.let { arr ->
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            appendLine("  ${s.optString("channel")}: ${if (s.isNull("now")) "—" else s.optString("now")}")
        }
    }
}

@Composable
private fun ProbeScreen(
    initialUrl: String,
    autorun: Boolean,
    cacheDir: String,
    onUrlUsed: (String) -> Unit,
    onPlay: (url: String, name: String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("Enter your tvtimes link and run the probe.") }
    var raw by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var reportFocused by remember { mutableStateOf(false) }

    fun run() {
        if (running || url.isBlank()) return
        running = true
        summary = "Running… (a cold guide load can take a while)"
        raw = ""
        onUrlUsed(url.trim())
        scope.launch {
            val json = Engine.call("probe", url.trim(), cacheDir)
            val report = JSONObject(json)
            val text = summarise(report)
            // Logged so the whole result can be pasted from `adb logcat -s radiotimes`.
            text.lines().forEach { Log.i(TAG, "probe | $it") }
            Log.i(TAG, "probe json | ${redactTokens(json)}")
            summary = text
            raw = redactTokens(report.toString(2))
            first = report.optJSONObject("first_channel")?.let { it.getString("url") to it.getString("name") }
            running = false
        }
    }

    LaunchedEffect(Unit) { if (autorun) run() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("radiotimes · engine probe", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("tvtimes link (tvtimess://host?token=…)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { run() }, enabled = !running) { Text(if (running) "Running…" else "Run probe") }
            Button(onClick = { first?.let { onPlay(it.first, it.second) } }, enabled = first != null && !running) {
                Text(first?.let { "Play ${it.second}" } ?: "Play first channel")
            }
        }
        // Focusable so the remote's D-pad can scroll a report taller than the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, if (reportFocused) Color(0xFFDB2777) else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                .onFocusChanged { reportFocused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { scroll.animateScrollBy(240f) }; scroll.canScrollForward }
                        KeyEvent.KEYCODE_DPAD_UP -> { scope.launch { scroll.animateScrollBy(-240f) }; scroll.canScrollBackward }
                        else -> false
                    }
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(summary, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color.White)
                if (raw.isNotEmpty()) Text(raw, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFA5B4FC))
            }
        }
    }
}
