package com.issinoho.radiotimes

import androidx.media3.common.MimeTypes
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a tvtimes stream URL into something ExoPlayer can be told the type of.
 *
 * tvtimes' `/api/exports/stream/<id>` answers with a 302: to the real
 * upstream URL, or, for a source tvtimes relays (so it plays away from home),
 * to a tvtimes relay URL whose last segment carries an extension where one is
 * known (`stream.ts` for HDHomeRun). Provider credentials never sit in the
 * playlist. ExoPlayer chooses
 * between HLS and a progressive (MPEG-TS) source from the URI it is *given*,
 * before following any redirect -- and the tvtimes URL has no extension, so
 * an HLS channel would be fed to the progressive extractor and fail. So the
 * redirect is followed here first, and the type taken from where it lands.
 *
 * Hops stop as soon as a URL's extension settles the type, without
 * requesting it: many IPTV providers allow one connection per account, and
 * opening the stream here only to close it again can count against that
 * limit just as ExoPlayer connects.
 */
object StreamResolver {
    const val USER_AGENT = "radiotimes/0.1"

    data class Resolved(val url: String, val mimeType: String?, val hops: Int, val how: String)

    fun resolve(start: String, maxHops: Int = 5): Resolved {
        var url = start
        var hops = 0
        while (true) {
            mimeFromExtension(url)?.let { return Resolved(url, it.ifEmpty { null }, hops, "extension") }

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("HTTP $code with no Location")
                    url = URL(URL(url), location).toString()
                    if (++hops > maxHops) throw IllegalStateException("more than $maxHops redirects")
                    continue
                }
                if (code >= 400) throw IllegalStateException("HTTP $code from ${redact(url)}")
                val contentType = conn.contentType.orEmpty().lowercase()
                val mime = if ("mpegurl" in contentType) MimeTypes.APPLICATION_M3U8 else null
                return Resolved(url, mime, hops, "content-type '$contentType'")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** HLS mime for .m3u8, "" for a known progressive container, null if the URL doesn't say. */
    private fun mimeFromExtension(url: String): String? {
        val path = URL(url).path.lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".mkv") -> ""
            else -> null
        }
    }

    /**
     * Safe to log or show: scheme, host and file extension only. Upstream
     * Xtream URLs carry the provider username and password in the path, and
     * tvtimes URLs carry the export token in the query.
     */
    fun redact(url: String): String = try {
        val u = URL(url)
        val ext = u.path.substringAfterLast('/', "").substringAfterLast('.', "")
        "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}/…${if (ext.isNotEmpty()) ".$ext" else ""}"
    } catch (e: Exception) {
        "<unparseable url>"
    }
}

/**
 * Mask `token=` / `ticket=` (tvtimes) and `X-Plex-Token=` values in free
 * text, and the token path segment of tvtimes relay URLs
 * (`/api/exports/relay/<profile>/<token>/<name>`).
 */
fun redactTokens(text: String): String =
    Regex("""(?i)(/relay/[^/\s"?]+/)[^/\s"?]+""").replace(
        Regex("""(?i)((?:token|ticket)=)[^&"\s]+""").replace(text, "$1…"),
        "$1…",
    )
