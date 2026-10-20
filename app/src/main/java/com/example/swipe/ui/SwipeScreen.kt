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
        if (profile.photoUrl != null) {
            AsyncImage(
                model = profile.photoUrl, contentDescription = profile.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(20.dp)
        ) {
            Text("${profile.name}, ${profile.age}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
