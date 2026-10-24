package com.example.swipe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.swipe.data.readIdScan
import com.example.swipe.data.readJpeg
import kotlinx.coroutines.launch
import java.io.File

private const val SHOW_READ_TEXT = true // shows the text the phone read when no birth date was found, to help fix it

@Composable
fun VerifyScreen(
    verified: Boolean,
    idVerified: Boolean,
    working: Boolean,
    onSubmit: (ByteArray) -> Unit,
    onSubmitId: (ByteArray, ByteArray, List<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingId by rememberSaveable { mutableStateOf<Uri?>(null) }
    var idJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var dob by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke() else note = "Camera permission is needed to verify."
        afterPermission = null
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { afterPermission = action; permission.launch(Manifest.permission.CAMERA) }
    }

    fun newUri(): Uri {
        val dir = File(context.cacheDir, "selfies").apply { mkdirs() }
        val file = File(dir, "id_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    fun wipe() { File(context.cacheDir, "selfies").listFiles()?.forEach { it.delete() } } // nothing is kept on the phone

    val idCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingId
        if (ok && uri != null) {
            scope.launch {
                scanning = true
                note = null
                val jpeg = readJpeg(context, uri, 1280)
                val scan = readIdScan(context, uri)
                val dates = scan.dates
                wipe()
                scanning = false
                when {
                    jpeg == null -> note = "Couldn't read the photo. Try again."
                    dates.isEmpty() -> note = "We couldn't find a birth date. Photograph the side that shows it: whole card in frame, flat, in focus, no glare." +
                        (if (SHOW_READ_TEXT) "\n\nWhat the phone read (only shown on your screen):\n" + scan.text.replace(Regex("\\s+"), " ").take(240).ifBlank { "nothing" } else "")
                    else -> { idJpeg = jpeg; dob = dates }
                }
            }
        }
    }

    fun onSelfie(selfie: ByteArray) {
        val id = idJpeg
        if (id != null) {
            onSubmitId(id, selfie, dob)
            idJpeg = null
            dob = emptyList()
        } else onSubmit(selfie)
    }

    if (live) {
        BackHandler { live = false }
        LiveSelfie(onDone = { live = false; onSelfie(it) }, onCancel = { live = false })
        return
    }

    val busy = working || scanning
    val hasId = idJpeg != null

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().padding(8.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (verified) {
                    VerifiedBadge(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (idVerified) "ID verified" else if (verified) "You're verified" else "Get verified",
                    fontSize = 28.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (idVerified) "Your face matches your ID and you're 18 or older. If you change your profile photo, you'll need to verify again."
                else "Verified people get a blue check. Photos are checked on our server and are not saved. We only keep that you passed.",
                color = Ink.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))

            if (!verified && !hasId) {
                Button(
                    onClick = { withCamera { live = true } },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (working) "Checking..." else "Quick check: selfie") }
                Text(
                    "Look at the camera, then turn your head when asked. This proves you're a real person and checks you match your profile photo. " +
                        "Use good light, no sunglasses, and be the only one in the shot.",
                    color = Ink.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
                )
            }

            if (!idVerified) {
                if (!hasId) {
                    OutlinedButton(
                        onClick = { withCamera { pendingId = newUri().also { idCamera.launch(it) } } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(if (scanning) "Reading your ID..." else "Verify with ID (face and age)") }
                    Text(
                        "Take a photo of a passport, driving licence or national ID. We check the birth date shows 18+ and that your face matches. " +
                            "We can't check if a document is genuine, so keep your real ID ready.",
                        color = Ink.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Text("ID photo ready. Now a live selfie to finish.", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { withCamera { live = true } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (working) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(12.dp))
                            Text("Checking...")
                        } else Text("Start selfie")
                    }
                    TextButton(onClick = { idJpeg = null; dob = emptyList() }, enabled = !busy) { Text("Retake ID photo") }
                }
            }
            note?.let { Text(it, color = NopeRed, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}
