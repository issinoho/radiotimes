package com.issinoho.radiotimes

import androidx.compose.foundation.layout.Arrangement
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
 * The tvtimes link, entered once. When a link is already filled in, focus
 * starts on "Connect", not the text field: selecting the field opens Fire
 * TV's full-screen keyboard, which Back doesn't always dismiss.
 */
@Composable
fun SetupScreen(initial: String, canCancel: Boolean, onConnect: (String) -> Unit, onCancel: () -> Unit) {
    var url by remember { mutableStateOf(initial) }
    val valid = url.trim().let { it.startsWith("tvtimes://") || it.startsWith("tvtimess://") } && "token=" in url
    val field = remember { FocusRequester() }
    val connect = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("radiotimes", color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "Enter your tvtimes link. In tvtimes it's under Settings → Export feeds, and looks like " +
                "tvtimess://your-host?token=…",
            color = Palette.dim,
            fontSize = 17.sp,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("tvtimes link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConnect(url.trim()) }),
            visualTransformation = MaskToken,
            modifier = Modifier.width(820.dp).focusRequester(field),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { onConnect(url.trim()) }, enabled = valid, modifier = Modifier.focusRequester(connect).focusRing()) {
                Text("Connect")
            }
            if (canCancel) OutlinedButton(onClick = onCancel, modifier = Modifier.focusRing()) { Text("Cancel") }
        }
        Text(
            "Typing a token with the remote is slow. From a computer on the same network you can send it instead:",
            color = Palette.dim,
            fontSize = 14.sp,
        )
        Text(
            "adb shell am start -n com.issinoho.radiotimes/.MainActivity --es url 'tvtimess://host?token=…'",
            color = Palette.dim,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }

    LaunchedEffect(Unit) {
        runCatching { if (valid) connect.requestFocus() else field.requestFocus() }
    }
}

/** The token is never shown (CLAUDE.md), only its length; editing still works. */
private object MaskToken : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = Regex("""(token=)([^&]*)""").replace(text.text) { m ->
            m.groupValues[1] + "•".repeat(m.groupValues[2].length)
        }
        // Same length as the input, so offsets map one to one.
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
