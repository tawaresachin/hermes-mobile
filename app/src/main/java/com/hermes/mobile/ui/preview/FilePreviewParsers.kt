package com.hermes.mobile.ui.preview

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Pure (Android-free) parsers backing the in-chat file preview sheet.
 * Everything here runs in JVM unit tests: SAX + java.util.zip + org.json
 * only — APIs Android and the JVM share (no StAX: absent on Android).
 */

/** Which renderer a tap on a file card should open. Keep in sync with
 * FilePreviewSheet's `when`. Anything not listed → external hand-off. */
enum class PreviewKind { Markdown, Html, Pdf, Csv, Json, Code, Spreadsheet, Word, Slides, Image, Other }

fun previewKindFor(name: String, mime: String): PreviewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    val m = mime.lowercase()
    return when {
        m.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> PreviewKind.Image
        ext == "md" || ext == "markdown" || m == "text/markdown" -> PreviewKind.Markdown
        ext == "html" || ext == "htm" || m == "text/html" -> PreviewKind.Html
        ext == "pdf" || m == "application/pdf" -> PreviewKind.Pdf
        ext == "csv" || ext == "tsv" || m == "text/csv" -> PreviewKind.Csv
        ext == "json" || m == "application/json" -> PreviewKind.Json
        ext == "xlsx" || ext == "xls" || m.contains("spreadsheet") -> PreviewKind.Spreadsheet
        ext == "docx" || ext == "doc" || m.contains("word") -> PreviewKind.Word
        ext == "pptx" || ext == "ppt" || m.contains("presentation") -> PreviewKind.Slides
        ext in setOf("txt", "log", "yaml", "yml", "kt", "kts", "py", "js", "ts", "java", "c", "cpp",
            "h", "sh", "bash", "zsh", "rs", "go", "rb", "php", "sql", "toml", "ini", "cfg", "conf",
            "xml", "gradle", "properties", "env", "jsonc", "diff", "patch", "css", "scss") ||
            m.startsWith("text/") -> PreviewKind.Code
        else -> PreviewKind.Other
    }
}

/** RFC-4180-ish CSV/TSV: quotes, escaped quotes, embedded delimiters and
 * newlines. Delimiter comes from the caller (.tsv → tab). */
fun parseCsv(text: String, delimiter: Char = ','): List<List<String>> {
    if (text.isBlank()) return emptyList()
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    fun endCell() { row.add(cell.toString()); cell.setLength(0) }
    fun endRow() { endCell(); rows.add(row.toList()); row = mutableListOf() }
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = false
                else -> cell.append(c)
            }
            c == '"' -> inQuotes = true
            c == delimiter -> endCell()
            c == '\r' -> { /* handled with the following \n */ }
            c == '\n' -> endRow()
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows.filter { r -> r.any { it.isNotBlank() } }
}

/** Pretty-print JSON with 2-space indent. Returns original text when
 * unparseable (a preview must never blank out on malformed input). */
fun prettyJson(text: String): String = try {
    val sb = StringBuilder()
    appendJson(sb, org.json.JSONObject(text), 0)
    sb.toString()
} catch (_: Exception) {
    try {
        val sb = StringBuilder()
        appendJson(sb, org.json.JSONArray(text), 0)
        sb.toString()
    } catch (_: Exception) { text }
}

private fun appendJson(sb: StringBuilder, v: Any, depth: Int) {
    val pad = "  ".repeat(depth + 1)
    when (v) {
        is org.json.JSONObject -> {
            if (v.length() == 0) { sb.append("{}"); return }
            sb.append("{\n")
            val keys = ArrayList<String>().apply { val it = v.keys(); while (it.hasNext()) add(it.next()) }
            keys.sort()
            keys.forEachIndexed { idx, k ->
                sb.append(pad).append("\"").append(k).append("\": ")
                appendJson(sb, v.opt(k) ?: org.json.JSONObject.NULL, depth + 1)
                if (idx < keys.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ".repeat(depth)).append("}")
        }
        is org.json.JSONArray -> {
            if (v.length() == 0) { sb.append("[]"); return }
            sb.append("[\n")
            for (i in 0 until v.length()) {
                sb.append(pad)
                appendJson(sb, v.opt(i) ?: org.json.JSONObject.NULL, depth + 1)
                if (i < v.length() - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ".repeat(depth)).append("]")
        }
        is String -> sb.append("\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n")).append("\"")
        else -> sb.append(v.toString())
    }
}

// ── Office XML (OOXML) readers — ZipInputStream + SAX ──────────────────────

private val saxFactory: SAXParserFactory = SAXParserFactory.newInstance().apply {
    isNamespaceAware = false
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
}

private fun sax(xml: ByteArray, handler: DefaultHandler) =
    saxFactory.newSAXParser().parse(ByteArrayInputStream(xml), handler)

/** Attribute lookup by LOCAL name — with namespaces off the parser keeps
 * prefixes (w:val, r:id), so getValue("val") misses them. */
private fun Attributes.local(name: String): String? {
    for (i in 0 until length) {
        val q = getQName(i)
        if (q == name || q.endsWith(":$name")) return getValue(i)
    }
    return null
}

data class Sheet(val name: String, val rows: List<List<String>>)

private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = HashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            out[e.name] = z.readBytes()
        }
    }
    return out
}

