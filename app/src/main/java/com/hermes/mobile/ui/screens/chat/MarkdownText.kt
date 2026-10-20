package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text, style, color) {
        parseMarkdown(text, style, color)
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier
    )
}

/**
 * Parse basic markdown into AnnotatedString.
 * Supports: **bold**, *italic*, `inline code`, ```fenced code blocks```,
 * `> quote` lines, and |tables|.
 */

private fun parseMarkdown(
    text: String,
    style: TextStyle,
    baseColor: Color
): AnnotatedString {
    // No table detection here - tables are handled by FullWidthTableOverlay
    // Parse normal markdown only
    return parseMarkdownBody(text, style, baseColor)
}

/** Parse the markdown body without table detection. */

private fun parseMarkdownBody(
    text: String,
    style: TextStyle,
    baseColor: Color
): AnnotatedString {
    val codeBackground = baseColor.copy(alpha = 0.10f)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBackground
    )
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Fenced code block: ```...``` (multi-line, monospace + bg)
            if (text.startsWith("```", i)) {
                val end = text.indexOf("```", i + 3)
                if (end != -1) {
                    val start = length
                    append(text.substring(i + 3, end).trim('\n'))
                    addStyle(codeStyle, start, length)
                    i = end + 3
                    continue
                }
            }
            // Check for **bold** (prefer bold over italic)
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end != -1 && end > i + 2) {
                    val start = length
                    append(text.substring(i + 2, end))
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start, length
                    )
                    i = end + 2
                    continue
                }
            }
            // Check for *italic*
            if (text[i] == '*') {
                val end = text.indexOf('*', i + 1)
                if (end != -1 && end > i + 1 &&
                    !(i + 1 < text.length && text[i + 1] == '*')
                ) {
                    val start = length
                    append(text.substring(i + 1, end))
                    addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        start, length
                    )
                    i = end + 1
                    continue
                }
            }
            // Inline code: `...` → monospace + subtle background
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1 && end > i + 1) {
                    val start = length
                    append(text.substring(i + 1, end))
                    addStyle(codeStyle, start, length)
                    i = end + 1
                    continue
                }
            }
            // Quote line: starts with "> " → italic + accent tint
            if (text[i] == '>' && (i == 0 || text[i - 1] == '\n')) {
                val lineEnd = text.indexOf('\n', i)
                val contentEnd = if (lineEnd == -1) text.length else lineEnd
                val start = length
                append(text.substring(i + 1, contentEnd))
                addStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = baseColor.copy(alpha = 0.75f)
                    ),
                    start, length
                )
                i = contentEnd
                continue
            }
            append(text[i])
            i++
        }
    }
}
