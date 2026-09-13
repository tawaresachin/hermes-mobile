package com.hermes.mobile.ui.preview

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermes.mobile.DiagLog
import com.hermes.mobile.data.model.Message
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.screens.chat.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen in-chat file preview: md/html/pdf/csv/json/code/xlsx/docx/pptx
 * render natively; anything unparseable or oversize falls back to the
 * external-viewer hand-off. Parsed off the main thread (parses live in
 * FilePreviewParsers.kt, JVM-unit-tested without a device).
 */

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Text(val body: String, val monospace: Boolean = false) : PreviewState
    data class Markdown(val body: String) : PreviewState
    data class Html(val body: String) : PreviewState
    data class Table(val header: List<String>, val rows: List<List<String>>) : PreviewState
    data class Sheets(val sheets: List<Sheet>) : PreviewState
    data class PdfPages(val pages: List<android.graphics.Bitmap>, val pageCount: Int) : PreviewState
    data class Unsupported(val reason: String) : PreviewState
}

private const val MAX_INLINE_BYTES = 25L * 1024 * 1024

@Composable
fun FilePreviewSheet(
    message: Message,
    repository: HermesRepository,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember(message.id) { mutableStateOf<PreviewState>(PreviewState.Loading) }
    var title by remember(message.id) { mutableStateOf(message.attachmentName ?: "Preview") }

    LaunchedEffect(message.id) {
      try {
        val url = message.attachmentUrl ?: run { onDismiss(); return@LaunchedEffect }
        val name = message.attachmentName ?: url.substringAfterLast('/').ifBlank { "attachment" }
        val mime = message.attachmentType.orEmpty()
        title = name
        val kind = previewKindFor(name, mime)
        if (kind == PreviewKind.Image || kind == PreviewKind.Other) {
            state = PreviewState.Unsupported("No inline preview for this type.")
            return@LaunchedEffect
        }
        val bytes = repository.downloadAttachment(url)
            ?: run { state = PreviewState.Unsupported("Download failed."); return@LaunchedEffect }
        if (bytes.size.toLong() > MAX_INLINE_BYTES) {
            state = PreviewState.Unsupported("File too large to preview (${bytes.size / 1024 / 1024} MB).")
            return@LaunchedEffect
        }
        state = withContext(Dispatchers.IO) {
            runCatching {
                when (kind) {
                    PreviewKind.Markdown -> PreviewState.Markdown(bytes.toString(Charsets.UTF_8))
                    PreviewKind.Html -> PreviewState.Html(bytes.toString(Charsets.UTF_8))
                    PreviewKind.Csv -> {
                        val rows = parseCsv(bytes.toString(Charsets.UTF_8),
                            delimiter = if (name.endsWith(".tsv", true)) '\t' else ',')
                        if (rows.isEmpty()) PreviewState.Unsupported("Empty table.")
                        else PreviewState.Table(rows.first(), rows.drop(1))
                    }
                    PreviewKind.Json -> PreviewState.Text(prettyJson(bytes.toString(Charsets.UTF_8)), monospace = true)
                    PreviewKind.Code -> PreviewState.Text(bytes.toString(Charsets.UTF_8), monospace = true)
                    PreviewKind.Spreadsheet -> {
                        val sheets = parseXlsx(bytes).filter { it.rows.isNotEmpty() }
                        if (sheets.isEmpty()) PreviewState.Unsupported("No readable sheets.")
                        else PreviewState.Sheets(sheets)
                    }
                    PreviewKind.Word -> PreviewState.Markdown(parseDocx(bytes).ifBlank { "(empty document)" })
                    PreviewKind.Slides -> PreviewState.Text(parsePptx(bytes).ifBlank { "(empty presentation)" })
                    PreviewKind.Pdf -> renderPdf(context, bytes)
                    PreviewKind.Image, PreviewKind.Other -> PreviewState.Unsupported("No inline preview.")
                }
            }.getOrElse { PreviewState.Unsupported("Preview failed: ${it.message ?: "parse error"}") }
        }
      } catch (t: Throwable) {
        // A preview must NEVER take the app down — worst case we show the
        // fallback row with Open-externally fallback. (Compose-launched coroutines are
        // uncaught here = process kill.)
        DiagLog.e("PREVIEW", "load crashed for ${'$'}{message.attachmentName}: ${'$'}t")
        state = PreviewState.Unsupported("Preview unavailable: ${'$'}{t.message ?: t.javaClass.simpleName}")
      }
    }

    BackHandler { onDismiss() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenExternal, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Open externally",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { shareToApp(context, title, state) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            when (val s = state) {
                PreviewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is PreviewState.Text -> TextBody(s.body, s.monospace)
                is PreviewState.Markdown -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MarkdownText(s.body, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp))
                }
                is PreviewState.Html -> HtmlBody(s.body)
                is PreviewState.Table -> TableBody(s.header, s.rows, onDismiss)
                is PreviewState.Sheets -> SheetsBody(s.sheets, onDismiss)
                is PreviewState.PdfPages -> PdfBody(s)
                is PreviewState.Unsupported -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.reason, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onOpenExternal) { Text("Open externally") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextBody(body: String, monospace: Boolean) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(body, style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HtmlBody(html: String) {
    // JS allowed for self-contained pages; file:// / http navigation blocked.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?,
                        request: android.webkit.WebResourceRequest?): Boolean = true
                }
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    )
}

