package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun ToolCallCard(toolCall: ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }
    val hasResult = !toolCall.result.isNullOrBlank()
    val hasArgs = toolCall.arguments.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(enabled = hasResult) { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (toolCall.status) {
                ToolCallStatus.RUNNING -> WarningAmber.copy(alpha = 0.12f)
                ToolCallStatus.COMPLETED -> SuccessGreen.copy(alpha = 0.08f)
                ToolCallStatus.FAILED -> ErrorRed.copy(alpha = 0.08f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header row: icon + name + status
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status icon
                when (toolCall.status) {
                    ToolCallStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = WarningAmber
                    )
                    ToolCallStatus.COMPLETED -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    ToolCallStatus.FAILED -> Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                // Tool icon
                Icon(
                    imageVector = when (toolCall.name) {
                        "terminal" -> Icons.Filled.Terminal
                        "web_search", "web_extract" -> Icons.Filled.Language
                        "read_file" -> Icons.Filled.Description
                        "write_file" -> Icons.Filled.Edit
                        "search_files" -> Icons.Filled.Search
                        "patch" -> Icons.Filled.Build
                        "execute_code" -> Icons.Filled.Code
                        else -> Icons.Filled.Build
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = toolCall.name.replace("_", " "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                // Status label
                Text(
                    text = when (toolCall.status) {
                        ToolCallStatus.RUNNING -> "Running..."
                        ToolCallStatus.COMPLETED -> "Done"
                        ToolCallStatus.FAILED -> "Failed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (toolCall.status) {
                        ToolCallStatus.RUNNING -> WarningAmber
                        ToolCallStatus.COMPLETED -> SuccessGreen
                        ToolCallStatus.FAILED -> ErrorRed
                    }
                )
            }

            // Arguments (truncated preview)
            if (hasArgs && !expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolCall.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Result (expandable)
            if (hasResult) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (expanded) toolCall.result!!
                    else toolCall.result!!.take(80) + if (toolCall.result!!.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 20 else 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (toolCall.result!!.length > 80) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (expanded) "▲ Show less" else "▼ Show more",
                        style = MaterialTheme.typography.labelSmall,
                        color = HermesPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
