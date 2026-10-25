package com.example.swipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.swipe.data.ageFromBirthDate

/** Change name, age, city, gender and who you are interested in. The age is locked once it was confirmed with an ID. */
@Composable
fun EditProfileScreen(
    me: MyProfile,
    saving: Boolean,
    serverError: String?,
    onSave: (MyProfile) -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(me.name) }
    var birthDate by rememberSaveable { mutableStateOf(me.birthDate) }
    var city by rememberSaveable { mutableStateOf(me.city) }
    var gender by rememberSaveable { mutableStateOf(me.gender) }
    var lookingFor by rememberSaveable { mutableStateOf(me.lookingFor) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val n = name.trim()
        val c = city.trim()
        // Profiles made before birth dates existed keep their age until a birth date is chosen.
        val a = birthDate?.let { ageFromBirthDate(it) } ?: me.age
        error = when {
            n.isEmpty() -> "Please enter your name."
            a < 18 -> "You must be 18 or older to use this app."
            c.isEmpty() -> "Please enter your city."
            else -> null
        }
        if (error == null) onSave(me.copy(name = n, age = a, city = c, gender = gender, lookingFor = lookingFor, birthDate = birthDate))
    }

    Column(
        Modifier.fillMaxSize().background(Page).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Edit profile", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(30) }, label = { Text("Name") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        BirthDateField(
            birthDate = birthDate, onChange = { birthDate = it }, enabled = !me.idVerified,
            lockedNote = if (me.idVerified) "Locked: your birth date was confirmed with your ID." else null
        )
        Spacer(Modifier.height(12.dp))
        CityField(city = city, onCity = { city = it }, required = false, onReady = {})
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
            Text(if (saving) "Saving..." else "Save")
        }
        Spacer(Modifier.height(24.dp))
    }
}
