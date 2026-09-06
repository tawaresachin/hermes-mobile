package com.hermes.mobile.ui.navigation

sealed class AppNavGraph(val route: String) {
    object Pairing : AppNavGraph("pairing")
    object Chat : AppNavGraph("chat")
    object Settings : AppNavGraph("settings")
}
