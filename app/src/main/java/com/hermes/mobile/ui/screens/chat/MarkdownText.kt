package com.hermes.mobile.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A markdown segment - either plain text (with inline formatting) or a fenced code block.
 */
sealed interface MarkdownSegment {
    data class TextSegment(val annotated: AnnotatedString) : MarkdownSegment
    data class CodeBlock(val code: String, val language: String?) : MarkdownSegment
}

/**
 * Parse markdown into a list of segments (text + code blocks).
 */
fun parseMarkdownSegments(text: String, style: TextStyle, color: Color): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    var i = 0
    var builder = AnnotatedString.Builder()

    fun flushPending() {
        if (builder.length > 0) {
            segments.add(MarkdownSegment.TextSegment(builder.toAnnotatedString()))
            builder = AnnotatedString.Builder()
        }
    }

    while (i < text.length) {
        // Fenced code block: ```...``` (multi-line, monospace + bg)
        if (text.startsWith("```", i)) {
            val langEnd = text.indexOf('\n', i + 3)
            val (language, codeStart) = if (langEnd != -1) {
                val lang = text.substring(i + 3, langEnd).trim()
                if (lang.isNotEmpty()) lang to (langEnd + 1) else null to (i + 3)
            } else {
                null to (i + 3)
            }
            val end = text.indexOf("```", codeStart)
            if (end != -1) {
                flushPending()
                val code = text.substring(codeStart, end).trim('\n')
                segments.add(MarkdownSegment.CodeBlock(code, language))
                i = end + 3
                continue
            }
        }
        // Check for **bold** (prefer bold over italic)
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end != -1 && end > i + 2) {
                val start = builder.length
                builder.append(text.substring(i + 2, end))
                builder.addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start, builder.length
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
                val start = builder.length
                builder.append(text.substring(i + 1, end))
                builder.addStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                    start, builder.length
                )
                i = end + 1
                continue
            }
        }
        // Inline code: `...` → monospace + subtle background
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1 && end > i + 1) {
                val start = builder.length
                builder.append(text.substring(i + 1, end))
                builder.addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = color.copy(alpha = 0.10f)
                    ),
                    start, builder.length
                )
                i = end + 1
                continue
            }
        }
        // Quote line: starts with "> " → italic + accent tint
        if (text[i] == '>' && (i == 0 || text[i - 1] == '\n')) {
            val lineEnd = text.indexOf('\n', i)
            val contentEnd = if (lineEnd == -1) text.length else lineEnd
            val start = builder.length
            builder.append(text.substring(i + 1, contentEnd))
            builder.addStyle(
                SpanStyle(
                    fontStyle = FontStyle.Italic,
                    color = color.copy(alpha = 0.75f)
                ),
                start, builder.length
            )
            i = contentEnd
            continue
        }
        builder.append(text[i])
        i++
    }
    flushPending()
    return segments
}

/**
 * Render a single code block with copy button and language label.
 */
@Composable
fun CodeBlockView(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    var copied by remember { mutableStateOf(false) }
    val copyLabel = if (copied) "Copied!" else "Copy"
    val copyIcon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy
    val scope = rememberCoroutineScope()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            // Header: language + copy button
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (language != null && language.isNotBlank()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code block", code))
                        copied = true
                        scope.launch {
                            delay(1500)
                            copied = false
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        copyIcon,
                        contentDescription = copyLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            // Code content
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }
}

/**
 * Render markdown with inline formatting + fenced code blocks (with copy buttons).
 */
@Composable
fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val segments = remember(text, style, color) { parseMarkdownSegments(text, style, color) }
    val context = LocalContext.current

    if (segments.isEmpty()) {
        Text(text = "", style = style, color = color, modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        segments.forEachIndexed { idx, segment ->
            when (segment) {
                is MarkdownSegment.TextSegment -> {
                    if (segment.annotated.length > 0) {
                        Text(
                            text = segment.annotated,
                            style = style,
                            color = color,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is MarkdownSegment.CodeBlock -> {
                    CodeBlockView(
                        code = segment.code,
                        language = segment.language,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (idx > 0) 8.dp else 0.dp, bottom = 4.dp),
                        context = context
                    )
                }
            }
        }
    }
}