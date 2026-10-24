package com.example.swipe.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.swipe.data.Profile

/** A person's full profile: all their photos (swipe sideways), name, age, city and the verified badge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    profile: Profile,
    onBack: () -> Unit,
    onReport: () -> Unit,
    onLike: (() -> Unit)? = null,
    onPass: (() -> Unit)? = null
) {
    val photos = profile.photos
    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReport) { Text("Report") }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(profile.tint, profile.tint.copy(alpha = 0.55f))))
        ) {
            Text(
                profile.name.first().toString(),
                color = Color.White.copy(alpha = 0.25f), fontSize = 160.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            if (photos.isNotEmpty()) {
                val pager = rememberPagerState { photos.size }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    AsyncImage(
                        model = photos[page], contentDescription = profile.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                }
                if (photos.size > 1) {
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        photos.indices.forEach { i ->
                            Box(
                                Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.35f))
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
        }
        if (onLike != null && onPass != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
                FilledIconButton(
                    onClick = onPass, modifier = Modifier.size(64.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = NopeRed)
                ) { Icon(Icons.Filled.Close, "Pass", modifier = Modifier.size(32.dp)) }
                FilledIconButton(
                    onClick = onLike, modifier = Modifier.size(64.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = LikeGreen)
                ) { Icon(Icons.Filled.Favorite, "Like", modifier = Modifier.size(32.dp)) }
            }
        }
    }
}
