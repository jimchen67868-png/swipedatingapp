package com.example.swipe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.swipe.data.readCheckedPhoto
import kotlinx.coroutines.launch

/** Shown when the profile has no accepted main photo yet. The server checks the photo shows exactly one face. */
@Composable
fun AddPhotoScreen(onPhoto: (ByteArray) -> Unit, saving: Boolean, serverError: String?, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readCheckedPhoto(context, uri, main = true)?.let(onPhoto) }
    }
    Column(
        Modifier.fillMaxSize().background(Page).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Add your main photo", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use a clear photo of your face. Only you should be in it. Photos with no face, other people, nudity or sexual content are not accepted.",
            color = Ink.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Checking photo...")
            } else Text("Choose photo")
        }
        serverError?.let { Text(it, color = NopeRed, modifier = Modifier.padding(top = 12.dp)) }
        TextButton(onClick = onLogout) { Text("Log out") }
    }
}
