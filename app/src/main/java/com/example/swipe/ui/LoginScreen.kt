package com.example.swipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    ui: AuthUi,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    // Open Telegram once per login attempt (not again after a screen rotation).
    var openedLink by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ui.link) {
        if (ui.link.isNotEmpty() && ui.link != openedLink) {
            openedLink = ui.link
            uriHandler.openUri(ui.link)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Page).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (ui.step == AuthStep.START) {
            Text("Log in with your phone", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "We verify your phone number through Telegram. It takes a few seconds.",
                color = Ink.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStart,
                enabled = !ui.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (ui.loading) "Please wait..." else "Continue with Telegram") }
        } else {
            Text("Confirm in Telegram", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "In the Telegram chat, tap Start, then \"Share my phone number\". " +
                    "Come back here and we'll log you in automatically.",
                color = Ink.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text("Waiting for confirmation...")
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { uriHandler.openUri(ui.link) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Open Telegram again") }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
        }
        ui.error?.let { Text(it, color = NopeRed, modifier = Modifier.padding(top = 12.dp)) }
    }
}
