package com.issinoho.radiotimes

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * How the picture fills the screen: Fit (whole picture, bars if the shapes
 * differ), Zoom (fills the screen, edges cropped -- e.g. a 4:3 SD channel
 * or a letterboxed film), Stretch (fills it, distorted). One setting for
 * live TV and Plex, remembered between sessions. Hold OK to cycle; Plex's
 * ☰ panel has a button too.
 */
@OptIn(UnstableApi::class)
enum class PictureMode(val label: String, val resizeMode: Int) {
    Fit("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Zoom("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    Stretch("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL);

    fun next(): PictureMode = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val KEY = "picture_mode"

        fun load(context: Context): PictureMode =
            context.getSharedPreferences("radiotimes", Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { name -> entries.firstOrNull { it.name == name } } ?: Fit

        fun save(context: Context, mode: PictureMode) =
            context.getSharedPreferences("radiotimes", Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }
}
