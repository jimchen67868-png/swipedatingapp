package com.example.swipe.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.swipe.data.Profile
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeScreen(
    deck: List<Profile>,
    onSwiped: (Profile, Boolean) -> Unit,
    onReport: (Profile) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = screenWidthPx * 0.3f

    val top = deck.getOrNull(0)
    val next = deck.getOrNull(1)
    val offsetX = remember(top?.id) { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }

    fun swipeOut(liked: Boolean) {
        val profile = top ?: return
        if (busy) return
        busy = true
        scope.launch {
            offsetX.animateTo(if (liked) screenWidthPx * 1.5f else -screenWidthPx * 1.5f, tween(250))
            onSwiped(profile, liked)
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Page)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (top == null) {
                EmptyState()
            } else {
                if (next != null) {
                    val progress = (abs(offsetX.value) / threshold).coerceIn(0f, 1f)
                    ProfileCard(next, Modifier.scale(0.94f + 0.06f * progress))
                }
                ProfileCard(
                    profile = top,
                    likeAlpha = (offsetX.value / threshold).coerceIn(0f, 1f),
                    nopeAlpha = (-offsetX.value / threshold).coerceIn(0f, 1f),
                    modifier = Modifier
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .rotate(offsetX.value / screenWidthPx * 15f)
                        .pointerInput(top.id) {
                            detectDragGestures(
                                onDragEnd = {
                                    if (abs(offsetX.value) > threshold) swipeOut(offsetX.value > 0)
                                    else scope.launch { offsetX.animateTo(0f, tween(200)) }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    if (!busy) scope.launch { offsetX.snapTo(offsetX.value + drag.x) }
                                }
                            )
                        }
                )
                Text(
                    "Report", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f))
                        .clickable { onReport(top) }.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            ActionButton(Icons.Filled.Close, NopeRed, "Pass", top != null) { swipeOut(false) }
            ActionButton(Icons.Filled.Favorite, LikeGreen, "Like", top != null) { swipeOut(true) }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    modifier: Modifier = Modifier,
    likeAlpha: Float = 0f,
    nopeAlpha: Float = 0f
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.96f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(profile.tint, profile.tint.copy(alpha = 0.55f))))
    ) {
        Text(
            profile.name.first().toString(),
            color = Color.White.copy(alpha = 0.25f),
            fontSize = 160.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
        val photos = profile.photos
        var shown by remember(profile.id) { mutableIntStateOf(0) }
        if (photos.isNotEmpty()) {
            AsyncImage(
                model = photos[shown.coerceIn(0, photos.lastIndex)], contentDescription = profile.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            if (photos.size > 1) {
                // Tap the left or right half of the card to see the previous or next photo.
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (shown > 0) shown-- }
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (shown < photos.lastIndex) shown++ }
                    )
                }
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    photos.indices.forEach { i ->
                        Box(
                            Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = if (i == shown) 0.95f else 0.35f))
                        )
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${profile.name}, ${profile.age}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (profile.verified) {
                    Spacer(Modifier.width(8.dp))
                    VerifiedBadge()
                }
            }
            Text(profile.city, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        }
        Stamp("LIKE", LikeGreen, likeAlpha, Modifier.align(Alignment.TopStart).padding(24.dp).rotate(-12f))
        Stamp("NOPE", NopeRed, nopeAlpha, Modifier.align(Alignment.TopEnd).padding(24.dp).rotate(12f))
    }
}

@Composable
private fun Stamp(text: String, color: Color, alpha: Float, modifier: Modifier) {
    Text(
        text, color = color, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
        modifier = modifier
            .alpha(alpha)
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
private fun ActionButton(icon: ImageVector, color: Color, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = color)
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp)) }
}

@Composable
private fun EmptyState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Yellow))
        Spacer(Modifier.height(16.dp))
        Text("You're all caught up", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Check back later for new people nearby.", color = Ink.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}
