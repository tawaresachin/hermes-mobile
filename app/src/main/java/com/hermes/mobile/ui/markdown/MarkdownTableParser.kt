package com.hermes.mobile.ui.markdown

/**
 * Table detection for chat responses — markdown pipe tables AND HTML
 * `<table>` blocks (models emit both; agents converting docs often answer
 * in HTML). Extracted from ChatScreen.kt and JVM-unit-tested.
 *
 * [parseTables] finds EVERY table in document order. Each table is a grid
 * (header first, separator dropped, ragged rows padded); `prose` is every
 * line outside any table block. The bubble renders the prose with
 * MarkdownText; the full-width overlay renders all tables.
 *
 * Detection rules:
 * - markdown: a pipe header line IMMEDIATELY followed by a `|---|` style
 *   separator (strict GFM — stray "A|B" prose never matches).
 * - HTML: lines from `<table…>` through `</table>` (case-insensitive,
 *   any tags inside are stripped, `<br>` becomes a space, the five common
 *   entities are decoded).
 * - Both forms inside ``` code fences stay literal example text.
 */
data class TableParse(val tables: List<List<List<String>>>, val prose: String)

private val SEPARATOR_RE = Regex("""^\s*\|?[\s:]*-[\s:|-]*\|?\s*$""")
private val HTML_TABLE_RE = Regex("""<table\b.*?</table>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_ROW_RE = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_CELL_RE = Regex("""<t[hd]\b[^>]*>(.*?)</t[hd]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_TAG_RE = Regex("""<[^>]+>""")

private fun isSeparator(s: String): Boolean {
    val t = s.trim()
    return t.contains('-') && t.contains('|') &&
        SEPARATOR_RE.matches(t) &&
        !t.replace("[-|:\\s ]".toRegex(), "").any()
}

private fun cellsOf(s: String): List<String> =
    s.trim().removePrefix("|").removeSuffix("|").split("|").map { raw ->
        // Cells render as plain Text — strip inline **bold**, `code`,
        // *italic* instead of showing literal markers.
        var cell = raw.trim()
        cell = cell.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""^\*([^*]+)\*$"""), "$1")
            .trim()
        cell
    }

private fun htmlCellToText(inner: String): String =
    inner.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
        .let { HTML_TAG_RE.replace(it, "") }
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .trim()

private fun parseHtmlTable(block: String): List<List<String>>? {
    val rows = HTML_ROW_RE.findAll(block).map { m ->
        HTML_CELL_RE.findAll(m.groupValues[1]).map { c -> htmlCellToText(c.groupValues[1]) }.toList()
    }.toList()
    if (rows.size < 2) return null      // need header + >=1 row, like markdown
    val colCount = rows.maxOf { it.size }
    if (colCount < 1) return null
    return rows.map { r -> if (r.size >= colCount) r.take(colCount) else r + List(colCount - r.size) { "" } }
}

/** True when line index i sits inside an open ``` fence. */
private fun fenceFlags(lines: List<String>): BooleanArray {
    val inside = BooleanArray(lines.size)
    var open = false
    for (i in lines.indices) {
        if (lines[i].trim().startsWith("```")) { inside[i] = open; open = !open; continue }
        inside[i] = open
    }
    return inside
}

private fun normalizePipeBlock(block: List<String>): List<List<String>>? {
    if (block.size < 2) return null
    val header = cellsOf(block[0])
    val data = block.drop(2).map { cellsOf(it) }
    val colCount = (listOf(header) + data).maxOf { it.size }
    if (colCount < 1) return null
    return (listOf(header) + data).map { r ->
        if (r.size >= colCount) r.take(colCount) else r + List(colCount - r.size) { "" }
    }
}

fun parseTables(text: String): TableParse? {
    val lines = text.split("\n")
    val fenced = fenceFlags(lines)

    data class Span(val start: Int, val end: Int, val rows: List<List<String>>)  // end EXCLUSIVE
    val spans = mutableListOf<Span>()

    // HTML tables: a block can span lines, so scan by character offset and
    // map back to line indices.
    run {
        var offset = 0
        val lineStarts = IntArray(lines.size + 1)
        for (i in lines.indices) { lineStarts[i] = offset; offset += lines[i].length + 1 }
        lineStarts[lines.size] = offset
        fun lineAt(pos: Int): Int {
            var lo = 0; var hi = lines.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (lineStarts[mid] <= pos) lo = mid else hi = mid - 1 }
            return lo
        }
        val joined = text
        for (m in HTML_TABLE_RE.findAll(joined)) {
            val startLine = lineAt(m.range.first)
            val endLine = lineAt(m.range.last)
            if ((startLine..endLine).any { fenced[it] }) continue
            val rows = parseHtmlTable(m.value) ?: continue
            spans.add(Span(startLine, endLine + 1, rows))
        }
    }

    // Markdown pipe tables, skipping anything inside an HTML span.
    var i = 0
    while (i < lines.lastIndex) {
        if (spans.any { i in it.start until it.end }) { i++; continue }
        val isHeader = !fenced[i] && lines[i].contains('|') && !isSeparator(lines[i]) &&
            isSeparator(lines[i + 1]) && lines[i].count { it == '|' } >= 1
        if (isHeader) {
            var end = i + 2
            while (end < lines.size && lines[end].contains('|') && lines[end].isNotBlank()) end++
            val rows = normalizePipeBlock(lines.subList(i, end))
            if (rows != null) {
                spans.add(Span(i, end, rows))
                i = end
                continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return null

    spans.sortBy { it.start }
    val consumed = BooleanArray(lines.size)
    spans.forEach { s -> (s.start until s.end).forEach { consumed[it] = true } }
    val prose = lines.withIndex()
        .filter { (idx, _) -> !consumed[idx] }
        .joinToString("\n") { it.value }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return TableParse(spans.map { it.rows }, prose)
}