/** xlsx: workbook sheet names + shared strings + per-sheet grids. */
fun parseXlsx(bytes: ByteArray): List<Sheet> {
    val entries = unzip(bytes)
    val shared = entries["xl/sharedStrings.xml"]?.let { x ->
        object : DefaultHandler() {
            val list = mutableListOf<String>()
            private var cur: StringBuilder? = null
            private var inT = false
            override fun startElement(u: String, l: String, q: String, a: Attributes) {
                when (q.substringAfter(':')) {
                    "si" -> cur = StringBuilder()
                    "t" -> inT = true
                }
            }
            override fun characters(ch: CharArray, s: Int, e: Int) { if (inT) cur?.append(ch, s, e) }
            override fun endElement(u: String, l: String, q: String) {
                when (q.substringAfter(':')) {
                    "t" -> inT = false
                    "si" -> { list += cur?.toString() ?: ""; cur = null }
                }
            }
        }.also { sax(x, it) }.list
    } ?: emptyList()
    val names = entries["xl/workbook.xml"]?.let { x ->
        object : DefaultHandler() {
            val list = mutableListOf<String>()
            override fun startElement(u: String, l: String, q: String, a: Attributes) {
                if (q.substringAfter(':') == "sheet")
                    list += a.local("name") ?: "Sheet ${'$'}{list.size + 1}"
            }
        }.also { sax(x, it) }.list
    } ?: emptyList()
    val xmlPaths = entries.keys
        .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sortedBy { Regex("""sheet(\d+)\.xml""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
    val sheets = names.ifEmpty { List(xmlPaths.size) { "Sheet ${it + 1}" } }
    return sheets.mapIndexed { idx, name ->
        val path = xmlPaths.getOrNull(idx) ?: return@mapIndexed Sheet(name, emptyList())
        Sheet(name, parseSheetGrid(entries.getValue(path), shared))
    }
}

private fun parseSheetGrid(xml: ByteArray, shared: List<String>): List<List<String>> =
    object : DefaultHandler() {
        val rows = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private var cellType = ""
        private var cellRef = ""
        private val value = StringBuilder()
        private var inValue = false
        override fun startElement(u: String, l: String, q: String, a: Attributes) {
            when (q.substringAfter(':')) {
                "row" -> row = mutableListOf()
                "c" -> { cellType = a.local("t") ?: ""; cellRef = a.local("r") ?: ""; value.setLength(0) }
                "v", "t" -> inValue = true
            }
        }
        override fun characters(ch: CharArray, s: Int, e: Int) { if (inValue) value.append(ch, s, e) }
        override fun endElement(u: String, l: String, q: String) {
            when (q.substringAfter(':')) {
                "v", "t" -> inValue = false
                "c" -> {
                    val text = when {
                        cellType == "s" -> value.toString().toIntOrNull()
                            ?.let { shared.getOrElse(it) { "" } } ?: ""
                        else -> value.toString()
                    }
                    val col = colIndex(cellRef)
                    while (col >= 0 && row.size < col) row += ""
                    row += text
                }
                "row" -> if (row.any { it.isNotBlank() }) rows += row
            }
        }
    }.also { sax(xml, it) }.rows

private fun colIndex(ref: String): Int {
    var n = 0; var found = false
    for (c in ref) {
        if (c in 'A'..'Z' || c in 'a'..'z') { n = n * 26 + (c.uppercaseChar() - 'A' + 1); found = true }
        else break
    }
    return if (found) n - 1 else -1
}

/** docx → plain paragraphs (headings tagged "# " so the previewer bolds them). */
fun parseDocx(bytes: ByteArray): String {
    val doc = unzip(bytes)["word/document.xml"] ?: return ""
    return object : DefaultHandler() {
        val sb = StringBuilder()
        private val para = StringBuilder()
        private var style = ""
        private var inT = false
        override fun startElement(u: String, l: String, q: String, a: Attributes) {
            when (q.substringAfter(':')) {
                "p" -> { para.setLength(0); style = "" }
                "pStyle" -> style = a.local("val") ?: ""
                "t" -> inT = true
            }
        }
        override fun characters(ch: CharArray, s: Int, e: Int) { if (inT) para.append(ch, s, e) }
        override fun endElement(u: String, l: String, q: String) {
            when (q.substringAfter(':')) {
                "t" -> inT = false
                "p" -> if (para.isNotBlank()) {
                    if (style.contains("Heading", true) || style.contains("Title", true)) sb.append("# ")
                    sb.append(para.toString().trim()).append('\n').append('\n')
                }
            }
        }
    }.also { sax(doc, it) }.sb.toString().trim()
}

/** pptx → "— Slide n —" blocks with all text frames per slide. */
fun parsePptx(bytes: ByteArray): String {
    val slides = unzip(bytes).filter { Regex("""ppt/slides/slide\d+\.xml""").matches(it.key) }
    if (slides.isEmpty()) return ""
    val ordered = slides.entries.sortedBy { Regex("""slide(\d+)""").find(it.key)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    val sb = StringBuilder()
    ordered.forEachIndexed { idx, (name, xml) ->
        sb.append("— Slide ${idx + 1} (${name.substringAfterLast('/')}) —\n")
        object : DefaultHandler() {
            private val cur = StringBuilder()
            private var inT = false
            override fun startElement(u: String, l: String, q: String, a: Attributes) {
                if (q.substringAfter(':') == "t") { inT = true; cur.setLength(0) }
            }
            override fun characters(ch: CharArray, s: Int, e: Int) { if (inT) cur.append(ch, s, e) }
            override fun endElement(u: String, l: String, q: String) {
                if (q.substringAfter(':') == "t") {
                    inT = false
                    if (cur.isNotBlank()) sb.append(cur).append('\n')
                }
            }
        }.also { sax(xml, it) }
        sb.append('\n')
    }
    return sb.toString().trim()
}
