package com.example.swipe.data

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

data class Profile(
    val id: String,
    val name: String,
    val age: Int,
    val distanceKm: Int,
    val bio: String,
    val photoUrl: String? = null,
    val tint: Color = Color(0xFF5B6CFF)
)

data class Message(val fromMe: Boolean, val text: String)

/**
 * In-memory data source so the app runs with no backend.
 * To go live, replace the bodies below with Firebase/REST calls and keep the same API.
 */
class Repository {
    private val _deck = MutableStateFlow(sampleProfiles)
    val deck: StateFlow<List<Profile>> = _deck.asStateFlow()

    private val _matches = MutableStateFlow<List<Profile>>(emptyList())
    val matches: StateFlow<List<Profile>> = _matches.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val chats: StateFlow<Map<String, List<Message>>> = _chats.asStateFlow()

    /** Records a swipe. Returns true if it created a match. */
    fun swipe(profile: Profile, liked: Boolean): Boolean {
        _deck.update { list -> list.filterNot { it.id == profile.id } }
        // Mock: the other person likes you back 60% of the time.
        val matched = liked && Random.nextFloat() < 0.6f
        if (matched) _matches.update { it + profile }
        return matched
    }

    fun send(matchId: String, message: Message) {
        _chats.update { it + (matchId to (it[matchId].orEmpty() + message)) }
    }
}

val sampleProfiles = listOf(
    Profile("1", "Mei", 26, 3, "Climbing gym regular. Looking for someone to try every dumpling place with.", tint = Color(0xFF5B6CFF)),
    Profile("2", "Jun", 29, 7, "Photographer, weekend hiker, terrible at karaoke but never stops.", tint = Color(0xFFE5484D)),
    Profile("3", "Lin", 24, 12, "Cat person. Will cook you dinner if you bring the wine.", tint = Color(0xFF1FB57A)),
    Profile("4", "Kai", 31, 5, "Designer by day, vinyl collector by night.", tint = Color(0xFFB45CFF)),
    Profile("5", "Yan", 27, 9, "Learning to surf. Badly. Send help and coffee.", tint = Color(0xFFEE8A2B)),
    Profile("6", "Ray", 30, 2, "Night-market snob and amateur baker.", tint = Color(0xFF2B9AEE))
)
