package com.switchboard.voice.speech.api

import kotlinx.coroutines.flow.StateFlow

interface SpeechInputEngine : AutoCloseable {
    val descriptor: SpeechInputDescriptor
    val state: StateFlow<SpeechInputState>

    suspend fun listen(config: SpeechInputConfig = SpeechInputConfig()): SpeechInputResult
    fun stop()
    override fun close()
}

data class SpeechInputDescriptor(
    val id: String,
    val displayName: String,
    val available: Boolean,
)

data class SpeechInputConfig(
    val localeTag: String? = null,
    val timeoutMillis: Long = 12_000L,
    val preferOffline: Boolean = true,
)

sealed interface SpeechInputResult {
    data class Transcript(val text: String) : SpeechInputResult
    data class NoSpeech(val reason: String) : SpeechInputResult
    data class Failed(val reason: String) : SpeechInputResult
    data object Cancelled : SpeechInputResult
}

sealed interface SpeechInputState {
    data object Idle : SpeechInputState
    data object Listening : SpeechInputState
    data object SpeechDetected : SpeechInputState
    data class Failed(val reason: String) : SpeechInputState
    data object Unavailable : SpeechInputState
}
