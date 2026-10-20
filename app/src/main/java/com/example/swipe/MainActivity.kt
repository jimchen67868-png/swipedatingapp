package com.example.swipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.swipe.ui.AppNav
import com.example.swipe.ui.AppTheme
import com.example.swipe.ui.AppViewModel
import com.example.swipe.ui.AuthViewModel
import com.example.swipe.ui.ErrorScreen
import com.example.swipe.ui.AddPhotoScreen
import com.example.swipe.ui.LoadingScreen
import com.example.swipe.ui.LoginScreen
import com.example.swipe.ui.ProfileSetupScreen
import com.example.swipe.ui.ProfileState
import com.example.swipe.ui.ProfileViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val auth: AuthViewModel = viewModel()
                val profileVm: ProfileViewModel = viewModel()
                val appVm: AppViewModel = viewModel()
                val ui by auth.ui.collectAsStateWithLifecycle()
                val profile by profileVm.state.collectAsStateWithLifecycle()
                val saving by profileVm.saving.collectAsStateWithLifecycle()
                val saveError by profileVm.saveError.collectAsStateWithLifecycle()
                val expired by appVm.sessionExpired.collectAsStateWithLifecycle()

                val logout = {
                    appVm.reset()
                    profileVm.reset()
                    auth.logout()
                }

                LaunchedEffect(ui.loggedIn) { if (ui.loggedIn) profileVm.load() }
                LaunchedEffect(profile) { if (profile is ProfileState.Ready) appVm.refresh() }
                LaunchedEffect(expired) { if (expired) logout() }

                if (!ui.loggedIn) {
                    LoginScreen(ui, onStart = auth::start, onCancel = auth::cancel)
                } else {
                    when (val s = profile) {
                        ProfileState.Loading -> LoadingScreen()
                        ProfileState.Missing -> ProfileSetupScreen(onSave = profileVm::save, saving = saving, serverError = saveError)
                        is ProfileState.Ready ->
                            if (s.me.hasPhoto) AppNav(onLogout = logout, initialVerified = s.me.verified, initialIdVerified = s.me.idVerified)
                            else AddPhotoScreen(onPhoto = profileVm::uploadPhoto, saving = saving, serverError = saveError, onLogout = logout)
                        is ProfileState.Failed -> ErrorScreen(s.message, onRetry = profileVm::load, onLogout = logout)
                    }
                }
            }
        }
    }
}
