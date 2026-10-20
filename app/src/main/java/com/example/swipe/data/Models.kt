package com.example.swipe.data

import androidx.compose.ui.graphics.Color

data class MyProfile(
    val name: String,
    val age: Int,
    val city: String,
    val gender: String,       // man | woman
    val lookingFor: String,   // men | women
    val verified: Boolean = false
)

private val palette = listOf(
    Color(0xFF5B6CFF), Color(0xFFE5484D), Color(0xFF1FB57A),
    Color(0xFFB45CFF), Color(0xFFEE8A2B), Color(0xFF2B9AEE)
)

data class Profile(
    val id: String,
    val name: String,
    val age: Int,
    val city: String,
    val photoUrl: String? = null,
    val verified: Boolean = false
) {
    val tint: Color get() = palette[id.hashCode().mod(palette.size)]
}

data class MatchItem(val matchId: Long, val profile: Profile, val lastMessage: String?)

data class Message(val id: Long, val fromMe: Boolean, val text: String)
