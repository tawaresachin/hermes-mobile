package com.hermes.mobile.ui.screens.terminal

import android.os.Handler
import android.os.Looper
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

    companion object {
        // One live gateway shell per process, mirroring SshTerminal's
        // singleton: tab switch / rotation re-attach instead of spawn.
        @Volatile var current: GatewayPtyClient? = null
        fun alive(): Boolean = current?.isOpen == true
    }

    @Volatile var isOpen: Boolean = false
        private set

    /** Connect + spawn the gateway shell at the given PTY geometry. */
    fun connect(cols: Int, rows: Int, cb: Callback) {
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
                post { cb.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val f = JSONObject(text)
                    when (f.optString("t")) {
                        "out" -> post {
                            cb.onOut(java.util.Base64.getDecoder().decode(f.getString("d")))
                        }
                        "exit" -> post { cb.onExit(f.optInt("code", 0)) }
                        "err" -> post { cb.onErr(f.optString("m", "gateway error")) }
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
                post { cb.onExit(code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                post { cb.onErr(t.message ?: "gateway connection failed") }
            }
        })
    }

    /** stdin bytes (the PTY echoes them; the gateway's shell owns the rest). */
    fun send(bytes: ByteArray) {
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
        ws?.close(1000, "user")
        ws = null
        isOpen = false
    }

    private fun post(block: () -> Unit) {
        main.post {
            try { block() } catch (_: Exception) { /* screen gone */ }
        }
    }
}
