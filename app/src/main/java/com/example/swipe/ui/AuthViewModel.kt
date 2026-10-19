package com.example.swipe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.swipe.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { PHONE, CODE }

data class AuthUi(
    val loggedIn: Boolean,
    val step: AuthStep = AuthStep.PHONE,
    val phone: String = "",
    val ref: String = "",
    val link: String = "",
    val loading: Boolean = false,
    val error: String? = null
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(app)
    private val _ui = MutableStateFlow(AuthUi(loggedIn = repo.token != null))
    val ui: StateFlow<AuthUi> = _ui.asStateFlow()

    fun sendCode(raw: String) {
        val phone = raw.filter { it.isDigit() || it == '+' }
        if (!Regex("^\\+[1-9]\\d{7,14}$").matches(phone)) {
            _ui.update { it.copy(error = "Enter your number with country code, like +8613800138000") }
            return
        }
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.start(phone)
                .onSuccess { r ->
                    _ui.update { s ->
                        s.copy(step = AuthStep.CODE, phone = phone, ref = r.ref, link = r.link, loading = false)
                    }
                }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun verify(code: String) {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.verify(_ui.value.ref, code)
                .onSuccess { _ui.update { it.copy(loggedIn = true, loading = false) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun changeNumber() = _ui.update { it.copy(step = AuthStep.PHONE, error = null) }

    fun logout() {
        repo.logout()
        _ui.value = AuthUi(loggedIn = false)
    }
}