@Composable
private fun TableBody(header: List<String>, rows: List<List<String>>, fallbackOpen: () -> Unit) {
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            header.forEach { cell ->
                Text(cell, modifier = Modifier.weight(1f), maxLines = 2,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows.size) { i ->
                val row = rows[i]
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        header.indices.forEach { c ->
                            Text(row.getOrElse(c) { "" }, modifier = Modifier.weight(1f),
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SheetsBody(sheets: List<Sheet>, fallbackOpen: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (sheets.size > 1) {
            TabRow(selectedTabIndex = tab.coerceAtMost(sheets.size - 1)) {
                sheets.forEachIndexed { i, s ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(s.name, maxLines = 1, fontSize = 12.sp) })
                }
            }
        }
        val sheet = sheets.getOrNull(tab) ?: return@Column
        TableBody(sheet.rows.firstOrNull() ?: emptyList(), sheet.rows.drop(1)) {}
    }
}

@Composable
private fun PdfBody(pages: PreviewState.PdfPages) {
    var current by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202020))
            .pointerInput(current) { detectTapGestures { /* page nav below */ } },
            contentAlignment = Alignment.Center) {
            pages.pages.getOrNull(current)?.let { bmp ->
                Image(bitmap = bmp.asImageBitmap(), contentDescription = "Page ${current + 1}",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit)
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (current > 0) current-- }, enabled = current > 0) { Text("Prev") }
            Text("${current + 1} / ${pages.pageCount}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (current < pages.pageCount - 1) current++ },
                enabled = current < pages.pageCount - 1) { Text("Next") }
        }
    }
}

private fun renderPdf(context: Context, bytes: ByteArray): PreviewState {
    // PdfRenderer needs a SEEKABLE fd — write to cache, render, delete.
    val f = java.io.File(context.cacheDir, "preview_${'$'}{System.nanoTime()}.pdf")
    return try {
        f.writeBytes(bytes)
        android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                val max = minOf(count, 12)
                val bitmaps = (0 until max).map { i ->
                    val page = renderer.openPage(i)
                    val bmp = android.graphics.Bitmap.createBitmap(page.width, page.height,
                        android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    if (bmp.width > 1400) android.graphics.Bitmap.createScaledBitmap(
                        bmp, 1400, (1400.0 * bmp.height / bmp.width).toInt(), true).also { bmp.recycle() }
                    else bmp
                }
                PreviewState.PdfPages(bitmaps, count)
            }
        }
    } catch (e: Exception) {
        PreviewState.Unsupported("PDF render failed: ${'$'}{e.message}")
    } finally {
        runCatching { f.delete() }
    }
}

private fun shareToApp(context: Context, name: String, state: PreviewState) {
    val text = when (state) {
        is PreviewState.Text -> state.body
        is PreviewState.Markdown -> state.body
        else -> null
    } ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, text.take(200_000))
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}
