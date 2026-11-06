package com.hermes.mobile.ui.screens.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Owns the xterm.js WebView and the write path into it. Writes that arrive
 * before the page reports ready are queued (bounded) instead of dropped —
 * the PTY starts streaming the moment the connection opens.
 */
class TerminalWebViewState {

    private var webView: WebView? = null
    private var ready = false
    private var cols = 0
    private var rows = 0

    // "b:<base64>" or "t:<text>". ArrayDeque is not thread-safe; every
    // entry point below runs on the main thread (bridge calls are posted).
    private val pending = ArrayDeque<String>()
    private var pendingChars = 0

    /** Last geometry reported by xterm's fit addon, or null before layout. */
    fun geometry(): Pair<Int, Int>? = if (cols > 0 && rows > 0) cols to rows else null

    internal fun attach(view: WebView) {
        if (webView !== view) {
            webView = view
            ready = false
        }
    }

    internal fun detach() {
        val view = webView
        webView = null
        ready = false
        pending.clear()
        pendingChars = 0
        if (view != null) {
            view.removeJavascriptInterface("HermesBridge")
            view.destroy()
        }
    }

    internal fun onReady() {
        ready = true
        while (pending.isNotEmpty()) dispatch(pending.removeFirst())
        pendingChars = 0
        webView?.requestFocus()
    }

    internal fun setSize(c: Int, r: Int) {
        cols = c
        rows = r
    }

    fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        send("b:$b64")
    }

    fun writeText(text: String) {
        if (text.isEmpty()) return
        send("t:$text")
    }

    fun clear() {
        pending.clear()
        pendingChars = 0
        if (ready) js("window.HermesTerminal.clear()")
    }

    fun focus() {
        if (ready) js("window.HermesTerminal.focus()")
    }

    fun find(query: String, incremental: Boolean, backwards: Boolean) {
        if (!ready) return
        js("window.HermesTerminal.find(${JSONObject.quote(query)}, $incremental, $backwards)")
    }

    fun clearFind() {
        if (ready) js("window.HermesTerminal.clearFind()")
    }

    fun copySelection() {
        if (ready) js("window.HermesTerminal.copySelection()")
    }

    private fun send(item: String) {
        if (ready) dispatch(item) else enqueue(item)
    }

    private fun dispatch(item: String) {
        if (item.startsWith("b:")) {
            js("window.HermesTerminal.writeBase64('${item.substring(2)}')")
        } else {
            js("window.HermesTerminal.writeText(${JSONObject.quote(item.substring(2))})")
        }
    }

    private fun enqueue(item: String) {
        pending.addLast(item)
        pendingChars += item.length
        // Page load is short; the cap only protects against a very slow
        // WebView. Dropping the oldest head is fine before first paint.
        while (pendingChars > 262_144 && pending.size > 1) {
            pendingChars -= pending.removeFirst().length
        }
    }

    private fun js(code: String) {
        webView?.evaluateJavascript(code, null)
    }
}

/**
 * JS → Kotlin. @JavascriptInterface methods are called on a WebView
 * background thread, so every entry hops to the main looper before it
 * touches Compose state or the WebView.
 */
internal class TerminalBridge(
    private val state: TerminalWebViewState,
    private val onInput: (ByteArray) -> Unit,
    private val onResize: (Int, Int) -> Unit,
    private val onFindResult: (Int, Int) -> Unit,
    private val context: Context
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun ready() = main.post { state.onReady() }

    @JavascriptInterface
    fun input(data: String) = main.post { onInput(data.toByteArray(Charsets.UTF_8)) }

    @JavascriptInterface
    fun resized(cols: Int, rows: Int) = main.post {
        state.setSize(cols, rows)
        onResize(cols, rows)
    }

    @JavascriptInterface
    fun findResult(index: Int, count: Int) = main.post { onFindResult(index, count) }

    @JavascriptInterface
    fun copy(text: String) = main.post {
        if (text.isBlank()) return@post
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@post
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
}

/** Search status label; index -1 means the addon stopped tracking matches. */
internal fun findStatus(index: Int, count: Int, hasQuery: Boolean): String = when {
    !hasQuery -> ""
    count <= 0 -> "No matches"
    index < 0 -> "$count+ matches"
    else -> "${index + 1}/$count"
}

/**
 * Live terminal surface: xterm.js in a WebView (screen grid, scrollback,
 * alternate screen, selection) plus a Compose find/copy toolbar.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalPanel(
    state: TerminalWebViewState,
    sendInput: (ByteArray) -> Unit,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var findOpen by remember { mutableStateOf(false) }
    val queryState = remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val findFocus = remember { FocusRequester() }

    val currentSend = rememberUpdatedState(sendInput)
    val currentResize = rememberUpdatedState(onResize)

    val bridge = remember {
        TerminalBridge(
            state = state,
            onInput = { currentSend.value(it) },
            onResize = { cols, rows -> currentResize.value(cols, rows) },
            onFindResult = { index, count ->
                status = findStatus(index, count, queryState.value.isNotBlank())
            },
            context = context.applicationContext
        )
    }

    fun closeFind() {
        findOpen = false
        queryState.value = ""
        status = ""
        state.clearFind()
        state.focus()
    }

    LaunchedEffect(findOpen) {
        if (findOpen) findFocus.requestFocus()
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { state.detach() }
    }

    Column(modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    // Local bundle only: no network, no content:// or
                    // cross-file access, and every navigation is refused.
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.textZoom = 100
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    setBackgroundColor(0xFF14161A.toInt())
                    addJavascriptInterface(bridge, "HermesBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = true
                    }
                    state.attach(this)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadUrl("file:///android_asset/terminal/index.html")
                }
            },
            update = { state.attach(it) }
        )

        if (findOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedTextField(
                    value = queryState.value,
                    onValueChange = {
                        queryState.value = it
                        if (it.isBlank()) status = ""
                        state.find(it, true, false)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(findFocus),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    placeholder = { Text("Find in terminal") }
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = {
                    state.find(queryState.value, false, true)
                }) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Previous match") }
                IconButton(onClick = {
                    state.find(queryState.value, false, false)
                }) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Next match") }
                IconButton(onClick = { closeFind() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = {
                if (findOpen) {
                    closeFind()
                } else {
                    findOpen = true
                }
            }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Find", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { state.copySelection() }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
