package com.example.swipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.swipe.data.Profile
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
fun AppNav(onLogout: () -> Unit, initialVerified: Boolean = false, initialIdVerified: Boolean = false, vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val deck by vm.deck.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val newMatch by vm.newMatch.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val verified by vm.verified.collectAsStateWithLifecycle()
    val idVerified by vm.idVerified.collectAsStateWithLifecycle()
    val verifying by vm.verifying.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val photoBusy by vm.photoBusy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var reportTarget by remember { mutableStateOf<Profile?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(initialVerified) { vm.initVerified(initialVerified, initialIdVerified) }

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
            composable("swipe") { SwipeScreen(deck, vm::onSwiped, onReport = { reportTarget = it }) }
            composable("matches") {
                LaunchedEffect(Unit) { vm.refreshMatches() }
                MatchesScreen(matches, onOpen = { nav.navigate("chat/${it.matchId}") }, onLogout = onLogout, onPhotos = { nav.navigate("photos") },
                    verified = verified == true, idVerified = idVerified == true, onVerify = { nav.navigate("verify") },
                    onDeleteAccount = { confirmDelete = true })
            }
            composable("photos") {
                LaunchedEffect(Unit) { vm.loadPhotos() }
                MyPhotosScreen(
                    photos = photos, busy = photoBusy,
                    onChangeMain = vm::uploadPhoto, onAdd = vm::addPhoto, onDelete = vm::deletePhoto,
                    onBack = { nav.popBackStack() }
                )
            }
            composable("verify") {
                VerifyScreen(
                    verified = verified == true, idVerified = idVerified == true, working = verifying,
                    onSubmit = vm::verify, onSubmitId = vm::verifyId, onBack = { nav.popBackStack() }
                )
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
                        onBack = { nav.popBackStack() },
                        onReport = { reportTarget = match.profile }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This permanently deletes your profile, photos, matches and messages. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteAccount(onLogout) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    reportTarget?.let { p ->
        val leaveChat = { if (route?.startsWith("chat") == true) nav.popBackStack() }
        ReportDialog(
            name = p.name,
            onReport = { reason -> reportTarget = null; vm.reportOrBlock(p, reason); leaveChat() },
            onBlock = { reportTarget = null; vm.reportOrBlock(p, null); leaveChat() },
            onDismiss = { reportTarget = null }
        )
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
