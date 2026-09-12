package com.hermes.mobile.ui.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Telegram-calibrated haptics. Plain (non-composable) helpers so they fire
 * from gesture/send lambdas; capture `LocalHapticFeedback.current` +
 * `LocalContext.current` in composition. The framework path respects the
 * system "Touch control" setting automatically. Subtle by design: ticks on
 * taps/toggles/swipes, double-tick success, single buzz errors. Never
 * per-keystroke.
 */
object Haptics {
    /** light tick — send, toggle, swipe commit */
    fun tick(fb: HapticFeedback) =
        fb.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    /** medium press — long-press menu opens */
    fun press(fb: HapticFeedback) =
        fb.performHapticFeedback(HapticFeedbackType.LongPress)

    /** success double-tick — reply completed / approval accepted */
    fun success(fb: HapticFeedback, ctx: Context) = buzz(fb, ctx, success = true)

    /** error buzz — failed send/download/run error */
    fun error(fb: HapticFeedback, ctx: Context) = buzz(fb, ctx, success = false)

    private fun buzz(fb: HapticFeedback, ctx: Context, success: Boolean) {
        fb.performHapticFeedback(
            if (success) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)
        runCatching {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = if (success) longArrayOf(0, 30, 60, 30) else longArrayOf(0, 70)
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
