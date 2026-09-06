package com.hermes.mobile.ui.settings

import androidx.compose.runtime.Composable

/** Format token count for display. */
@Composable
fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000f)
        tokens >= 1_000 -> "%.0fK".format(tokens / 1_000f)
        else -> tokens.toString()
    }
}
