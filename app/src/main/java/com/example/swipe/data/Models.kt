package com.example.swipe.data

import androidx.compose.ui.graphics.Color

data class MyProfile(
    val name: String,
    val age: Int,
    val city: String,
    val gender: String,       // man | woman
    val lookingFor: String,   // men | women
    val verified: Boolean = false,
    val idVerified: Boolean = false,
    val hasPhoto: Boolean = false,
    val birthDate: String? = null // yyyy-mm-dd; the age is worked out from it
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
    val verified: Boolean = false,
    val extraPhotos: List<String> = emptyList()
) {
    /** Main photo first, then the extra photos. */
    val photos: List<String> get() = listOfNotNull(photoUrl) + extraPhotos
    val tint: Color get() = palette[id.hashCode().mod(palette.size)]
}

data class PhotoSlot(val id: Int, val url: String)
data class MyPhotos(val main: String?, val extras: List<PhotoSlot>)

data class MatchItem(val matchId: Long, val profile: Profile, val lastMessage: String?)

data class Message(val id: Long, val fromMe: Boolean, val text: String)
