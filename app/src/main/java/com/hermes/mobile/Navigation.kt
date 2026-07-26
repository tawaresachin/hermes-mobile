package com.hermes.mobile

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import com.hermes.mobile.ui.theme.HermesPrimary
import com.hermes.mobile.ui.theme.HermesSecondary

@Composable
fun HermesBottomNavigationBar(
    screens: List<Screen>,
    currentDestination: NavDestination?,
    onNavigate: (Screen) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            screens.forEach { screen ->
                val selected = currentDestination?.route?.let { route ->
                    screen.route == route || route.startsWith(screen.route + "/")
                } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(screen) },
                    icon = {
                        Icon(
                            imageVector = when (screen) {
                                Screen.Home -> ImageVector.vectorResource(R.drawable.ic_home)
                                Screen.Chat -> ImageVector.vectorResource(R.drawable.ic_chat)
                                Screen.Sessions -> ImageVector.vectorResource(R.drawable.ic_history)
                                Screen.Settings -> ImageVector.vectorResource(R.drawable.ic_settings)
                                else -> ImageVector.vectorResource(R.drawable.ic_chat)
                            },
                            contentDescription = screen.label,
                            tint = if (selected) HermesPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = screen.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) HermesPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = HermesPrimary.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
    }
}
