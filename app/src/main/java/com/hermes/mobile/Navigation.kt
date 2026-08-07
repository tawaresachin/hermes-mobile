package com.hermes.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.hermes.mobile.ui.screens.voice.VoiceScreen
import com.hermes.mobile.ui.theme.HermesPrimary

// ═══════════════════════════════════════════════════════════
// Shared state — observable, lets Home/Sessions pass sessionId to Chat
// ═══════════════════════════════════════════════════════════

object ChatNav {
    /** Set BEFORE navigating to the Chat tab to resume a session. */
    var pendingSessionId: String? by androidx.compose.runtime.mutableStateOf(null)
}

object VoiceNav {
    /** Set BEFORE navigating to the Voice tab to force a NEW session
     *  (Home's voice card). The tab itself resumes the latest session. */
    var pendingNewSession: Boolean by androidx.compose.runtime.mutableStateOf(false)
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
    data object Voice : Screen("voice", "Voice", Icons.Filled.Mic)
    data object Sessions : Screen("sessions", "Sessions", Icons.Filled.History)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavScreens = listOf(Screen.Home, Screen.Chat, Screen.Voice, Screen.Sessions, Screen.Settings)

// ═══════════════════════════════════════════════════════════
// Main Navigation — NavHost + bottom bar
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainNavigation(
    isLoggedIn: Boolean,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Security: leave gated screens (Chat/Voice/Sessions) IMMEDIATELY when
    // the user signs out — an already-open screen must not stay usable.
    LaunchedEffect(isLoggedIn, currentDestination) {
        if (!isLoggedIn) {
            val route = currentDestination?.route
            if (route == Screen.Chat.route || route == Screen.Voice.route || route == Screen.Sessions.route) {
                navController.navigate(Screen.Home.route) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            }
        }
    }

    // Hide the bottom bar while the keyboard is open (Telegram behavior) —
    // otherwise it leaves a dead white band between the input bar and the IME.
    val isImeVisible = WindowInsets.isImeVisible

    Scaffold(
        bottomBar = {
            if (!isImeVisible) {
                HermesBottomNavigationBar(
                    screens = bottomNavScreens,
                    currentDestination = currentDestination,
                    onNavigate = { screen ->
                        navController.navigate(screen.route) {
                            // saveState/restoreState: each tab's backstack
                            // entry (and its ViewModels) SURVIVE tab
                            // switches — an ongoing chat stream keeps
                            // running while the user visits other tabs.
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(scaffoldPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToChat = { sessionId -> openChat(navController, sessionId) },
                    onNavigateToSessions = {
                        navController.navigate(Screen.Sessions.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToVoice = {
                        // Home voice card = NEW voice session (the Voice TAB
                        // itself resumes the latest session).
                        VoiceNav.pendingNewSession = true
                        navController.navigate(Screen.Voice.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Chat.route) {
                if (isLoggedIn) {
                    ChatScreen(
                        paddingValues = scaffoldPadding
                    )
                } else {
                    SignInRequired(
                        feature = "Chat",
                        onGoToSettings = {
                            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                        }
                    )
                }
            }
            composable(Screen.Voice.route) {
                if (isLoggedIn) {
                    VoiceScreen(
                        onExit = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        }
                    )
                } else {
                    SignInRequired(
                        feature = "Voice",
                        onGoToSettings = {
                            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                        }
                    )
                }
            }
            composable(Screen.Sessions.route) {
                if (isLoggedIn) {
                    SessionsScreen(
                        paddingValues = scaffoldPadding,
                        onSessionSelected = { sessionId -> openChat(navController, sessionId) },
                        onBack = {
                            navController.popBackStack()
                        },
                        onNewChat = {
                            openChat(navController, null)
                        }
                    )
                } else {
                    SignInRequired(
                        feature = "Sessions",
                        onGoToSettings = {
                            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                        }
                    )
                }
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

/** Navigate to the chat screen, optionally opening a session (null = new chat). */
private fun openChat(navController: NavHostController, sessionId: String?) {
    ChatNav.pendingSessionId = sessionId
    navController.navigate(Screen.Chat.route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

// ═══════════════════════════════════════════════════════════
// Sign-in gate — Chat / Voice / Sessions require a signed-in account
// ═══════════════════════════════════════════════════════════

@Composable
fun SignInRequired(
    feature: String,
    onGoToSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Sign in required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$feature is locked until you sign in with your bridge account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onGoToSettings) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go to Settings")
        }
    }
}
