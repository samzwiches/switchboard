package com.switchboard.voice.api

import kotlinx.coroutines.flow.StateFlow

interface VoiceProvider : AutoCloseable {
    val descriptor: VoiceProviderDescriptor
    val state: StateFlow<VoiceState>

    suspend fun initialize(): Result<Unit>
    suspend fun speak(text: String): Result<Unit>
    fun stop()
    override fun close()
}

data class VoiceProviderDescriptor(
    val id: String,
    val displayName: String,
)

sealed interface VoiceState {
    data object Uninitialized : VoiceState
    data object Ready : VoiceState
    data class Speaking(val text: String) : VoiceState
    data class Failed(val reason: String) : VoiceState
}

