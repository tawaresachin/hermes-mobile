package com.hermes.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.mobile.ui.screens.chat.ChatScreen
import com.hermes.mobile.ui.screens.home.HomeScreen
import com.hermes.mobile.ui.screens.sessions.SessionsScreen
import com.hermes.mobile.ui.screens.settings.SettingsScreen
import com.hermes.mobile.ui.theme.HermesPrimary

// ═══════════════════════════════════════════════════════════
// Shared state — observable, lets Home/Sessions pass sessionId to Chat
// ═══════════════════════════════════════════════════════════

object ChatNav {
    /** Set BEFORE navigating to the Chat tab to resume a session. */
    var pendingSessionId: String? by androidx.compose.runtime.mutableStateOf(null)
}

// ═══════════════════════════════════════════════════════════
// Screen routes
// ═══════════════════════════════════════════════════════════

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Chat : Screen("chat", "Chat", Icons.Filled.Chat)
    data object Sessions : Screen("sessions", "Sessions", Icons.Filled.History)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavScreens = listOf(Screen.Home, Screen.Chat, Screen.Sessions, Screen.Settings)

// ═══════════════════════════════════════════════════════════
// Main Navigation — NavHost + bottom bar
// ═══════════════════════════════════════════════════════════

@Composable
fun MainNavigation(
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            HermesBottomNavigationBar(
                screens = bottomNavScreens,
                currentDestination = currentDestination,
                onNavigate = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                }
            )
        }
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(scaffoldPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToChat = { sessionId ->
                        ChatNav.pendingSessionId = sessionId
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSessions = {
                        navController.navigate(Screen.Sessions.route)
                    }
                )
            }
            composable(Screen.Chat.route) {
                ChatScreen(
                    paddingValues = scaffoldPadding
                )
            }
            composable(Screen.Sessions.route) {
                SessionsScreen(
                    paddingValues = scaffoldPadding,
                    onSessionSelected = { sessionId ->
                        ChatNav.pendingSessionId = sessionId
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onBack = {
                        navController.popBackStack()
                    },
                    onNewChat = {
                        ChatNav.pendingSessionId = null
                        navController.navigate(Screen.Chat.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(paddingValues = scaffoldPadding)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Bottom navigation bar (Telegram inspired)
// ═══════════════════════════════════════════════════════════

@Composable
fun HermesBottomNavigationBar(
    screens: List<Screen>,
    currentDestination: androidx.navigation.NavDestination?,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        screens.forEach { screen ->
            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = {
                    Icon(
                        imageVector = screen.icon,
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
                    indicatorColor = HermesPrimary.copy(alpha = 0.1f)
                )
            )
        }
    }
}
