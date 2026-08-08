package com.hermes.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Light haptic tick — Telegram-style subtle feedback for sends and tab
 * switches. (HapticFeedbackType.TextHandleMove is the gentle "tick";
 * LongPress is reserved for confirmations.)
 */
@Composable
fun hapticTick() {
    LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

@Composable
fun hapticConfirm() {
    LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)
}
