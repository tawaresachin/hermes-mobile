package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun EmptyChatState(onSuggestion: (String) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Push the text below the centered Hermes watermark (240dp tall,
        // so its bottom edge sits ~124dp below screen center).
        Spacer(modifier = Modifier.height(132.dp))

        Text(
            text = "No messages here yet...",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Send a message or tap a suggestion below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        // Telegram-style suggestion chips — tap to ask instantly.
        Spacer(modifier = Modifier.height(20.dp))
        val suggestions = listOf(
            "What can you do?",
            "Plan my day",
            "Explain like I'm 5",
            "Summarize this article"
        )
        suggestions.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                rowItems.forEach { suggestion ->
                    SuggestionChip(onClick = { onSuggestion(suggestion) }, label = suggestion)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(onClick: () -> Unit, label: String) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = HermesPrimary.copy(alpha = 0.10f),
        modifier = Modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = HermesPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
