package com.example.swipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import coil.compose.AsyncImage
import com.example.swipe.data.readJpeg
import kotlinx.coroutines.launch
import com.example.swipe.data.MatchItem
import com.example.swipe.data.Profile

@Composable
fun Avatar(profile: Profile, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(profile.tint),
        contentAlignment = Alignment.Center
    ) {
        Text(profile.name.first().toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        if (profile.photoUrl != null) {
            AsyncImage(model = profile.photoUrl, contentDescription = profile.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun VerifiedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(Color(0xFF2B9AEE)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, "Verified", tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

@Composable
fun MatchesScreen(
    matches: List<MatchItem>,
    onOpen: (MatchItem) -> Unit,
    onProfile: (MatchItem) -> Unit,
    onLogout: () -> Unit,
    onPhotos: () -> Unit,
    verified: Boolean,
    idVerified: Boolean,
    onVerify: () -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(Page)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Matches", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Menu, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("My photos") }, onClick = { menu = false; onPhotos() })
                    DropdownMenuItem(text = { Text(if (idVerified) "ID verified \u2713" else if (verified) "Verified \u2713" else "Get verified") }, onClick = { menu = false; onVerify() })
                    DropdownMenuItem(text = { Text("Log out") }, onClick = { menu = false; onLogout() })
                    DropdownMenuItem(text = { Text("Delete account") }, onClick = { menu = false; onDeleteAccount() })
                }
            }
        }
        if (matches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches yet. Like someone to get started.", color = Ink.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn {
                items(matches, key = { it.matchId }) { m ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(m) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Box(Modifier.clickable { onProfile(m) }) { Avatar(m.profile) } },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.profile.name, fontWeight = FontWeight.SemiBold)
                                if (m.profile.verified) {
                                    Spacer(Modifier.width(6.dp))
                                    VerifiedBadge()
                                }
                            }
                        },
                        supportingContent = {
                            Text(m.lastMessage ?: "Say hello", maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    )
                }
            }
        }
    }
}
