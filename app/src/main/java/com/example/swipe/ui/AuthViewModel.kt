package com.example.swipe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.swipe.data.ApiException
import com.example.swipe.data.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AuthStep { START, WAITING }

data class AuthUi(
    val loggedIn: Boolean,
    val step: AuthStep = AuthStep.START,
    val link: String = "",
    val autoOpen: Boolean = false,   // true only right after the user taps Continue
    val loading: Boolean = false,
    val error: String? = null
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(app)
    private val _ui = MutableStateFlow(AuthUi(loggedIn = repo.token != null))
    val ui: StateFlow<AuthUi> = _ui.asStateFlow()
    private var pollJob: Job? = null

    init {
        // Android may have closed the app while the user was in Telegram: pick the login back up.
        val secret = repo.pendingSecret
        if (repo.token == null && secret != null) {
            _ui.update { it.copy(step = AuthStep.WAITING, link = repo.pendingLink.orEmpty()) }
            poll(secret)
        }
    }

    fun start() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.start()
                .onSuccess { r ->
                    _ui.update { s -> s.copy(step = AuthStep.WAITING, link = r.link, autoOpen = true, loading = false) }
                    poll(r.secret)
                }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message ?: "Network error") } }
        }
    }

    private fun poll(secret: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                val result = repo.checkStatus(secret)
                result.onSuccess { done ->
                    if (done) {
                        _ui.update { it.copy(loggedIn = true) }
                        return@launch
                    }
                }
                result.onFailure { e ->
                    // Server said no (expired etc.): stop. Network hiccups: keep trying.
                    if (e is ApiException) {
                        repo.clearPending()
                        _ui.update { it.copy(step = AuthStep.START, link = "", autoOpen = false, error = e.message) }
                        return@launch
                    }
                }
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        repo.clearPending()
        _ui.update { it.copy(step = AuthStep.START, link = "", autoOpen = false, error = null) }
    }

    fun logout() {
        pollJob?.cancel()
        repo.logout()
        repo.clearPending()
        _ui.value = AuthUi(loggedIn = false)
    }
}
