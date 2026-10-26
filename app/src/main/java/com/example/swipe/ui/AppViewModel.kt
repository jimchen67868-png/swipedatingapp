package com.example.swipe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.swipe.data.Api
import com.example.swipe.data.ApiException
import com.example.swipe.data.MatchItem
import com.example.swipe.data.Message
import com.example.swipe.data.MyPhotos
import com.example.swipe.data.Profile
import com.example.swipe.data.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(Api(app))
    private val swiped = mutableSetOf<String>()
    private val pullLock = Mutex()
    private var chatJob: Job? = null
    private var activeChat: Long? = null

    private val _deck = MutableStateFlow<List<Profile>>(emptyList())
    val deck: StateFlow<List<Profile>> = _deck.asStateFlow()
    private val _matches = MutableStateFlow<List<MatchItem>>(emptyList())
    val matches: StateFlow<List<MatchItem>> = _matches.asStateFlow()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    private val _newMatch = MutableStateFlow<MatchItem?>(null)
    val newMatch: StateFlow<MatchItem?> = _newMatch.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _photos = MutableStateFlow<MyPhotos?>(null)
    val photos: StateFlow<MyPhotos?> = _photos.asStateFlow()
    private val _photoBusy = MutableStateFlow(false)
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()
    private val _verified = MutableStateFlow<Boolean?>(null)
    val verified: StateFlow<Boolean?> = _verified.asStateFlow()
    private val _idVerified = MutableStateFlow<Boolean?>(null)
    val idVerified: StateFlow<Boolean?> = _idVerified.asStateFlow()
    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()
    private val _expired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _expired.asStateFlow()

    private fun fail(e: Throwable, quiet: Boolean = false) {
        if ((e as? ApiException)?.status == 401) _expired.value = true
        else if (!quiet) _error.value = e.message ?: "Network error"
    }

    /** Sets the starting value once; later calls (for example after rotating the screen) are ignored. */
    fun initVerified(v: Boolean, id: Boolean) {
        if (_verified.value == null) _verified.value = v
        if (_idVerified.value == null) _idVerified.value = id
    }

    fun verifyId(idPhoto: ByteArray, selfie: ByteArray, dob: List<String>) {
        if (_verifying.value) return
        _verifying.value = true
        viewModelScope.launch {
            repo.verifyId(idPhoto, selfie, dob)
                .onSuccess { _verified.value = true; _idVerified.value = true; _error.value = "ID verified" }
                .onFailure { fail(it) }
            _verifying.value = false
        }
    }

    fun verify(selfie: ByteArray) {
        if (_verifying.value) return
        _verifying.value = true
        viewModelScope.launch {
            repo.verify(selfie)
                .onSuccess { _verified.value = true; _error.value = "You're verified" }
                .onFailure { fail(it) }
            _verifying.value = false
        }
    }

    fun clearError() { _error.value = null }
    fun dismissMatch() { _newMatch.value = null }

    fun refresh() {
        loadDeck()
        refreshMatches()
    }

    /** Starts the deck again, for example after the maximum distance was changed. */
    fun reloadDeck() {
        _deck.value = emptyList()
        loadDeck()
    }

    private fun loadDeck() {
        viewModelScope.launch {
            repo.deck()
                .onSuccess { list ->
                    // Add new people without disturbing the card the user is looking at.
                    _deck.update { cur -> cur + list.filter { n -> n.id !in swiped && cur.none { it.id == n.id } } }
                }
                .onFailure { fail(it) }
        }
    }

    fun refreshMatches() {
        viewModelScope.launch {
            repo.matches().onSuccess { _matches.value = it }.onFailure { fail(it) }
        }
    }

    fun onSwiped(profile: Profile, liked: Boolean) {
        swiped += profile.id
        _deck.update { d -> d.filterNot { it.id == profile.id } }
        viewModelScope.launch {
            repo.swipe(profile.id, liked)
                .onSuccess { matchId ->
                    if (matchId != null) {
                        repo.matches().onSuccess { list ->
                            _matches.value = list
                            _newMatch.value = list.firstOrNull { it.matchId == matchId }
                        }
                    }
                }
                .onFailure { fail(it) }
            if (_deck.value.size < 3) loadDeck()
        }
    }

    /** reason == null means just block. Either way the person disappears from the deck and the matches. */
    fun reportOrBlock(profile: Profile, reason: String?) {
        swiped += profile.id
        _deck.update { d -> d.filterNot { it.id == profile.id } }
        _matches.update { m -> m.filterNot { it.profile.id == profile.id } }
        viewModelScope.launch {
            val result = if (reason != null) repo.report(profile.id, reason) else repo.block(profile.id)
            result
                .onSuccess { _error.value = if (reason != null) "Thanks, we'll review your report. They can no longer contact you." else "Blocked" }
                .onFailure { fail(it) }
            if (_deck.value.size < 3) loadDeck()
        }
    }

    /** Deletes the account on the server. onDone logs out on this phone. */
    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteAccount().onSuccess { onDone() }.onFailure { fail(it) }
        }
    }

    private fun busy(block: suspend () -> Unit) {
        if (_photoBusy.value) return
        _photoBusy.value = true
        viewModelScope.launch {
            try { block() } finally { _photoBusy.value = false }
        }
    }

    fun loadPhotos() {
        viewModelScope.launch {
            repo.myPhotos().onSuccess { _photos.value = it }.onFailure { fail(it, quiet = true) }
        }
    }

    fun addPhoto(jpeg: ByteArray) = busy {
        repo.addPhoto(jpeg).onSuccess { loadPhotos() }.onFailure { fail(it) }
    }

    fun deletePhoto(id: Int) = busy {
        repo.deletePhoto(id).onSuccess { loadPhotos() }.onFailure { fail(it) }
    }

    fun uploadPhoto(jpeg: ByteArray) {
        busy {
            repo.uploadPhoto(jpeg)
                .onSuccess {
                    loadPhotos()
                    val was = _verified.value == true
                    _verified.value = false
                    _idVerified.value = false
                    _error.value = if (was) "Photo updated. Verify again to keep your badge." else "Photo updated"
                }
                .onFailure { fail(it) }
        }
    }

    // ----- chat -----
    fun startChat(matchId: Long) {
        chatJob?.cancel()
        activeChat = matchId
        _messages.value = emptyList()
        chatJob = viewModelScope.launch {
            while (isActive) {
                pull(matchId)
                delay(3000)
            }
        }
    }

    fun stopChat() {
        chatJob?.cancel()
        chatJob = null
        activeChat = null
    }

    private suspend fun pull(matchId: Long) {
        pullLock.withLock {
            if (activeChat != matchId) return
            val after = _messages.value.lastOrNull()?.id ?: 0L
            repo.messages(matchId, after)
                .onSuccess { new ->
                    if (activeChat == matchId && new.isNotEmpty()) _messages.update { it + new }
                }
                .onFailure { fail(it, quiet = true) }
        }
    }

    fun send(matchId: Long, text: String) {
        viewModelScope.launch {
            repo.send(matchId, text).onSuccess { pull(matchId) }.onFailure { fail(it) }
        }
    }

    /** Clears everything, used on logout so the next person starts clean. */
    fun reset() {
        stopChat()
        swiped.clear()
        _deck.value = emptyList()
        _matches.value = emptyList()
        _messages.value = emptyList()
        _newMatch.value = null
        _error.value = null
        _expired.value = false
        _verified.value = null
        _idVerified.value = null
        _photos.value = null
        _verifying.value = false
    }
}
