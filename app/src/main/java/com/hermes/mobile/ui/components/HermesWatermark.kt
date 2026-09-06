package com.hermes.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hermes.mobile.R

/**
 * Faded Hermes watermark (the girl portrait) — theme-agnostic, purely
 * decorative. Drop it as the FIRST child of any screen's root so it sits
 * behind the content. Same look on every screen (centered, 240dp, 10%).
 * Non-interactive (no pointer handling).
 */
@Composable
fun HermesWatermark(
    modifier: Modifier = Modifier,
    size: Int = 240,
    alpha: Float = 0.10f
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Image(
            painter = painterResource(R.drawable.hermes_watermark),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(size.dp)
                .alpha(alpha),
            contentScale = ContentScale.Fit
        )
    }
}
