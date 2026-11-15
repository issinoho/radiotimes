package com.issinoho.radiotimes

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Find-and-approve pairing with tvtimes (its `/api/pairing/start` and `/poll`, tvtimes
 * PR #238): find tvtimes on the LAN or take its address, ask for a code,
 * show it, and poll until someone enters it under tvtimes' Settings →
 * Devices; the answer is this TV's own export token, from which the usual
 * `tvtimes(s)://` link is built. No tvdinner involved: this is radiotimes
 * talking to tvtimes, before tvdinner has a link to work with.
 *
 * Tokens never reach a log: only hosts are logged.
 */
object Pairing {
    /** A tvtimes that answered `/api/healthz` as itself. `base` has no trailing slash. */
    data class Server(val base: String, val version: String, val canPair: Boolean)

    data class Started(val code: String, val secret: String, val intervalMs: Long, val expiresAt: Long)

    sealed interface Poll {
        data object Pending : Poll
        data class Approved(val token: String, val origin: String?) : Poll
        data object Expired : Poll
    }

    /** Ports a home-lab tvtimes usually answers on: compose's default, plain http, the container's own. */
    private val PORTS = buildList {
        add(8888); add(80); add(8000)
        if (BuildConfig.DEBUG) add(8899) // tools/fake_tvtimes.py
    }
    private const val PROBE_CONNECT_MS = 350
    private const val PROBE_READ_MS = 1_200
    private const val PARALLEL = 64

    /**
     * Probe every address on the TV's /24 for a tvtimes healthz. A few
     * seconds: most addresses refuse or time out fast. Same-host duplicates
     * (one tvtimes on two ports) are kept; the user picks.
     */
    suspend fun discover(): List<Server> = withContext(Dispatchers.IO) {
        val own = SetupServer.lanAddress() ?: return@withContext emptyList()
        val prefix = own.substringBeforeLast('.')
        val gate = Semaphore(PARALLEL)
        val started = System.currentTimeMillis()
        val found = coroutineScope {
            (1..254).flatMap { host -> PORTS.map { port -> "$prefix.$host" to port } }
                .map { (host, port) ->
                    async { gate.withPermit { health(if (port == 80) "http://$host" else "http://$host:$port", PROBE_CONNECT_MS) } }
                }
                .awaitAll()
                .filterNotNull()
        }
        Log.i(TAG, "pair | scanned $prefix.0/24 in ${System.currentTimeMillis() - started} ms: ${found.joinToString { it.base }.ifEmpty { "nothing" }}")
        found
    }

    /**
     * What the user typed: an IP, host, host:port, or a URL with a path (a
     * tvtimes behind a reverse proxy). Tries https then http when no scheme
     * is given, and :8888 when no port either.
     */
    suspend fun check(typed: String): Server? = withContext(Dispatchers.IO) {
        val t = typed.trim().trimEnd('/')
        if (t.isEmpty()) return@withContext null
        val candidates = if ("://" in t) {
            listOf(t)
        } else {
            val hasPort = Regex("""^[^/]+:\d+""").containsMatchIn(t)
            buildList {
                add("https://$t"); add("http://$t")
                if (!hasPort) {
                    val host = t.substringBefore('/')
                    val path = t.removePrefix(host)
                    add("http://$host:8888$path")
                }
            }
        }
        candidates.firstNotNullOfOrNull { health(it, 3_000) }
    }

    private fun health(base: String, connectMs: Int): Server? = runCatching {
        val o = JSONObject(http("GET", "$base/api/healthz", null, connectMs, PROBE_READ_MS.coerceAtLeast(connectMs)).second)
        if (o.optString("app") != "tvtimes") return null
        Server(base, o.optString("version"), o.optBoolean("pairing"))
    }.getOrNull()

    suspend fun start(server: Server, deviceName: String): Started = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", deviceName.take(120)).toString()
        val (code, text) = http("POST", "${server.base}/api/pairing/start", body, 5_000, 10_000)
        if (code != 200) error(detail(text) ?: "tvtimes said HTTP $code")
        val o = JSONObject(text)
        Log.i(TAG, "pair | code issued by ${host(server.base)}")
        Started(
            code = o.getString("code"),
            secret = o.getString("poll_secret"),
            intervalMs = o.optLong("interval", 3) * 1000,
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000,
        )
    }

    suspend fun poll(server: Server, started: Started): Poll = withContext(Dispatchers.IO) {
        val body = JSONObject().put("poll_secret", started.secret).toString()
        val (code, text) = http("POST", "${server.base}/api/pairing/poll", body, 5_000, 10_000)
        when (code) {
            200 -> {
                val o = JSONObject(text)
                if (o.optString("status") == "approved") Poll.Approved(o.getString("token"), o.optString("origin").ifEmpty { null })
                else Poll.Pending
            }
            404 -> Poll.Expired
            else -> error(detail(text) ?: "tvtimes said HTTP $code")
        }
    }

    /**
     * The link to save. tvtimes writes its stream URLs from its public
     * origin, and tvdinner matches watch reports against the link's own
     * base, so prefer the origin when this TV can reach it; else the
     * address it was found at.
     */
    suspend fun linkFor(server: Server, approved: Poll.Approved): String {
        val origin = approved.origin?.trimEnd('/')
        val base = if (origin != null && origin != server.base && health(origin, 3_000) != null) origin else server.base
        val uri = URI(base)
        val scheme = if (uri.scheme == "https") "tvtimess" else "tvtimes"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        Log.i(TAG, "pair | paired with ${host(base)}")
        return "$scheme://${uri.host}$port$path?token=${URLEncoder.encode(approved.token, "UTF-8")}"
    }

    private fun host(base: String) = runCatching { URI(base).let { "${it.host}${if (it.port > 0) ":${it.port}" else ""}" } }.getOrDefault("?")

    private fun detail(text: String): String? = runCatching { JSONObject(text).optString("detail").ifEmpty { null } }.getOrNull()

    private fun http(method: String, url: String, body: String?, connectMs: Int, readMs: Int): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "radiotimes/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text.take(64_000)
        } finally {
            conn.disconnect()
        }
    }
}
