package com.issinoho.radiotimes

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the picture fills the screen: Fit (whole picture, bars if the shapes
 * differ), Zoom (fills the screen, edges cropped -- e.g. a 4:3 SD channel
 * or a letterboxed film), Stretch (fills it, distorted). One setting for
 * live TV and Plex, remembered between sessions. Hold OK to cycle; the ☰
 * panels have a button too.
 *
 * A resize mode only acts on a mismatch between the video's shape and the
 * screen's. Many streams (Pluto TV via FastChannels, most IPTV) are always
 * 16:9 with any 4:3 or film bars burned into the picture, so for those
 * Zoom and Stretch would change nothing; [applyPicture] then works inside
 * the picture instead.
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

/** Zoom/Stretch inside a picture already the screen's shape: as for 4:3 pillarboxed in 16:9. */
private const val INNER_SCALE = 4f / 3f

/**
 * Show [view]'s video in [mode]. When the video already has the screen's
 * shape (within 5 %) and the view fills the screen, Zoom enlarges the
 * picture by 4/3 (cropping burned-in bars: pillarboxed 4:3 fills the
 * width, a letterboxed film the height) and Stretch widens it by 4/3
 * (pillarboxed 4:3 stretched to full width).
 *
 * Only the video surface grows -- larger than the screen and centred, the
 * excess off its edges -- so subtitles, the buffering spinner and Plex's
 * controller stay in place. It's resized rather than scaled because a
 * SurfaceView doesn't reliably follow view scaling on older Android (Fire
 * OS 7 is API 28). Not in the guide's corner preview, where the surface
 * would spill out of its box. Call again when the mode, the video size or
 * the view's size changes; it changes nothing when nothing needs changing.
 */
@OptIn(UnstableApi::class)
fun applyPicture(view: PlayerView, mode: PictureMode) {
    val surface = view.videoSurfaceView
    val size = view.player?.videoSize
    val videoAspect = if (size != null && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
    val w = view.width
    val h = view.height
    val root = view.rootView
    val fullScreen = w > 0 && h > 0 && root != null && w >= root.width * 0.9f && h >= root.height * 0.9f
    val sameShape = fullScreen && videoAspect > 0f && abs(videoAspect / (w.toFloat() / h) - 1f) < 0.05f
    val (sx, sy) = when {
        !sameShape -> 1f to 1f
        mode == PictureMode.Zoom -> INNER_SCALE to INNER_SCALE
        mode == PictureMode.Stretch -> INNER_SCALE to 1f
        else -> 1f to 1f
    }
    view.resizeMode = if (sx == 1f && sy == 1f) mode.resizeMode else AspectRatioFrameLayout.RESIZE_MODE_FIT
    surface ?: return
    val lp = surface.layoutParams ?: return
    val (pw, ph) = if (sx == 1f && sy == 1f) {
        ViewGroup.LayoutParams.MATCH_PARENT to ViewGroup.LayoutParams.MATCH_PARENT
    } else {
        (w * sx).roundToInt() to (h * sy).roundToInt()
    }
    if (lp.width == pw && lp.height == ph) return
    surface.layoutParams = FrameLayout.LayoutParams(pw, ph, Gravity.CENTER)
}

/**
 * Keep [applyPicture] current as [view] changes size (the guide's corner
 * preview and back) and as the video's size becomes known.
 */
@OptIn(UnstableApi::class)
fun PlayerView.followPicture(mode: () -> PictureMode) {
    addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        // Re-laying out from inside a layout pass is deferred a frame.
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) v.post { applyPicture(this, mode()) }
    }
}
