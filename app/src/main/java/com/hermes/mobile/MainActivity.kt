package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.mobile.ui.screens.chat.ChatScreen
import com.hermes.mobile.ui.screens.home.HomeScreen
import com.hermes.mobile.ui.screens.sessions.SessionsScreen
import com.hermes.mobile.ui.screens.settings.SettingsScreen
import com.hermes.mobile.ui.theme.HermesMobileTheme
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String, val label: String, val icon: String) {
    data object Home : Screen("home", "Home", "home")
    data object Chat : Screen("chat", "Chat", "chat")
    data object Sessions : Screen("sessions", "Sessions", "history")
    data object Settings : Screen("settings", "Settings", "settings")
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HermesNavHost()
                }
            }
        }
    }
}

@Composable
fun HermesNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val screens = listOf(
        Screen.Home, Screen.Chat, Screen.Sessions, Screen.Settings
    )

    val showBottomBar = currentDestination?.route in screens.map { it.route }

    androidx.compose.material3.Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                HermesBottomNavigationBar(
                    screens = screens,
                    currentDestination = currentDestination,
                    onNavigate = { screen ->
                        navController.navigate(screen.route) {
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
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .let { mod ->
                    mod
                },
            enterTransition = { fadeIn(initialAlpha = 0.3f) },
            exitTransition = { fadeOut(targetAlpha = 0.3f) }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToChat = { sessionId ->
                        navController.navigate("chat/${sessionId}") {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSessions = {
                        navController.navigate(Screen.Sessions.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Chat.route + "/{sessionId}?") { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")
                ChatScreen(
                    sessionId = sessionId,
                    paddingValues = paddingValues
                )
            }
            composable(Screen.Sessions.route) {
                SessionsScreen(
                    paddingValues = paddingValues,
                    onSessionSelected = { sessionId ->
                        navController.navigate("chat/$sessionId") {
                            launchSingleTop = true
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(paddingValues = paddingValues)
            }
        }
    }
}
