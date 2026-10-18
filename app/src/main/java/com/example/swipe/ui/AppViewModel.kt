package com.example.swipe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.swipe.data.Message
import com.example.swipe.data.Profile
import com.example.swipe.data.Repository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {
    private val repo = Repository()

    val deck = repo.deck
    val matches = repo.matches
    val chats = repo.chats

    private val _newMatch = MutableStateFlow<Profile?>(null)
    val newMatch: StateFlow<Profile?> = _newMatch.asStateFlow()

    fun onSwiped(profile: Profile, liked: Boolean) {
        if (repo.swipe(profile, liked)) _newMatch.value = profile
    }

    fun dismissMatch() { _newMatch.value = null }

    fun send(matchId: String, text: String) {
        repo.send(matchId, Message(fromMe = true, text = text))
        // Mock reply so chat feels alive. Remove once real messages arrive from a backend.
        viewModelScope.launch {
            delay(1200)
            repo.send(matchId, Message(fromMe = false, text = replies.random()))
        }
    }

    private val replies = listOf(
        "Ha, nice. Tell me more.",
        "Okay, you've got my attention.",
        "Free this weekend?",
        "That's my kind of plan."
    )
}
