package com.issinoho.radiotimes

import org.json.JSONArray
import org.json.JSONObject

/** One channel in tvtimes' order. `url` is the identity (never tvg-id). */
data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
)

/** Times are UTC epoch milliseconds, as bridge.py sends them. */
data class Programme(
    val title: String,
    val start: Long,
    val stop: Long,
    val description: String?,
    val category: String?,
    val year: String?,
) {
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

data class NowNext(val now: Programme?, val next: Programme?)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

fun parseChannels(json: String): List<Channel> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Channel(
            number = i + 1,
            name = o.optString("name"),
            url = o.getString("url"),
            logo = o.optStringOrNull("logo"),
            group = o.optStringOrNull("group"),
        )
    }
}

private fun parseProgramme(o: JSONObject?): Programme? = o?.let {
    Programme(
        title = it.optString("title"),
        start = it.optLong("start"),
        stop = it.optLong("stop"),
        description = it.optStringOrNull("description"),
        category = it.optStringOrNull("category"),
        year = it.optStringOrNull("year"),
    )
}

fun parseNowNext(json: String): Map<String, NowNext> {
    val o = JSONObject(json)
    val out = HashMap<String, NowNext>(o.length())
    for (url in o.keys()) {
        val e = o.getJSONObject(url)
        out[url] = NowNext(parseProgramme(e.optJSONObject("now")), parseProgramme(e.optJSONObject("next")))
    }
    return out
}

/** A bridge result is either the payload or `{"error": "..."}`. */
fun bridgeError(json: String): String? =
    json.trimStart().takeIf { it.startsWith("{") }?.let { JSONObject(it).optStringOrNull("error") }
