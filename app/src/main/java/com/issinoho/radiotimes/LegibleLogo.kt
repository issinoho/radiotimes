package com.issinoho.radiotimes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import coil.size.Size
import coil.transform.Transformation

/**
 * Makes a channel logo readable on radiotimes' dark tiles.
 *
 * Logo feeds mix logos drawn for light backgrounds with ones for dark. A
 * black-on-transparent logo (epg.best's TCM: 13% of pixels drawn, mean
 * brightness 32/255) simply vanishes on a dark tile. So, judging only the
 * drawn (non-transparent) pixels:
 *  - dark and near-monochrome -> colours inverted, keeping transparency
 *    (black text becomes white, as a dark-theme logo would be);
 *  - dark but coloured -> composited onto a light rounded plate, since
 *    inverting would turn its brand colours into their opposites;
 *  - anything else -> unchanged.
 * Runs once per image in Coil's pipeline; Coil caches the result.
 */
object LegibleLogo : Transformation {
    override val cacheKey: String = "legible-logo-v1"

    private const val DARK = 90.0        // mean luminance (0-255) below which a logo is "dark"
    private const val MONOCHROME = 40.0  // mean chroma (max-min channel) below which it's "monochrome"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var drawn = 0
        var lum = 0.0
        var chroma = 0.0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            chroma += (maxOf(r, g, b) - minOf(r, g, b))
            drawn++
        }
        // Nothing drawn, or an opaque picture filling the frame (it brings its own background).
        if (drawn == 0 || drawn > pixels.size * 0.9) return input
        if (lum / drawn >= DARK) return input

        if (chroma / drawn < MONOCHROME) {
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (p and 0xFF000000.toInt()) or (p.inv() and 0x00FFFFFF)
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        val pad = maxOf(w, h) / 10
        val out = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E9F0.toInt() }
        val radius = pad * 1.5f
        canvas.drawRoundRect(RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), radius, radius, plate)
        canvas.drawBitmap(input, pad.toFloat(), pad.toFloat(), null)
        return out
    }
}
