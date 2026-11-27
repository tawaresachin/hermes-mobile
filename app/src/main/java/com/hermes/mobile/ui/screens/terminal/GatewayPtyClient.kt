package com.hermes.mobile.ui.screens.terminal

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Batch 4 client: gateway-side PTY bridge (hermes-mobile-plugin's
 * /api/mobile/pty/ws). The phone owns no shell here — the gateway host
 * spawns one in a sanitized pty and streams it over the authenticated
 * WebSocket, so no SSH daemon or credentials are involved.
 *
 * Frame protocol (JSON text frames; see the plugin's pty_routes.py):
 *   -> {"t":"hello","cols":N,"rows":N} {"t":"in","d":b64}
 *      {"t":"resize","cols":N,"rows":N} {"t":"close"}
 *   <- {"t":"ok"} {"t":"out","d":b64} {"t":"exit","code":N} {"t":"err","m":s}
 *
 * Threading: OkHttp delivers on its own threads; every callback hops to
 * the main looper so Compose state writes in the screen are safe.
 */
class GatewayPtyClient(private val baseUrl: String, private val apiKey: String) {

    interface Callback {
        fun onOpen()
        fun onOut(bytes: ByteArray)
        fun onExit(code: Int)
        fun onErr(message: String)
    }

    private val http = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val promptFilter = GatewayPromptFilter()
    private val replay = PtyReplayBuffer()
    @Volatile private var callback: Callback? = null

    companion object {
        // One live gateway shell per process, mirroring SshTerminal's
        // singleton: tab switch / rotation re-attach instead of spawn.
        @Volatile var current: GatewayPtyClient? = null
        fun alive(): Boolean = current?.isOpen == true
    }

    @Volatile var isOpen: Boolean = false
        private set

    /** True when this live shell belongs to the currently configured server.
     * A pairing change must not re-attach a shell from the old gateway. */
    fun matches(base: String, key: String): Boolean =
        baseUrl.trimEnd('/') == base.trimEnd('/') && apiKey == key

    /** Connect + spawn the gateway shell at the given PTY geometry. */
    fun connect(cols: Int, rows: Int, cb: Callback) {
        callback = cb
        if (isOpen && ws != null) {
            // Already live: never spawn a second shell for the same screen.
            resize(cols, rows)
            return
        }
        // A socket that is still connecting is replaced, never stacked.
        ws?.cancel()
        ws = null
        current = this
        val url = baseUrl.trimEnd('/')
        val wsUrl = url.replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/api/mobile/pty/ws"
        val req = Request.Builder().url(wsUrl)
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                webSocket.send(JSONObject()
                    .put("t", "hello").put("cols", cols).put("rows", rows)
                    .toString())
                post { callback?.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val f = JSONObject(text)
                    when (f.optString("t")) {
                        "out" -> {
                            // Decode + record off the main thread: a large
                            // `cat` must not push base64 work onto UI.
                            val raw = java.util.Base64.getDecoder().decode(f.getString("d"))
                            replay.add(raw)
                            val bytes = promptFilter.feed(raw)
                            post { callback?.onOut(bytes) }
                        }
                        "exit" -> {
                            isOpen = false
                            webSocket.close(1000, null)
                            if (current === this@GatewayPtyClient) current = null
                            post { callback?.onExit(f.optInt("code", 0)) }
                        }
                        "err" -> post { callback?.onErr(f.optString("m", "gateway error")) }
                    }
                } catch (_: Exception) {
                    // A malformed server frame never kills the session.
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                if (current === this@GatewayPtyClient) current = null
                post { callback?.onExit(code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                if (current === this@GatewayPtyClient) current = null
                post { callback?.onErr(t.message ?: "gateway connection failed") }
            }
        })
    }

    /** Adopt a live shell from a previous composition (rotation / tab switch).
     * Replays the recent output tail so the new WebView is not blank. Does
     * NOT fire onOpen: the screen is already in terminal mode and must not
     * print the welcome banner twice. */
    fun attach(cb: Callback) {
        callback = cb
        val snapshot = replay.snapshot()
        if (snapshot.isNotEmpty()) post { cb.onOut(snapshot) }
    }

    /** stdin bytes (the PTY echoes them; the gateway's shell owns the rest). */
    fun send(bytes: ByteArray) {
        if (!isOpen) return
        ws?.send(JSONObject()
            .put("t", "in")
            .put("d", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString())
    }

    fun resize(cols: Int, rows: Int) {
        if (isOpen) {
            ws?.send(JSONObject()
                .put("t", "resize").put("cols", cols).put("rows", rows)
                .toString())
        }
    }

    fun close() {
        // Null the callback BEFORE closing: the close handshake posts onExit
        // asynchronously, and a user-initiated Disconnect must not surface as
        // "Shell exited (1000)" on a screen that already left the session.
        callback = null
        ws?.close(1000, "user")
        ws = null
        isOpen = false
        replay.clear()
        if (current === this@GatewayPtyClient) current = null
        try { http.dispatcher.executorService.shutdown() } catch (_: Exception) { }
        try { http.connectionPool.evictAll() } catch (_: Exception) { }
    }

    private fun post(block: () -> Unit) {
        main.post {
            try { block() } catch (_: Exception) { /* screen gone */ }
        }
    }
}

/** Live identity the gateway reports for the shell it spawns
 *  (GET /api/mobile/pty/info). Every value comes from the server at request
 *  time — the app never hardcodes a user, host or path, and never writes a
 *  synthetic prompt into the terminal. */
internal data class GatewayPtyInfo(
    val user: String,
    val host: String,
    val hermesHome: String,
    val cwd: String,
)

internal const val PTY_INFO_UNAVAILABLE = "path unavailable"

/** Pure header subtitle, JVM-testable. Identity and path are server values;
 *  a null/blank result degrades to the honest [PTY_INFO_UNAVAILABLE]. */
internal fun gatewayPtySubtitle(info: GatewayPtyInfo?): String {
    if (info == null) return PTY_INFO_UNAVAILABLE
    val identity = listOf(info.user, info.host).filter { it.isNotBlank() }.joinToString("@")
    val path = info.cwd.takeIf { it.isNotBlank() } ?: info.hermesHome
    return if (identity.isBlank() || path.isBlank()) {
        PTY_INFO_UNAVAILABLE
    } else {
        "$identity · $path"
    }
}

/** Fetch the connected gateway's live identity using the stored server
 *  config (same Bearer key the PTY websocket uses). Null on any failure. */
internal suspend fun fetchGatewayPtyInfo(base: String, key: String): GatewayPtyInfo? =
    withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/api/mobile/pty/info")
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                .get().build()
            OkHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                if (!json.optBoolean("ok", false)) return@withContext null
                GatewayPtyInfo(
                    user = json.optString("user"),
                    host = json.optString("host"),
                    hermesHome = json.optString("hermes_home"),
                    cwd = json.optString("cwd"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

/** zsh's PROMPT_SP paints an inverse '%' box before the first prompt on a raw
 *  PTY. Drop only that exact sequence; all other bytes stay byte-for-byte. */
internal class GatewayPromptFilter {
    private val pending = ByteArray(MARKER.size)
    private var matched = 0

    fun feed(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val byte = bytes[i]
            if (matched > 0) {
                if (MARKER[matched] == byte) {
                    pending[matched++] = byte
                    if (matched == MARKER.size) matched = 0
                    i++
                    continue
                }
                out.write(pending, 0, matched)
                matched = 0
                continue // reprocess the mismatching byte
            }
            if (byte == MARKER[0]) pending[matched++] = byte
            else out.write(byte.toInt())
            i++
        }
        return out.toByteArray()
    }

    internal companion object {
        val MARKER = byteArrayOf(
            0x1B, '['.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(),
            0x1B, '['.code.toByte(), '7'.code.toByte(), 'm'.code.toByte(),
            '%'.code.toByte(),
            0x1B, '['.code.toByte(), '2'.code.toByte(), '7'.code.toByte(),
            'm'.code.toByte(),
            0x1B, '['.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(),
            0x1B, '['.code.toByte(), '0'.code.toByte(), 'm'.code.toByte()
        )
    }
}
