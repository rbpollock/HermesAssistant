package com.example.hermesassistant

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * App-scoped ViewModel holder. The Compose sheet and the legacy
 * MainActivity must share ONE AssistantViewModel (and therefore one
 * RelayClient/AudioPlayer/VoiceInput) — two instances would open two
 * WebSocket connections and two audio stacks.
 */
object AppViewModelProvider {

    private lateinit var app: Application

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
    }

    val viewModel: AssistantViewModel by lazy {
        AssistantViewModel(app)
    }

    /** Factory so activities can `by viewModels { AppViewModelProvider.factory }`. */
    val factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
                return viewModel as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
