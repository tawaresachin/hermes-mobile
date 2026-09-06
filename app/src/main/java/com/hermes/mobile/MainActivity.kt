package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.hermes.mobile.ui.PairingScreen
import com.hermes.mobile.ui.navigation.AppNavGraph
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HermesApp()
                }
            }
        }
    }
}

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