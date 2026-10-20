package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun EmojiPickerGrid(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(EMOJIS.size) { index ->
                Text(
                    text = EMOJIS[index],
                    fontSize = 24.sp,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onEmojiSelected(EMOJIS[index]) }
                        .then(Modifier.padding(2.dp)),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ── Local in-chat search helpers ──

/** Filter the loaded messages by [query] (case-insensitive substring). */


private val EMOJIS = listOf(
    "😀", "😂", "❤️", "🔥", "👍", "🎉", "✨", "💪",
    "😊", "🤣", "😍", "😭", "🥺", "🙏", "💀", "🤝",
    "👋", "🙌", "🚀", "⭐", "💡", "✅", "❌", "📌",
    "🎯", "💯", "🤔", "😎", "👀", "💜", "🎶", "🏆",
    "📱", "💻", "🔗", "📎", "📄", "📂", "🗂️", "📁"
)
