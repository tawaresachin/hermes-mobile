package com.hermes.mobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests pinning the table-parser fixes (columns, prose, false positives). */
class MarkdownTableParserTest {

    @Test fun `null when there is no table`() {
        assertNull(parseMarkdownTables("just prose with A|B pipes and no separator"))
        assertNull(parseMarkdownTables(""))
    }

    @Test fun `simple table parses rows with header`() {
        val md = """
            | Name | Qty |
            | ---- | --- |
            | Foo  | 2   |
            | Bar  | 7   |
        """.trimIndent()
        val t = parseMarkdownTables(md)
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(listOf("Name", "Qty"), rows[0])
        assertEquals(listOf("Foo", "2"), rows[1])
        assertEquals(listOf("Bar", "7"), rows[2])
        assertEquals(3, rows.size)  // separator must NOT become a row
        assertEquals("", t.prose)
    }

    @Test fun `empty middle cell keeps its column position`() {
        val t = parseMarkdownTables("| A | B | C |\n|---|---|---|\n| x |   | z |")
        assertEquals(listOf("A", "B", "C"), t!!.tables[0][0])
        assertEquals(listOf("x", "", "z"), t.tables[0][1])  // old parser dropped the empty cell
    }

    @Test fun `ragged rows are padded to equal columns`() {
        val t = parseMarkdownTables("| H1 | H2 | H3 |\n|---|---|---|\n| a | b |\n| c | d | e |")
        val rows = t!!.tables[0]
        assertEquals(3, rows[0].size)
        assertEquals(listOf("a", "b", ""), rows[1])
        assertEquals(listOf("c", "d", "e"), rows[2])
    }

    @Test fun `surrounding prose is preserved separately`() {
        val md = "Here's the comparison:\n\n| Name | Qty |\n| ---- | --- |\n| Foo | 2 |\n\nOverall Foo wins."
        val t = parseMarkdownTables(md)
        assertNotNull(t)
        assertEquals(2, t!!.tables[0].size)
        assertEquals("Here's the comparison:\n\nOverall Foo wins.", t.prose)
    }

    @Test fun `two tables both parse, prose interleaved stays with the bubble`() {
        val md = "Before:\n| A | B |\n|---|---|\n| 1 | 2 |\nMiddle note\n| C | D |\n|---|---|\n| 3 | 4 |\nAfter."
        val t = parseMarkdownTables(md)
        assertNotNull(t)
        assertEquals(2, t!!.tables.size)
        assertEquals(listOf("A", "B"), t.tables[0][0])
        assertEquals(listOf("C", "D"), t.tables[1][0])
        assertEquals(listOf("3", "4"), t.tables[1][1])
        assertEquals("Before:\nMiddle note\nAfter.", t.prose)
    }

    @Test fun `colon alignment markers in separator are fine`() {
        val t = parseMarkdownTables("| L | R |\n|:--|--:|\n| a | b |")
        assertNotNull(t)
        assertEquals(listOf("a", "b"), t!!.tables[0][1])
    }

    @Test fun `single pipe line without separator is not a table`() {
        assertNull(parseMarkdownTables("use A|B notation here\nmore prose"))
    }

    @Test fun `header-only table (no data rows) is still a table`() {
        val t = parseMarkdownTables("| A | B |\n|---|---|")
        assertNotNull(t)
        assertEquals(1, t!!.tables[0].size)
    }
}
