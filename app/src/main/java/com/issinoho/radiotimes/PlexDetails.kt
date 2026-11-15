package com.issinoho.radiotimes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The parts of a resolved Plex item the details page shows (bridge.py `plex_resolve`). */
class PlexDetail(val json: String) {
    private val o = JSONObject(json)
    private fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
    private fun list(k: String): List<String> =
        o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }.orEmpty()

    val title = o.optString("title")
    val seriesTitle = s("series_title")
    val poster = s("poster")
    val backdrop = s("backdrop")
    val year = s("year")
    val rating = s("rating")
    val contentRating = s("content_rating")
    val description = s("description")
    val tagline = s("tagline")
    val director = s("director")
    val studio = s("studio")
    val video = s("video")
    val audio = s("audio")
    val genres = list("genres")
    val cast = list("cast")
    val subtitles = list("subtitles") + (o.optJSONArray("sidecar_subtitles") ?: JSONArray()).let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("label")?.ifEmpty { null } }
    }
    val durationMs = if (o.isNull("duration_ms")) null else o.optLong("duration_ms").takeIf { it > 0 }
    val season = if (o.isNull("season")) null else o.optInt("season")
    val episode = if (o.isNull("episode")) null else o.optInt("episode")
    val resumeSeconds = if (o.isNull("resume_seconds")) null else o.optDouble("resume_seconds").takeIf { it > 30 }
}

private fun runtime(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private fun clock(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Everything Plex knows about a movie or episode, shown when it's chosen
 * in the browser; the buttons play it (resuming, or from the start).
 */
@Composable
fun PlexDetailScreen(detail: PlexDetail, onPlay: (fromStart: Boolean) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        detail.backdrop?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Legibility over any backdrop: dark on the left where the text is.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Palette.background, 0.55f to Palette.background.copy(alpha = 0.92f), 1f to Palette.background.copy(alpha = 0.45f)),
            ),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.seriesTitle?.let {
                    val numbering = listOfNotNull(detail.season?.let { s -> "Season $s" }, detail.episode?.let { e -> "Episode $e" }).joinToString(" · ")
                    Text(listOf(it, numbering).filter { x -> x.isNotEmpty() }.joinToString("  ·  "), color = Palette.dim, fontSize = 17.sp)
                }
                Text(detail.title, color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        detail.year, detail.contentRating, detail.durationMs?.let(::runtime), detail.rating?.let { "★ $it" },
                    ).joinToString("   ·   "),
                    color = Palette.text, fontSize = 16.sp,
                )
                if (detail.genres.isNotEmpty()) Text(detail.genres.joinToString(" · "), color = Palette.accent, fontSize = 15.sp)
                detail.tagline?.let { Text(it, color = Palette.dim, fontSize = 16.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                detail.description?.let {
                    Text(it, color = Palette.text, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                detail.director?.let { Credit("Director", it) }
                if (detail.cast.isNotEmpty()) Credit("Cast", detail.cast.joinToString(", "))
                detail.studio?.let { Credit("Studio", it) }
                val quality = listOfNotNull(detail.video, detail.audio, detail.subtitles.takeIf { it.isNotEmpty() }?.let { "Subtitles: ${it.distinct().joinToString(", ")}" })
                if (quality.isNotEmpty()) Credit("File", quality.joinToString("  ·  "))
                Spacer(Modifier.weight(1f))
                val duration = detail.durationMs
                val resume = detail.resumeSeconds
                if (resume != null && duration != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressBar((resume * 1000 / duration).toFloat(), Modifier.width(300.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("${runtime(duration - (resume * 1000).toLong())} left", color = Palette.dim, fontSize = 14.sp)
                    }
                }
                val first = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (resume != null) {
                        Button(onClick = { onPlay(false) }, modifier = Modifier.focusRequester(first).focusRing()) { Text("▶  Resume from ${clock(resume)}") }
                        OutlinedButton(onClick = { onPlay(true) }, modifier = Modifier.focusRing()) { Text("↺  Play from the start") }
                    } else {
                        Button(onClick = { onPlay(true) }, modifier = Modifier.focusRequester(first).focusRing()) { Text("▶  Play") }
                    }
                }
                LaunchedEffect(detail.json) {
                    repeat(20) {
                        if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
                        delay(30)
                    }
                }
            }
            Spacer(Modifier.width(36.dp))
            detail.poster?.let {
                AsyncImage(
                    model = it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(220.dp, 330.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)),
                )
            }
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    Row {
        Text(label, color = Palette.dim, fontSize = 14.sp, modifier = Modifier.width(78.dp))
        Text(value, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
