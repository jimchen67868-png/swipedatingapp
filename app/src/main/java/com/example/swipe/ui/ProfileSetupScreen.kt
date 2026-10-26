package com.example.swipe.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.swipe.data.ageFromBirthDate
import com.example.swipe.data.readCheckedPhoto
import kotlinx.coroutines.launch
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

internal val genderOptions = listOf("man" to "Man", "woman" to "Woman")
internal val lookingOptions = listOf("men" to "Men", "women" to "Women")

@Composable
fun ProfileSetupScreen(onSave: (MyProfile, ByteArray?) -> Unit, saving: Boolean = false, serverError: String? = null) {
    var name by rememberSaveable { mutableStateOf("") }
    var birthDate by rememberSaveable { mutableStateOf<String?>(null) }
    var city by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("") }
    var lookingFor by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var cityReady by remember { mutableStateOf(false) }
    var coords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readCheckedPhoto(context, uri, main = true)?.let { photo = it } }
    }
    val preview = remember(photo) { photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    fun submit() {
        val n = name.trim()
        val c = city.trim()
        val a = birthDate?.let { ageFromBirthDate(it) }
        error = when {
            n.isEmpty() -> "Please enter your name."
            a == null -> "Please choose your birth date."
            a < 18 -> "You must be 18 or older to use this app."
            a > 99 -> "Please enter a valid age."
            !cityReady || c.isEmpty() -> "Please turn on location so we can fill in your city."
            gender.isEmpty() -> "Please choose your gender."
            lookingFor.isEmpty() -> "Please choose who you want to meet."
            photo == null -> "Please add a main photo. Only you, with your face clearly visible."
            else -> null
        }
        if (error == null) onSave(MyProfile(n, a!!, c, gender, lookingFor, birthDate = birthDate, lat = coords?.first, lon = coords?.second), photo)
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
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.size(120.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(Ink.copy(alpha = 0.08f))
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) Image(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("Add photo\n(required)", color = Ink.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        BirthDateField(birthDate = birthDate, onChange = { birthDate = it })
        Spacer(Modifier.height(12.dp))
        CityField(city = city, onCity = { city = it }, onCoords = { la, lo -> coords = la to lo }, required = true, onReady = { cityReady = it })

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
internal fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
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
