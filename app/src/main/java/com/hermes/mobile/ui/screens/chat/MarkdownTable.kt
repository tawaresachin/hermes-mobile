package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Telegram-style markdown table rendering as a proper Compose UI. */
@Composable
fun MarkdownTable(tableRows: List<List<String>>, darkTheme: Boolean) {
    val columnCount = tableRows.maxOfOrNull { it.size } ?: 0
    if (columnCount == 0 || tableRows.size < 2) return
    // Wide tables crush into illegible ellipses when every column weights
    // to fill the screen (5+ cols x min 60dp > phone width). Few columns:
    // weighted fill (looks great). Many: fixed 140dp columns in a
    // horizontally scrollable strip — Telegram's answer for wide grids.
    val wide = columnCount > 4

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) TableBgDark else TableBgLight
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        ) {
            tableRows.forEachIndexed { rowIdx, row ->
                Row(
                    modifier = Modifier
                        .then(if (wide) Modifier else Modifier.fillMaxWidth())
                        .then(
                            if (rowIdx == 0) Modifier
                                .background(
                                    if (darkTheme) TableHeaderBgDark else TableHeaderBgLight,
                                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                                )
                            else Modifier
                        )
                ) {
                    row.forEachIndexed { colIdx, cell ->
                        Box(
                            modifier = Modifier
                                .then(if (wide) Modifier.width(140.dp) else Modifier.weight(1f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .defaultMinSize(minWidth = 60.dp)
                        ) {
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (rowIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (rowIdx == 0)
                                        (if (darkTheme) TableTextHeaderDark else TableTextHeaderLight)
                                    else
                                        (if (darkTheme) TableTextBodyDark else TableTextBodyLight)
                                ),
                                maxLines = if (wide) 4 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // Vertical divider (except last column)
                        if (colIdx < row.size - 1) {
                            VerticalDivider(
                                color = if (darkTheme) TableDividerDark else TableDividerLight,
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
                // Divider under the header + between data rows, none after
                // the last row.
                if (rowIdx < tableRows.size - 1) {
                    HorizontalDivider(
                        color = if (darkTheme) TableDividerDark else TableDividerLight,
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}
