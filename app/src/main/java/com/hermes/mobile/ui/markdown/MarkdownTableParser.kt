package com.hermes.mobile.ui.markdown

/**
 * GitHub-flavored markdown table detection, extracted from ChatScreen.kt so
 * it gets real JVM unit tests (it previously shipped three silent rendering
 * bugs: empty-cell column collapse, prose deletion, greedy pipe matching).
 *
 * [parseMarkdownTable] returns (rows, prose): rows includes the header row
 * (separator stripped, all rows padded to equal column counts); prose is
 * everything outside the table block — the bubble renders the prose and the
 * full-width overlay renders the rows. null when no valid table exists.
 */
data class MarkdownTableParse(val rows: List<List<String>>, val prose: String)

private val SEPARATOR_RE = Regex("""^\s*\|?[\s:]*-[\s:|-]*\|?\s*$""")

private fun isSeparator(s: String): Boolean {
    val t = s.trim()
    return t.contains('-') && t.contains('|') &&
        SEPARATOR_RE.matches(t) &&
        !t.replace("[-|:\\s ]".toRegex(), "").any()
}

private fun cellsOf(s: String): List<String> =
    s.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

fun parseMarkdownTable(text: String): MarkdownTableParse? {
    val lines = text.split("\n")

    var start = -1
    for (i in 0 until lines.lastIndex) {
        if (lines[i].contains('|') && !isSeparator(lines[i]) &&
            isSeparator(lines[i + 1]) && lines[i].count { it == '|' } >= 1) {
            start = i; break
        }
    }
    if (start < 0) return null

    var end = start + 2
    while (end < lines.size && lines[end].contains('|') && lines[end].isNotBlank()) end++

    val blockLines = lines.subList(start, end).toSet()
    val header = cellsOf(lines[start])
    val data = (start + 2 until end).map { cellsOf(lines[it]) }
    val colCount = (listOf(header) + data).maxOf { it.size }
    val rows = (listOf(header) + data).map { r ->
        if (r.size >= colCount) r.take(colCount) else r + List(colCount - r.size) { "" }
    }
    if (rows.size < 2) return null

    val prose = lines.filterIndexed { idx, _ -> lines[idx] !in blockLines }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")   // table removal must not double-splice gaps
        .trim()
    return MarkdownTableParse(rows, prose)
}
