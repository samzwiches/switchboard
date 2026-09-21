package com.switchboard.app.settings

import android.content.Context
import androidx.core.content.edit
import com.switchboard.core.wakeword.DEFAULT_WAKE_PHRASE
import com.switchboard.providers.openai.MOCK_OPENAI_PROVIDER_ID
import com.switchboard.providers.openai.OPENAI_BACKEND_PROVIDER_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SwitchboardSettingsRepository(context: Context, private val defaultBackendUrl: String = "") {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readSettings())

    val settings: StateFlow<SwitchboardSettings> = mutableSettings.asStateFlow()

    fun setWakePhrase(phrase: String) {
        preferences.edit { putString(KEY_WAKE_PHRASE, phrase) }
        mutableSettings.update { it.copy(wakePhrase = phrase) }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_WAKE_WORD_ENABLED, enabled) }
        mutableSettings.update { it.copy(wakeWordEnabled = enabled) }
    }

    fun setSelectedProvider(providerId: String) {
        val normalized = providerId.takeIf { it in SUPPORTED_PROVIDER_IDS } ?: MOCK_OPENAI_PROVIDER_ID
        preferences.edit { putString(KEY_SELECTED_PROVIDER, normalized) }
        mutableSettings.update {
            it.copy(
                selectedProviderId = normalized,
                selectedProviderName = providerDisplayName(normalized),
            )
        }
    }

    fun setBackendUrl(url: String) {
        val normalized = url.trim()
        preferences.edit { putString(KEY_BACKEND_URL, normalized) }
        mutableSettings.update { it.copy(backendUrl = normalized) }
    }

    fun resetBackendUrl() {
        preferences.edit { remove(KEY_BACKEND_URL) }
        mutableSettings.update { it.copy(backendUrl = defaultBackendUrl) }
    }

    private fun readSettings(): SwitchboardSettings {
        val selected = preferences.getString(KEY_SELECTED_PROVIDER, MOCK_OPENAI_PROVIDER_ID)
            ?.takeIf { it in SUPPORTED_PROVIDER_IDS }
            ?: MOCK_OPENAI_PROVIDER_ID
        return SwitchboardSettings(
            backendUrl = preferences.getString(KEY_BACKEND_URL, defaultBackendUrl) ?: defaultBackendUrl,
            selectedProviderId = selected,
            selectedProviderName = providerDisplayName(selected),
            wakePhrase = DEFAULT_WAKE_PHRASE,
            wakeWordEnabled = preferences.getBoolean(KEY_WAKE_WORD_ENABLED, false),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "switchboard_settings"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_WAKE_PHRASE = "wake_phrase"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_SELECTED_PROVIDER = "selected_provider"
        val SUPPORTED_PROVIDER_IDS = setOf(MOCK_OPENAI_PROVIDER_ID, OPENAI_BACKEND_PROVIDER_ID)
    }
}

private fun providerDisplayName(providerId: String): String = when (providerId) {
    OPENAI_BACKEND_PROVIDER_ID -> "OpenAI"
    else -> "OpenAI (mock)"
}

data class SwitchboardSettings(
    val selectedProviderId: String,
    val selectedProviderName: String,
    val wakePhrase: String,
    val wakeWordEnabled: Boolean,
    val backendUrl: String = "",
)
