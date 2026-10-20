package com.example.swipe.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.swipe.data.readJpeg
import kotlinx.coroutines.launch
import java.io.File

/** Opens the camera app, asking for the front camera. */
private class FrontTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .putExtra("android.intent.extras.CAMERA_FACING", 1)
            .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
}

@Composable
fun VerifyScreen(verified: Boolean, working: Boolean, onSubmit: (ByteArray) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(FrontTakePicture()) { ok ->
        val uri = pending
        if (ok && uri != null) {
            scope.launch {
                val jpeg = readJpeg(context, uri)
                File(context.cacheDir, "selfies").listFiles()?.forEach { it.delete() } // the selfie is not kept
                if (jpeg != null) onSubmit(jpeg)
            }
        }
    }

    fun shoot() {
        val dir = File(context.cacheDir, "selfies").apply { mkdirs() }
        val file = File(dir, "selfie_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pending = uri
        camera.launch(uri)
    }

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().padding(8.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
            if (verified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VerifiedBadge(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("You're verified", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text("People see a blue check on your card. If you change your photo, you'll need to verify again.", color = Ink.copy(alpha = 0.6f))
            } else {
                Text("Get verified", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Take a selfie and we'll check that it matches your profile photo. " +
                        "Face the camera in good light, with no sunglasses, and make sure you're the only one in the shot. " +
                        "The selfie is not saved.",
                    color = Ink.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = ::shoot, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking...")
                    } else Text("Take selfie")
                }
            }
        }
    }
}
