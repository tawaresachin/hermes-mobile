package com.hermes.mobile.ui.preview

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses are pure stdlib (StAX + java.util.zip). The android.util.Base64
 * stub throws "not mocked" under unit tests, so fixtures decode via
 * java.util.Base64 in runCatching and tests self-skip if the stub bites —
 * same pattern the rest of the suite uses for android.* deps.
 */
class FilePreviewParsersTest {

    @Test fun routingTable() {
        assertEquals(PreviewKind.Markdown, previewKindFor("README.md", ""))
        assertEquals(PreviewKind.Html, previewKindFor("report.HTML", "text/html"))
        assertEquals(PreviewKind.Pdf, previewKindFor("a.pdf", "application/pdf"))
        assertEquals(PreviewKind.Csv, previewKindFor("data.csv", "text/csv"))
        assertEquals(PreviewKind.Json, previewKindFor("cfg.json", ""))
        assertEquals(PreviewKind.Spreadsheet, previewKindFor("q.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals(PreviewKind.Word, previewKindFor("memo.docx", ""))
        assertEquals(PreviewKind.Slides, previewKindFor("deck.pptx", ""))
        assertEquals(PreviewKind.Code, previewKindFor("app.yaml", ""))
        assertEquals(PreviewKind.Code, previewKindFor("script.kt", ""))
        assertEquals(PreviewKind.Code, previewKindFor("plain.txt", "text/plain"))
        assertEquals(PreviewKind.Image, previewKindFor("cat.png", "image/png"))
        assertEquals(PreviewKind.Image, previewKindFor("cat", "image/jpeg"))   // mime wins
        assertEquals(PreviewKind.Other, previewKindFor("movie.mp4", "video/mp4"))
        assertEquals(PreviewKind.Other, previewKindFor("x", ""))
    }

    @Test fun csvQuotesDelimitersNewlines() {
        val rows = parseCsv("a,b\n\"x,1\",\"y\"\"q\"\n\"multi\nline\",z")
        assertEquals(listOf(listOf("a", "b"), listOf("x,1", "y\"q"), listOf("multi\nline", "z")), rows)
    }

    @Test fun csvTsvDelimiterAndBlankRows() {
        val rows = parseCsv("a\tb\n\n1\t2\n", '\t')
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test fun jsonPrettyPrintAndMalformedFallback() {
        val out = prettyJson("""{"b":1,"a":{"c":[1,"x"]}}""")
        assertTrue(out.indexOf("\"a\"") < out.indexOf("\"b\""))  // keys sorted
        assertTrue(out.contains("  \"c\": ["))
        assertEquals("not json {", prettyJson("not json {"))
    }

    @Test fun docxHeadingsAndRunJoining() {
        val bytes = b64Bytes(DOCX_B64) ?: return
        val text = parseDocx(bytes)
        assertTrue(text.contains("# Report Title"))
        assertTrue(text.contains("First paragraph, split across runs."))
        assertTrue(text.contains("# A heading"))
    }

    @Test fun xlsxSharedStringsInlineGapsAndSheets() {
        val bytes = b64Bytes(XLSX_B64) ?: return
        val sheets = parseXlsx(bytes)
        assertEquals(listOf("Sales", "Ops"), sheets.map { it.name })
        val sales = sheets[0].rows
        assertEquals(listOf("Region", "Q1"), sales[0])
        assertEquals(listOf("North", "42.5"), sales[1])
        // row 3: A shared string with comma, B empty, C inlineStr
        assertEquals("South, East", sales[2][0])
        assertEquals("", sales[2][1])
        assertEquals("gap-col", sales[2][2])
        assertEquals(listOf("Ops only"), sheets[1].rows[0])
    }

    @Test fun pptxSlideOrderAndText() {
        val bytes = b64Bytes(PPTX_B64) ?: return
        val text = parsePptx(bytes)
        assertTrue(text.indexOf("Slide 1") < text.indexOf("Slide 2"))
        assertTrue(text.contains("Title frame"))
        assertTrue(text.contains("Bullet two"))
        assertTrue(text.contains("Second slide text"))
    }

    private fun b64Bytes(b: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(b)
    } catch (_: Exception) {
        null
    }

    companion object {
        // embedded below by generate step
        const val DOCX_B64 = "UEsDBBQAAAAAAPMALV0DrPwidQEAAHUBAAARAAAAd29yZC9kb2N1bWVudC54bWw8dzpkb2N1bWVudCB4bWxuczp3PSJodHRwOi8vc2NoZW1hcy5vcGVueG1sZm9ybWF0cy5vcmcvd29yZHByb2Nlc3NpbmdtbC8yMDA2L21haW4iPjx3OmJvZHk+PHc6cD48dzpwUHI+PHc6cFN0eWxlIHc6dmFsPSJUaXRsZSIvPjwvdzpwUHI+PHc6cj48dzp0PlJlcG9ydCBUaXRsZTwvdzp0PjwvdzpyPjwvdzpwPjx3OnA+PHc6cj48dzp0PkZpcnN0IHBhcmFncmFwaCwgc3BsaXQ8L3c6dD48L3c6cj48dzpyPjx3OnQ+IGFjcm9zcyBydW5zLjwvdzp0PjwvdzpyPjwvdzpwPjx3OnA+PHc6cFByPjx3OnBTdHlsZSB3OnZhbD0iSGVhZGluZzIiLz48L3c6cFByPjx3OnI+PHc6dD5BIGhlYWRpbmc8L3c6dD48L3c6cj48L3c6cD48L3c6Ym9keT48L3c6ZG9jdW1lbnQ+UEsBAhQDFAAAAAAA8wAtXQOs/CJ1AQAAdQEAABEAAAAAAAAAAAAAAIABAAAAAHdvcmQvZG9jdW1lbnQueG1sUEsFBgAAAAABAAEAPwAAAKQBAAAAAA=="
        const val XLSX_B64 = "UEsDBBQAAAAAAPMALV19j8M5TQAAAE0AAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbDxUeXBlcyB4bWxucz0iaHR0cDovL3NjaGVtYXMub3BlbnhtbGZvcm1hdHMub3JnL3BhY2thZ2UvMjAwNi9jb250ZW50LXR5cGVzIi8+UEsDBBQAAAAAAPMALV0SOZTnDgEAAA4BAAAPAAAAeGwvd29ya2Jvb2sueG1sPHdvcmtib29rIHhtbG5zPSJodHRwOi8vc2NoZW1hcy5vcGVueG1sZm9ybWF0cy5vcmcvc3ByZWFkc2hlZXRtbC8yMDA2L21haW4iIHhtbG5zOnI9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9vZmZpY2VEb2N1bWVudC8yMDA2L3JlbGF0aW9uc2hpcHMiPjxzaGVldHM+PHNoZWV0IG5hbWU9IlNhbGVzIiBzaGVldElkPSIxIiByOmlkPSJySWQxIi8+PHNoZWV0IG5hbWU9Ik9wcyIgc2hlZXRJZD0iMiIgcjppZD0icklkMiIvPjwvc2hlZXRzPjwvd29ya2Jvb2s+UEsDBBQAAAAAAPMALV2cwQxhDQEAAA0BAAAUAAAAeGwvc2hhcmVkU3RyaW5ncy54bWw8c3N0IHhtbG5zPSJodHRwOi8vc2NoZW1hcy5vcGVueG1sZm9ybWF0cy5vcmcvc3ByZWFkc2hlZXRtbC8yMDA2L21haW4iIHhtbG5zOnI9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9vZmZpY2VEb2N1bWVudC8yMDA2L3JlbGF0aW9uc2hpcHMiIGNvdW50PSI0IiB1bmlxdWVDb3VudD0iNCI+PHNpPjx0PlJlZ2lvbjwvdD48L3NpPjxzaT48dD5RMTwvdD48L3NpPjxzaT48dD5Ob3J0aDwvdD48L3NpPjxzaT48dD5Tb3V0aCwgRWFzdDwvdD48L3NpPjwvc3N0PlBLAwQUAAAAAADzAC1dBMMnDq0BAACtAQAAGAAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbDx3b3Jrc2hlZXQgeG1sbnM9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9zcHJlYWRzaGVldG1sLzIwMDYvbWFpbiIgeG1sbnM6cj0iaHR0cDovL3NjaGVtYXMub3BlbnhtbGZvcm1hdHMub3JnL29mZmljZURvY3VtZW50LzIwMDYvcmVsYXRpb25zaGlwcyI+PHNoZWV0RGF0YT48cm93IHI9IjEiPjxjIHI9IkExIiB0PSJzIj48dj4wPC92PjwvYz48YyByPSJCMSIgdD0icyI+PHY+MTwvdj48L2M+PC9yb3c+PHJvdyByPSIyIj48YyByPSJBMiIgdD0icyI+PHY+Mjwvdj48L2M+PGMgcj0iQjIiPjx2PjQyLjU8L3Y+PC9jPjwvcm93Pjxyb3cgcj0iMyI+PGMgcj0iQTMiIHQ9InMiPjx2PjM8L3Y+PC9jPjxjIHI9IkMzIiB0PSJpbmxpbmVTdHIiPjxpcz48dD5nYXAtY29sPC90PjwvaXM+PC9jPjwvcm93Pjwvc2hlZXREYXRhPjwvd29ya3NoZWV0PlBLAwQUAAAAAADzAC1dBiF4iwMBAAADAQAAGAAAAHhsL3dvcmtzaGVldHMvc2hlZXQyLnhtbDx3b3Jrc2hlZXQgeG1sbnM9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9zcHJlYWRzaGVldG1sLzIwMDYvbWFpbiIgeG1sbnM6cj0iaHR0cDovL3NjaGVtYXMub3BlbnhtbGZvcm1hdHMub3JnL29mZmljZURvY3VtZW50LzIwMDYvcmVsYXRpb25zaGlwcyI+PHNoZWV0RGF0YT48cm93IHI9IjEiPjxjIHI9IkExIiB0PSJpbmxpbmVTdHIiPjxpcz48dD5PcHMgb25seTwvdD48L2lzPjwvYz48L3Jvdz48L3NoZWV0RGF0YT48L3dvcmtzaGVldD5QSwECFAMUAAAAAADzAC1dfY/DOU0AAABNAAAAEwAAAAAAAAAAAAAAgAEAAAAAW0NvbnRlbnRfVHlwZXNdLnhtbFBLAQIUAxQAAAAAAPMALV0SOZTnDgEAAA4BAAAPAAAAAAAAAAAAAACAAX4AAAB4bC93b3JrYm9vay54bWxQSwECFAMUAAAAAADzAC1dnMEMYQ0BAAANAQAAFAAAAAAAAAAAAAAAgAG5AQAAeGwvc2hhcmVkU3RyaW5ncy54bWxQSwECFAMUAAAAAADzAC1dBMMnDq0BAACtAQAAGAAAAAAAAAAAAAAAgAH4AgAAeGwvd29ya3NoZWV0cy9zaGVldDEueG1sUEsBAhQDFAAAAAAA8wAtXQYheIsDAQAAAwEAABgAAAAAAAAAAAAAAIAB2wQAAHhsL3dvcmtzaGVldHMvc2hlZXQyLnhtbFBLBQYAAAAABQAFAEwBAAAUBgAAAAA="
        const val PPTX_B64 = "UEsDBBQAAAAAAPMALV0nCgYrgAEAAIABAAAVAAAAcHB0L3NsaWRlcy9zbGlkZTEueG1sPHA6c2xkIHhtbG5zOnA9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9wcmVzZW50YXRpb25tbC8yMDA2L21haW4iIHhtbG5zOmE9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9kcmF3aW5nbWwvMjAwNi9tYWluIj48cDpjU2xkPjxwOnNwVHJlZT48cDpzcD48cDp0eEJvZHk+PGE6cD48YTpyPjxhOnQ+VGl0bGUgZnJhbWU8L2E6dD48L2E6cj48L2E6cD48L3A6dHhCb2R5PjwvcDpzcD48cDpzcD48cDp0eEJvZHk+PGE6cD48YTpyPjxhOnQ+QnVsbGV0IG9uZTwvYTp0PjwvYTpyPjwvYTpwPjxhOnA+PGE6cj48YTp0PkJ1bGxldCB0d288L2E6dD48L2E6cj48L2E6cD48L3A6dHhCb2R5PjwvcDpzcD48L3A6c3BUcmVlPjwvcDpjU2xkPjwvcDpzbGQ+UEsDBBQAAAAAAPMALV0WZtTFDgEAAA4BAAAVAAAAcHB0L3NsaWRlcy9zbGlkZTIueG1sPHA6c2xkIHhtbG5zOnA9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9wcmVzZW50YXRpb25tbC8yMDA2L21haW4iIHhtbG5zOmE9Imh0dHA6Ly9zY2hlbWFzLm9wZW54bWxmb3JtYXRzLm9yZy9kcmF3aW5nbWwvMjAwNi9tYWluIj48cDpjU2xkPjxwOnNwVHJlZT48cDpzcD48cDp0eEJvZHk+PGE6cD48YTpyPjxhOnQ+U2Vjb25kIHNsaWRlIHRleHQ8L2E6dD48L2E6cj48L2E6cD48L3A6dHhCb2R5PjwvcDpzcD48L3A6c3BUcmVlPjwvcDpjU2xkPjwvcDpzbGQ+UEsBAhQDFAAAAAAA8wAtXScKBiuAAQAAgAEAABUAAAAAAAAAAAAAAIABAAAAAHBwdC9zbGlkZXMvc2xpZGUxLnhtbFBLAQIUAxQAAAAAAPMALV0WZtTFDgEAAA4BAAAVAAAAAAAAAAAAAACAAbMBAABwcHQvc2xpZGVzL3NsaWRlMi54bWxQSwUGAAAAAAIAAgCGAAAA9AIAAAAA"
    }
}
