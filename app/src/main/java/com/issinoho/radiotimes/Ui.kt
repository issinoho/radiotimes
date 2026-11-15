package com.issinoho.radiotimes

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Date

object Palette {
    val background = Color(0xFF0B0A1F)
    val surface = Color(0xFF16142E)
    val focus = Color(0xFF2A2660)
    val accent = Color(0xFFDB2777)
    val text = Color(0xFFF5F5FA)
    val dim = Color(0xFFA09CC0)
    val faint = Color(0x33FFFFFF)
    val warn = Color(0xFFFBBF24)
}

@Composable
fun RadiotimesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            background = Palette.background,
            surface = Palette.surface,
            onSurface = Palette.text,
            onBackground = Palette.text,
        ),
        content = content,
    )
}

/** Wall-clock time that recomposes every [stepMs], for clocks and progress bars. */
@Composable
fun rememberNow(stepMs: Long = 15_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stepMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(stepMs - now % stepMs)
        }
    }
    return now
}

/** Device zone and 12/24 h preference; bridge.py only ever sends UTC epoch ms. */
@Composable
fun clockText(epochMs: Long): String =
    DateFormat.getTimeFormat(LocalContext.current).format(Date(epochMs))

/** "3 h ago" style age for the guide status line. */
fun ageText(updated: Long, now: Long): String {
    val minutes = ((now - updated) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

@Composable
fun ChannelLogo(channel: Channel, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x14FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(4.dp).fillMaxWidth().fillMaxHeight(),
            )
        } else {
            Text(
                channel.name.take(3).uppercase(),
                color = Palette.dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.faint),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(Palette.accent),
        )
    }
}

/** A visible ring on focus: Material 3 buttons barely change when focused, which is unusable from a sofa. */
fun Modifier.focusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) Palette.text else Color.Transparent, RoundedCornerShape(50))
}
