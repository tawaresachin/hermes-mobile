package com.hermes.mobile.ui.markdown

/**
 * GitHub-flavored markdown table detection, extracted from ChatScreen.kt so
 * it gets real JVM unit tests (it previously shipped three silent rendering
 * bugs: empty-cell column collapse, prose deletion, greedy pipe matching).
 *
 * [parseMarkdownTables] finds EVERY table in the text (the single-table
 * version left a second table rendering as raw pipes). Returns null when
 * there is no table at all, else (tables, prose) where each table includes
 * its header (separator stripped, rows padded to equal column counts) and
 * prose is every line outside any table block — the bubble renders the
 * prose, the overlay renders all tables in order.
 */
data class MarkdownTableParse(val tables: List<List<List<String>>>, val prose: String)

private val SEPARATOR_RE = Regex("""^\s*\|?[\s:]*-[\s:|-]*\|?\s*$""")

private fun isSeparator(s: String): Boolean {
    val t = s.trim()
    return t.contains('-') && t.contains('|') &&
        SEPARATOR_RE.matches(t) &&
        !t.replace("[-|:\\s ]".toRegex(), "").any()
}

private fun cellsOf(s: String): List<String> =
    s.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun normalizeBlock(block: List<String>): List<List<String>>? {
    // block = header + separator + optional data rows. Header-only tables
    // are valid GitHub-flavored markdown (renders the header band alone).
    if (block.size < 2) return null
    val header = cellsOf(block[0])
    val data = block.drop(2).map { cellsOf(it) }
    val colCount = (listOf(header) + data).maxOf { it.size }
    if (colCount < 1) return null
    return (listOf(header) + data).map { r ->
        if (r.size >= colCount) r.take(colCount) else r + List(colCount - r.size) { "" }
    }
}

fun parseMarkdownTables(text: String): MarkdownTableParse? {
    val lines = text.split("\n")
    val tables = mutableListOf<List<List<String>>>()
    val consumed = mutableSetOf<Int>()
    var i = 0
    while (i < lines.lastIndex) {
        val isHeader = lines[i].contains('|') && !isSeparator(lines[i]) &&
            isSeparator(lines[i + 1]) && lines[i].count { it == '|' } >= 1
        if (isHeader) {
            var end = i + 2
            while (end < lines.size && lines[end].contains('|') && lines[end].isNotBlank()) end++
            val rows = normalizeBlock(lines.subList(i, end))
            if (rows != null) {
                tables.add(rows)
                (i until end).forEach { consumed.add(it) }
                i = end
                continue
            }
        }
        i++
    }
    if (tables.isEmpty()) return null
    val prose = lines.withIndex()
        .filter { (idx, _) -> idx !in consumed }
        .joinToString("\n") { it.value }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return MarkdownTableParse(tables, prose)
}
