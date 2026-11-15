package com.issinoho.radiotimes

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Setup from a phone: while the setup screen is open, the TV serves a
 * one-page form on the home network, reached by scanning the QR code on
 * screen. Pasting links on a phone beats typing tokens with a remote.
 *
 * Deliberately tiny (a ServerSocket, one request per connection, no
 * library). Guarded by a one-time code that is in the QR/URL and shown on
 * the TV: a request without it gets nothing, so other devices on the
 * network can't configure the TV. It stops when the setup screen closes.
 */
class SetupServer(private val onSubmit: (tvtimes: String?, plex: List<String>) -> Unit) {
    val code: String = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    @Volatile private var socket: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    /** e.g. "http://192.168.0.31:8765/?c=K7Q2MX", or null if the TV has no IPv4 address or the port is taken. */
    var url: String? = null
        private set

    fun start(): String? {
        val ip = lanAddress() ?: return null
        val server = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            Log.w(TAG, "setup | can't listen on $PORT: ${e.message}")
            return null
        }
        socket = server
        url = "http://$ip:$PORT/?c=$code"
        thread(name = "setup-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
        Log.i(TAG, "setup | phone setup page on port $PORT")
        return url
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    /**
     * One connection. Must never throw: this runs on a bare thread, and an
     * uncaught exception there kills the whole app. Phone browsers open
     * spare connections that send nothing (they time out here), and Chrome
     * may try https:// first (a TLS handshake arrives); both used to crash
     * radiotimes, which the phone saw as ERR_TOO_MANY_RETRIES.
     */
    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val first = input.read()
            if (first < 0) return
            if (first == 0x16) { // TLS handshake: no https here; close so the browser falls back to http
                Log.i(TAG, "setup | https attempt refused (browser will retry over http)")
                return
            }
            val head = readHead(first, input) ?: return
            val lines = head.split("\r\n")
            val (method, target) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = if (length in 1..MAX_BODY) readBody(input, length) else ""
            val result = respondTo(method, target, body, client)
            Log.i(TAG, "setup | $method ${target.substringBefore('?')} -> $result")
        } catch (e: Exception) {
            Log.i(TAG, "setup | connection dropped: ${e.javaClass.simpleName}")
        } finally {
            // Close gracefully: finish sending, then drain anything unread, so
            // the reply isn't cut off by a reset.
            runCatching {
                client.shutdownOutput()
                client.soTimeout = 500
                val sink = ByteArray(1024)
                while (client.getInputStream().read(sink) > 0) Unit
            }
            runCatching { client.close() }
        }
    }

    /** The request line and headers, up to the blank line; null if it's too long or not text. */
    private fun readHead(first: Int, input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write(first)
        while (bytes.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            val n = bytes.size()
            if (n >= 4) {
                val a = bytes.toByteArray()
                if (a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) {
                    return String(a, 0, n - 4, Charsets.ISO_8859_1)
                }
            }
        }
        return null
    }

    private fun readBody(input: java.io.InputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    /** Writes the reply; returns a short description for the log (never the code or links). */
    private fun respondTo(method: String, target: String, body: String, client: Socket): String {
        val out = client.getOutputStream()
        fun respond(status: String, html: String): String {
            val bytes = html.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
            return status
        }

        val query = form(target.substringAfter('?', ""))
        if (method == "GET" && target.startsWith("/?") && query["c"] == code) return respond("200 OK", page(null))
        if (method == "POST" && target.startsWith("/setup") && body.isNotEmpty()) {
            val fields = form(body)
            if (fields["c"] != code) return respond("403 Forbidden", page("This page has expired. Scan the code on the TV again."))
            val tvtimes = fields["tvtimes"]?.trim()?.ifEmpty { null }
            val plex = Prefs.splitLinks(fields["plex"].orEmpty())
            val problem = when {
                tvtimes == null && plex.isEmpty() -> "Paste at least one link."
                tvtimes != null && !looksLikeTvtimes(tvtimes) ->
                    "That tvtimes link doesn't look right. In tvtimes: Settings → Export feeds."
                plex.any { !(it.startsWith("plex://") || it.startsWith("plexs://")) || "X-Plex-Token=" !in it } ->
                    "A Plex link doesn't look right: plex://host:32400?X-Plex-Token=…"
                else -> null
            }
            if (problem != null) return respond("200 OK", page(problem))
            Log.i(TAG, "setup | received from phone: tvtimes ${if (tvtimes != null) "yes" else "no"}, ${plex.size} Plex link(s)")
            main.post { onSubmit(tvtimes, plex) }
            return respond("200 OK", done())
        }
        if (method == "GET" && target.startsWith("/?")) return respond("403 Forbidden", page("This page has expired. Scan the code on the TV again."))
        return respond("404 Not Found", "<h1>Not found</h1>")
    }

    private fun looksLikeTvtimes(link: String) =
        (link.startsWith("tvtimes://") || link.startsWith("tvtimess://")) && "token=" in link

    private fun form(encoded: String): Map<String, String> =
        encoded.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun page(error: String?): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>radiotimes setup</title><style>
body{font-family:system-ui,sans-serif;background:#0b0a1f;color:#f5f5fa;margin:0;padding:24px;max-width:560px}
h1{font-size:24px;margin:0 0 4px}p{color:#a09cc0;line-height:1.45}
label{display:block;margin:20px 0 6px;font-weight:600}
input,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #3a3670;background:#16142e;color:#f5f5fa;font-size:16px}
textarea{min-height:90px}button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:24px;background:#db2777;color:#fff;font-size:17px;font-weight:600}
.err{background:#4a1d1d;color:#fecaca;padding:12px;border-radius:10px}
</style></head><body>
<h1>radiotimes</h1><p>Paste your links and press Send; your TV sets itself up.</p>
${if (error != null) "<p class=\"err\">${escape(error)}</p>" else ""}
<form method="post" action="/setup">
<input type="hidden" name="c" value="$code">
<label for="t">tvtimes link</label>
<input id="t" name="tvtimes" placeholder="tvtimess://your-host?token=…" autocapitalize="off" autocorrect="off" spellcheck="false">
<p>In tvtimes: Settings → Export feeds → copy the tvdinner link.</p>
<label for="p">Plex servers (optional)</label>
<textarea id="p" name="plex" placeholder="plex://192.168.0.10:32400?X-Plex-Token=…" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
<p>One per line. Or skip this and use “Link Plex account” on the TV.</p>
<button type="submit">Send to TV</button></form></body></html>
""".trimIndent()

    private fun done(): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>radiotimes setup</title><style>body{font-family:system-ui,sans-serif;background:#0b0a1f;color:#f5f5fa;padding:24px}p{color:#a09cc0}</style>
</head><body><h1>Sent ✓</h1><p>Look at your TV. You can close this page.</p></body></html>
""".trimIndent()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val PORT = 8765
        private const val MAX_BODY = 8_192
        private const val MAX_HEAD = 16_384
        // No 0/O, 1/I: it's read off a TV screen.
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        /** The TV's IPv4 address on the home network (Wi-Fi or Ethernet). */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
