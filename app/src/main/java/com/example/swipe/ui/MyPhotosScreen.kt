package com.example.swipe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.swipe.data.MyPhotos
import com.example.swipe.data.readCheckedPhoto
import kotlinx.coroutines.launch

private const val MAX_EXTRA = 5

@Composable
fun MyPhotosScreen(
    photos: MyPhotos?,
    busy: Boolean,
    onChangeMain: (ByteArray) -> Unit,
    onAdd: (ByteArray) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readCheckedPhoto(context, uri, main = true)?.let(onChangeMain) }
    }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readCheckedPhoto(context, uri, main = false, mainPhotoUrl = photos?.main)?.let(onAdd) }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    var confirm by remember { mutableStateOf<Int?>(null) }
    val extras = photos?.extras.orEmpty()
    val tile = Modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(Ink.copy(alpha = 0.08f))

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("My photos", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            "Every photo must show only you, and extra photos must match your main photo. Your main photo needs a close, clear face. You can add up to $MAX_EXTRA more photos. No nudity, sexual content or anything illegal: photos are checked automatically.",
            color = Ink.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Box(tile.clickable(enabled = !busy) { mainPicker.launch(imageOnly) }) {
                    if (photos?.main != null) {
                        AsyncImage(photos.main, "Main photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        "Main - tap to change", color = Color.White, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(4.dp)
                    )
                }
            }
            items(extras, key = { it.id }) { slot ->
                Box(tile) {
                    AsyncImage(slot.url, "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = !busy) { confirm = slot.id },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Close, "Delete photo", tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
            if (photos != null && extras.size < MAX_EXTRA) {
                item {
                    Box(tile.clickable(enabled = !busy) { addPicker.launch(imageOnly) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, "Add photo", tint = Ink.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }

    confirm?.let { id ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this photo?") },
            confirmButton = { TextButton(onClick = { onDelete(id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}
