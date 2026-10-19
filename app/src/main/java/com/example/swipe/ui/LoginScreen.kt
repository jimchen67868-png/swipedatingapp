package com.example.swipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    ui: AuthUi,
    onSend: (String) -> Unit,
    onVerify: (String) -> Unit,
    onChangeNumber: () -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier.fillMaxSize().background(Page).statusBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (ui.step == AuthStep.PHONE) {
            Text("Log in with your phone", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("We'll verify your number through WhatsApp.", color = Ink.copy(alpha = 0.6f))
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone number") },
                placeholder = { Text("+8613800138000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSend(phone) },
                enabled = !ui.loading && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (ui.loading) "Please wait..." else "Continue") }
        } else {
            Text("Verify on WhatsApp", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Tap the button and send the message that appears (don't edit it).\n" +
                    "2. We'll reply on WhatsApp with a 6-digit code.\n" +
                    "3. Enter the code below.",
                color = Ink.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { uriHandler.openUri(ui.link) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Open WhatsApp") }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("6-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onVerify(code) },
                enabled = !ui.loading && code.length == 6,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (ui.loading) "Checking..." else "Verify and continue") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { code = ""; onChangeNumber() }) { Text("Use a different number") }
                TextButton(onClick = { code = ""; onSend(ui.phone) }, enabled = !ui.loading) { Text("Get a new code") }
            }
        }
        ui.error?.let { Text(it, color = NopeRed, modifier = Modifier.padding(top = 12.dp)) }
    }
}
