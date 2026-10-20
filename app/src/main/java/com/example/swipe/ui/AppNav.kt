package com.example.swipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNav(onLogout: () -> Unit, vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val deck by vm.deck.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val newMatch by vm.newMatch.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route == "swipe" || route == "matches") {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == "swipe",
                        onClick = { nav.goTo("swipe") },
                        icon = { Icon(Icons.Filled.Favorite, null) },
                        label = { Text("Discover") }
                    )
                    NavigationBarItem(
                        selected = route == "matches",
                        onClick = { nav.goTo("matches") },
                        icon = { Icon(Icons.Filled.Email, null) },
                        label = { Text("Matches") }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "swipe", modifier = Modifier.padding(pad)) {
            composable("swipe") { SwipeScreen(deck, vm::onSwiped) }
            composable("matches") {
                LaunchedEffect(Unit) { vm.refreshMatches() }
                MatchesScreen(matches, onOpen = { nav.navigate("chat/${it.matchId}") }, onLogout = onLogout, onPhoto = vm::uploadPhoto)
            }
            composable("chat/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                val match = matches.firstOrNull { it.matchId == id }
                if (id != null && match != null) {
                    DisposableEffect(id) {
                        vm.startChat(id)
                        onDispose { vm.stopChat() }
                    }
                    ChatScreen(
                        match = match,
                        messages = messages,
                        onSend = { text -> vm.send(id, text) },
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }

    newMatch?.let { m ->
        AlertDialog(
            onDismissRequest = vm::dismissMatch,
            title = { Text("It's a match") },
            text = { Text("You and ${m.profile.name} liked each other.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissMatch()
                    nav.navigate("chat/${m.matchId}")
                }) { Text("Send a message") }
            },
            dismissButton = { TextButton(onClick = vm::dismissMatch) { Text("Keep swiping") } }
        )
    }
}

private fun NavController.goTo(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
