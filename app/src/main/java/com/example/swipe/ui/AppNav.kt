package com.example.swipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNav(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val deck by vm.deck.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val newMatch by vm.newMatch.collectAsStateWithLifecycle()

    Scaffold(
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
                MatchesScreen(matches, chats, onOpen = { nav.navigate("chat/${it.id}") })
            }
            composable("chat/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                matches.firstOrNull { it.id == id }?.let { match ->
                    ChatScreen(
                        match = match,
                        messages = chats[id].orEmpty(),
                        onSend = { text -> vm.send(id, text) },
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }

    newMatch?.let { match ->
        AlertDialog(
            onDismissRequest = vm::dismissMatch,
            title = { Text("It's a match") },
            text = { Text("You and ${match.name} liked each other.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissMatch()
                    nav.navigate("chat/${match.id}")
                }) { Text("Send a message") }
            },
            dismissButton = { TextButton(onClick = vm::dismissMatch) { Text("Keep swiping") } }
        )
    }
}

private fun androidx.navigation.NavController.goTo(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
