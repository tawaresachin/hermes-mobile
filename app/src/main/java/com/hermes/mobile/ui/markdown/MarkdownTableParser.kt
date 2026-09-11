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
 * - unicode box-drawing (│ ─ ┼, the Hermes CLI's rich tables) is normalized
 *   to ASCII pipes first, then follows the same rules; border/rule lines
 *   (┌──┬──┐, +---+, └──┴──┘) are dropped, never rendered as cells.
 * - HTML: lines from `<table…>` through `</table>` (case-insensitive,
 *   any tags inside are stripped, `<br>` becomes a space, the five common
 *   entities are decoded).
 * - Tables inside ``` code fences stay literal example text — EXCEPT when a
 *   fence's whole content is table material (models habitually fence real
 *   tables); such fences are lifted and their markers consumed.
 */
data class TableParse(val tables: List<List<List<String>>>, val prose: String)

private val SEPARATOR_RE = Regex("""^\s*[|+]?[\s:]*-[\s:|+-]*[|+]?\s*$""")

/**
 * Unicode box-drawing set (U+2500–U+257F, plus rounded corners) mapped to
 * ASCII: horizontal-only strokes → '-', everything with a vertical stroke
 * or any corner/junction → '|'. Hermes/CLI `rich` tables use │ ─ ┼, and the
 * mobile chat renders those verbatim unless normalized first — the shape
 * that triggered the "still broken on v0.0.26" report.
 */
private const val BOX_H = "─═"
private const val BOX_V = "│├┤┼┬┴┌┐└┘║╠╣╬╦╩╔╗╚╝╭╮╰╯"

private fun debox(line: String): String {
    if (!line.any { it in BOX_H || it in BOX_V }) return line
    return buildString(line.length) {
        for (ch in line) append(
            when {
                ch in BOX_H -> '-'
                ch in BOX_V -> '|'
                else -> ch
            }
        )
    }
}
private val HTML_TABLE_RE = Regex("""<table\b.*?</table>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_ROW_RE = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_CELL_RE = Regex("""<t[hd]\b[^>]*>(.*?)</t[hd]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_TAG_RE = Regex("""<[^>]+>""")

private fun isSeparator(s: String): Boolean {
    val t = s.trim()
    return t.contains('-') && (t.contains('|') || t.contains('+')) &&
        SEPARATOR_RE.matches(t) &&
        !t.replace("[-|:\\s+]".toRegex(), "").any()
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
    // block = header, separator, data rows; drop stray separator/border rules
    // that sit INSIDE the body (rich/CLI tables put +----+ between every row).
    if (block.size < 2) return null
    val header = cellsOf(block[0])
    val data = block.drop(2).filterNot { isSeparator(it) || isRuleLine(it) }.map { cellsOf(it) }
    val colCount = (listOf(header) + data).maxOf { it.size }
    if (colCount < 1) return null
    return (listOf(header) + data).map { r ->
        if (r.size >= colCount) r.take(colCount) else r + List(colCount - r.size) { "" }
    }
}

/** Parse a block that starts header, separator, data rows (fence-stripped inner).
 * Border rules at the block edges (rich box tables wrap in ┌──┐/└──┘) are
 * dropped first; interior rules are dropped by normalizePipeBlock. */
private fun parseTableBlock(rawBlock: List<String>): List<List<String>>? {
    val block = dropRuleEdges(rawBlock.filter { it.isNotBlank() })
    // locate the separator to find the header
    val sepIdx = block.indexOfFirst { isSeparator(it) }
    if (sepIdx < 1) return null
    return normalizePipeBlock(block.subList(sepIdx - 1, block.size))
}

/**
 * A line that is ONLY dashes/pluses/pipes with no cell text at all — e.g. a
 * CLI rule "+-----+-----+" or a box-table border "└──┴──┘" (deboxed:
 * "|--|--|"). Strict: needs a junction (+) or 2+ vertical strokes, so a
 * prose line with one dash-cell ("|-|") is never consumed.
 */
private fun isRuleLine(s: String): Boolean {
    val t = s.trim()
    if (t.count { it == '-' } < 3) return false   // real rules are long dashes
    if (!t.all { it == '-' || it == '|' || it == '+' || it == ':' || it == ' ' }) return false
    return t.count { it == '|' } >= 2 || t.contains('+')
}

private fun isSeparatorOutsideTable(s: String) = isRuleLine(s)

private fun dropRuleEdges(block: List<String>): List<String> {
    var a = 0; var b = block.size
    while (a < b && block[a].isNotBlank() && isRuleLine(block[a])) a++
    while (b > a && block[b - 1].isNotBlank() && isRuleLine(block[b - 1])) b--
    return block.subList(a, b)
}

fun parseTables(text: String): TableParse? {
    // `orig` feeds prose (chars must stay literal); `lines` is a 1:1 char
    // normalization of Unicode box-drawing (rich/CLI tables: │ ─ ┼) to ASCII
    // pipes so the same detector handles every table flavor. Line count and
    // per-line lengths are identical either way.
    val orig = text.split("\n")
    val lines = orig.map { debox(it) }
    val fenced = fenceFlags(lines)

    data class Span(val start: Int, val end: Int, val rows: List<List<String>>)  // end EXCLUSIVE
    val spans = mutableListOf<Span>()

    // Fence exemption: models habitually wrap tables in ``` fences. When a
    // fence's ENTIRE content is table material (every non-blank line carries
    // a column delimiter and the block has header+separator shape), the fence
    // is formatting noise, not code — lift the table and consume the fence
    // lines too. Mixed code+tables or prose stay literal.
    var fi = 0
    while (fi < lines.size) {
        if (lines[fi].trim().startsWith("```")) {
            var close = fi + 1
            while (close < lines.size && !lines[close].trim().startsWith("```")) close++
            // (unterminated fences still render; skip lifting there)
            if (close >= lines.size) { fi++; continue }
            if (close > fi + 1) {
                val inner = lines.subList(fi + 1, close)
                val nonBlank = inner.filter { it.isNotBlank() }
                val tableish = nonBlank.size >= 2 &&
                    nonBlank.all { it.contains('|') || isRuleLine(it) } &&
                    parseTableBlock(inner) != null
                if (tableish) {
                    val rows = parseTableBlock(inner)
                    if (rows != null) {
                        spans.add(Span(fi, close + 1, rows))
                        fi = close + 1
                        continue
                    }
                }
            }
        }
        fi++
    }

    // HTML tables: a block can span lines, so scan by character offset and
    // map back to line indices. Uses the ORIGINAL text (box chars never
    // appear in HTML tables; regex offsets line up with `lines`).
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
        for (m in HTML_TABLE_RE.findAll(text)) {
            val startLine = lineAt(m.range.first)
            val endLine = lineAt(m.range.last)
            if (spans.any { startLine < it.end && endLine + 1 > it.start }) continue  // fence took it
            if ((startLine..endLine).any { fenced[it] }) continue
            val rows = parseHtmlTable(m.value) ?: continue
            spans.add(Span(startLine, endLine + 1, rows))
        }
    }

    // Markdown pipe tables (incl. box-drawing rows normalized above),
    // skipping anything already claimed.
    var i = 0
    while (i < lines.lastIndex) {
        if (spans.any { i in it.start until it.end }) { i++; continue }
        val isHeader = !fenced[i] && lines[i].contains('|') && !isSeparator(lines[i]) &&
            isSeparator(lines[i + 1]) && lines[i].count { it == '|' } >= 1
        if (isHeader) {
            var end = i + 2
            // Extend across data rows, box borders, and interior rules
            // ("+----+----+" has no pipes but is part of the same table).
            while (end < lines.size && lines[end].isNotBlank() &&
                (lines[end].contains('|') || isRuleLine(lines[end]))) end++
            val rows = normalizePipeBlock(lines.subList(i, end))
            if (rows != null) {
                spans.add(Span(i, end, rows))
                i = end
                continue
            }
        }
        i++
    }

    // Leftover CLI rules (a "+----+----+" line with no markdown header above
    // it, e.g. a box table fenced out or preceded by another table): consume
    // them so raw ─│─ noise never rides in the prose bubble.
    val ruleOnly = BooleanArray(lines.size)
    for (idx in lines.indices) {
        if (!fenced[idx] && spans.none { idx in it.start until it.end } &&
            lines[idx].contains('-') && isSeparatorOutsideTable(lines[idx])) ruleOnly[idx] = true
    }

    if (spans.isEmpty() && !ruleOnly.any { it }) return null

    spans.sortBy { it.start }
    val consumed = BooleanArray(lines.size)
    spans.forEach { s -> (s.start until s.end).forEach { consumed[it] = true } }
    (ruleOnly.indices).forEach { if (ruleOnly[it]) consumed[it] = true }
    val prose = orig.withIndex()
        .filter { (idx, _) -> !consumed[idx] }
        .joinToString("\n") { it.value }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return TableParse(spans.map { it.rows }, prose)
}
