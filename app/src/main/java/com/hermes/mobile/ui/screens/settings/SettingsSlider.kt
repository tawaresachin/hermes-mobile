package com.hermes.mobile.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.HermesPrimary

/**
 * One settings slider: icon + label + value on top, hairline track with a dot
 * thumb below. Chat Text Size and Wake sensitivity share this exact component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSlider(
    icon: ImageVector,
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(6.dp))
            Text(valueText, style = MaterialTheme.typography.labelMedium,
                color = HermesPrimary)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = HermesPrimary,
                activeTrackColor = HermesPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
            track = { state ->
                val inactive = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                val active = HermesPrimary
                Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                    val span = valueRange.endInclusive - valueRange.start
                    val frac = if (span <= 0f) 0f
                        else ((state.value - valueRange.start) / span).coerceIn(0f, 1f)
                    val inset = 6.dp.toPx()
                    val barW = size.width - inset * 2
                    val y = size.height / 2f - 1.dp.toPx()
                    drawRoundRect(
                        color = inactive,
                        topLeft = Offset(inset, y),
                        size = Size(barW, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()))
                    if (frac > 0f) drawRoundRect(
                        color = active,
                        topLeft = Offset(inset, y),
                        size = Size(barW * frac, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()))
                }
            },
            thumb = { Box(Modifier.size(12.dp).background(HermesPrimary, CircleShape)) },
        )
    }
}
