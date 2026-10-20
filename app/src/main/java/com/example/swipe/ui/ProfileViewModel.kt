package com.example.swipe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.swipe.data.Api
import com.example.swipe.data.MyProfile
import com.example.swipe.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProfileState {
    data object Loading : ProfileState
    data object Missing : ProfileState
    data class Ready(val me: MyProfile) : ProfileState
    data class Failed(val message: String) : ProfileState
}

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(Api(app))

    private val _state = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val state: StateFlow<ProfileState> = _state.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** Asks the server whether this phone number already has a profile. */
    fun load() {
        _state.value = ProfileState.Loading
        viewModelScope.launch {
            repo.me()
                .onSuccess { me -> _state.value = if (me == null) ProfileState.Missing else ProfileState.Ready(me) }
                .onFailure { e -> _state.value = ProfileState.Failed(e.message ?: "Network error") }
        }
    }

    fun save(p: MyProfile, photo: ByteArray?) {
        _saving.value = true
        _saveError.value = null
        viewModelScope.launch {
            repo.saveMe(p)
                .onSuccess { me ->
                    // The profile is saved. If the photo is refused, the app asks for another one.
                    val upload = if (photo != null) repo.uploadPhoto(photo) else Result.failure(Exception("Please add a photo of you."))
                    upload.onFailure { e -> _saveError.value = e.message ?: "Network error" }
                    _state.value = ProfileState.Ready(me.copy(hasPhoto = upload.isSuccess))
                }
                .onFailure { e -> _saveError.value = e.message ?: "Network error" }
            _saving.value = false
        }
    }

    /** Used when the profile exists but the main photo is missing or was refused. */
    fun uploadPhoto(jpeg: ByteArray) {
        val ready = _state.value as? ProfileState.Ready ?: return
        _saving.value = true
        _saveError.value = null
        viewModelScope.launch {
            repo.uploadPhoto(jpeg)
                .onSuccess { _state.value = ProfileState.Ready(ready.me.copy(hasPhoto = true)) }
                .onFailure { e -> _saveError.value = e.message ?: "Network error" }
            _saving.value = false
        }
    }

    fun reset() {
        _state.value = ProfileState.Loading
        _saveError.value = null
    }
}
