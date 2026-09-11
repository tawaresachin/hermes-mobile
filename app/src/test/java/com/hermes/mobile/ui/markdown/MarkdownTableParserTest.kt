package com.hermes.mobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests pinning the table-parser fixes (columns, prose, false positives). */
class MarkdownTableParserTest {

    @Test fun `null when there is no table`() {
        assertNull(parseMarkdownTable("just prose with A|B pipes and no separator"))
        assertNull(parseMarkdownTable(""))
    }

    @Test fun `simple table parses rows with header`() {
        val md = """
            | Name | Qty |
            | ---- | --- |
            | Foo  | 2   |
            | Bar  | 7   |
        """.trimIndent()
        val t = parseMarkdownTable(md)
        assertNotNull(t)
        assertEquals(listOf("Name", "Qty"), t!!.rows[0])
        assertEquals(listOf("Foo", "2"), t.rows[1])
        assertEquals(listOf("Bar", "7"), t.rows[2])
        assertEquals(3, t.rows.size)  // separator must NOT become a row
        assertEquals("", t.prose)
    }

    @Test fun `empty middle cell keeps its column position`() {
        val t = parseMarkdownTable(
            "| A | B | C |\n|---|---|---|\n| x |   | z |"
        )
        assertEquals(listOf("A", "B", "C"), t!!.rows[0])
        assertEquals(listOf("x", "", "z"), t.rows[1])  // the old parser dropped the empty cell
    }

    @Test fun `ragged rows are padded to equal columns`() {
        val t = parseMarkdownTable(
            "| H1 | H2 | H3 |\n|---|---|---|\n| a | b |\n| c | d | e |"
        )
        assertEquals(3, t!!.rows[0].size)
        assertEquals(listOf("a", "b", ""), t.rows[1])
        assertEquals(listOf("c", "d", "e"), t.rows[2])
    }

    @Test fun `surrounding prose is preserved separately`() {
        val md = "Here's the comparison:\n\n| Name | Qty |\n| ---- | --- |\n| Foo | 2 |\n\nOverall Foo wins."
        val t = parseMarkdownTable(md)
        assertNotNull(t)
        assertEquals(2, t!!.rows.size)
        assertEquals("Here's the comparison:\n\nOverall Foo wins.", t.prose)
    }

    @Test fun `colon alignment markers in separator are fine`() {
        val t = parseMarkdownTable("| L | R |\n|:--|--:|\n| a | b |")
        assertNotNull(t)
        assertEquals(listOf("a", "b"), t!!.rows[1])
    }

    @Test fun `single pipe line without separator is not a table`() {
        assertNull(parseMarkdownTable("use A|B notation here\nmore prose"))
    }
}
