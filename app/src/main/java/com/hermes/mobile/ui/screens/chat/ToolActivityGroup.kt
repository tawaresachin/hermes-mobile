package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun ToolActivityGroup(jsonLines: String) {
    var expanded by remember(jsonLines) { mutableStateOf(false) }
    val lines = remember(jsonLines) {
        try {
            val arr = org.json.JSONArray(jsonLines)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Triple(
                    o.optString("e", "⚙️") + " " + o.optString("n", "tool"),
                    o.optString("l", ""),
                    o.optString("s", "completed")
                )
            }
        } catch (_: Exception) { emptyList() }
    }
    if (lines.isEmpty()) return
    // Bluish, collapsed to the first two calls + "N more" — tap to expand.
    // Tints the whole agent-work trail (tools/MCP/skills) distinctly from
    // the prose answer, like IDE fold regions.
    val shown = if (expanded) lines else lines.take(2)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = HermesPrimary.copy(alpha = 0.07f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, HermesPrimary.copy(alpha = 0.16f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 26.dp, top = 4.dp, bottom = 4.dp)) {
            if (!expanded && lines.size > 2) {
                Text(
                    text = "Agent work · ${lines.size} steps",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = HermesPrimary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            shown.forEach { (head, label, status) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 0.5.dp)
                ) {
                    Text(
                        text = if (status == "failed") "✕" else if (status == "running") "◌" else "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (status) {
                            "failed" -> ErrorRed
                            "running" -> WarningAmber
                            else -> SuccessGreen
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = head,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = HermesPrimary.copy(alpha = 0.95f),
                        maxLines = 1
                    )
                    if (label.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = HermesPrimary.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (lines.size > 2) {
                Text(
                    text = if (!expanded) "show all ${lines.size} steps" else "collapse",
                    style = MaterialTheme.typography.labelSmall,
                    color = HermesPrimary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
