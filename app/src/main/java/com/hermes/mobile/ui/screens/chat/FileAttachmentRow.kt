package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ── File attachment row ──
@Composable
fun FileAttachmentRow(
    name: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onDownload: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.AttachFile,
            contentDescription = "File",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Telegram: download affordance on every file bubble — actually saves
        // to Downloads and opens (was decorative-only → "download not working").
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = "Download",
            modifier = Modifier.size(28.dp)
                .clip(CircleShape)
                .clickable(enabled = onDownload != null) { onDownload?.invoke() }
                .padding(5.dp)
                .size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
