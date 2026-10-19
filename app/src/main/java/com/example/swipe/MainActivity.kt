package com.example.swipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.swipe.ui.AppNav
import com.example.swipe.ui.AppTheme
import com.example.swipe.ui.AuthViewModel
import com.example.swipe.ui.LoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val auth: AuthViewModel = viewModel()
                val ui by auth.ui.collectAsStateWithLifecycle()
                if (ui.loggedIn) {
                    AppNav(onLogout = auth::logout)
                } else {
                    LoginScreen(ui, onStart = auth::start, onCancel = auth::cancel)
                }
            }
        }
    }
}
