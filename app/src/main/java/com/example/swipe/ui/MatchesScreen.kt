package com.example.swipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.swipe.data.Message
import com.example.swipe.data.Profile

@Composable
fun Avatar(profile: Profile, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(profile.tint),
        contentAlignment = Alignment.Center
    ) {
        Text(profile.name.first().toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun MatchesScreen(
    matches: List<Profile>,
    chats: Map<String, List<Message>>,
    onOpen: (Profile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(Page)) {
        Text("Matches", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
        if (matches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches yet. Like someone to get started.", color = Ink.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn {
                items(matches, key = { it.id }) { p ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(p) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Avatar(p) },
                        headlineContent = { Text(p.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(chats[p.id]?.lastOrNull()?.text ?: "Say hello", maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    )
                }
            }
        }
    }
}
