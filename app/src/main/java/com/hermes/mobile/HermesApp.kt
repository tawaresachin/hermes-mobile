package com.hermes.mobile

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hermes.mobile.ui.screens.chat.ChatScreen
import com.hermes.mobile.ui.screens.settings.SettingsScreen
import com.hermes.mobile.ui.screens.auth.PairingScreen
import com.hermes.mobile.ui.navigation.AppNavGraph

@Composable
fun HermesApp() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = AppNavGraph.Pairing.route
    ) {
        composable(AppNavGraph.Pairing.route) {
            PairingScreen(
                onPaired = { desktopUrl ->
                    navController.navigate(AppNavGraph.Chat.route) {
                        popUpTo(AppNavGraph.Pairing.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(AppNavGraph.Chat.route) {
            ChatScreen(
                onSettingsClick = {
                    navController.navigate(AppNavGraph.Settings.route)
                }
            )
        }
        
        composable(AppNavGraph.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
