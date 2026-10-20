package com.example.swipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.swipe.data.MyProfile

private val genderOptions = listOf("man" to "Man", "woman" to "Woman")
private val lookingOptions = listOf("men" to "Men", "women" to "Women")

@Composable
fun ProfileSetupScreen(onSave: (MyProfile) -> Unit, saving: Boolean = false, serverError: String? = null) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("") }
    var lookingFor by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val n = name.trim()
        val c = city.trim()
        val a = age.toIntOrNull()
        error = when {
            n.isEmpty() -> "Please enter your name."
            a == null -> "Please enter your age."
            a < 18 -> "You must be 18 or older to use this app."
            a > 99 -> "Please enter a valid age."
            c.isEmpty() -> "Please enter your city."
            gender.isEmpty() -> "Please choose your gender."
            lookingFor.isEmpty() -> "Please choose who you want to meet."
            else -> null
        }
        if (error == null) onSave(MyProfile(n, a!!, c, gender, lookingFor))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Page)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Tell us about you", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("This is what other people will see on your card.", color = Ink.copy(alpha = 0.6f))
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = age,
            onValueChange = { age = it.filter(Char::isDigit).take(2) },
            label = { Text("Age") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = city,
            onValueChange = { city = it.take(50) },
            label = { Text("Location (city)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text("I am", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ChipRow(genderOptions, gender) { gender = it }

        Spacer(Modifier.height(16.dp))
        Text("Interested in", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ChipRow(lookingOptions, lookingFor) { lookingFor = it }

        (error ?: serverError)?.let { Text(it, color = NopeRed, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = ::submit, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (saving) "Saving..." else "Save and continue")
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Page), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Page).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Couldn't load your profile", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = NopeRed)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
        TextButton(onClick = onLogout) { Text("Log out") }
    }
}
