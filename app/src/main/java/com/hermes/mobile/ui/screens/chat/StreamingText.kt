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
import androidx.compose.ui.text.TextStyle
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun StreamingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    // STATIC text — the old infinite alpha pulse animated the whole text
    // layer at 60fps (every frame re-rendered the bubble → scroll jank
    // while streaming). The blinking cursor is StreamingIndicator's tiny
    // separate glyph, which is cheap to animate.
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier
    )
}
