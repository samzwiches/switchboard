package com.switchboard.core.wakeword

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WakeWordProvider {
    val events: Flow<WakeWordEvent>
    val state: StateFlow<WakeWordState>

    suspend fun start(config: WakeWordConfig)
    suspend fun stop()
    suspend fun release()
    suspend fun simulateDetection()
    suspend fun onAssistantSessionStarted()
    suspend fun onAssistantSessionFinished()
}

/** Platform-neutral boundary implemented by a concrete microphone and keyword engine. */
interface WakeWordDetector {
    val name: String
    val events: Flow<WakeWordDetectorEvent>

    suspend fun start(config: WakeWordConfig)
    suspend fun stop()
    suspend fun release()
}

data class WakeWordConfig(
    val phrase: String,
)

sealed interface WakeWordDetectorEvent {
    data object MicrophoneActive : WakeWordDetectorEvent
    data object MicrophoneStopped : WakeWordDetectorEvent
    data class AudioLevel(val normalizedLevel: Float) : WakeWordDetectorEvent
    data class Detected(val phrase: String) : WakeWordDetectorEvent
    data class Error(
        val message: String,
        val kind: WakeWordErrorKind,
        val recoverable: Boolean,
    ) : WakeWordDetectorEvent
}

enum class WakeWordErrorKind {
    Microphone,
    Detector,
}

class WakeWordDetectorException(
    message: String,
    val kind: WakeWordErrorKind,
    val recoverable: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

sealed interface WakeWordEvent {
    data class Detected(
        val phrase: String,
        val detectedAtMillis: Long,
        val source: WakeWordEventSource,
    ) : WakeWordEvent
}

enum class WakeWordEventSource {
    Detector,
    Simulation,
}

data class WakeWordState(
    val phase: WakeWordPhase = WakeWordPhase.Stopped,
    val phrase: String = DEFAULT_WAKE_PHRASE,
    val microphoneActive: Boolean = false,
    val audioLevel: Float = 0f,
    val lastWakeAtMillis: Long? = null,
    val detectorName: String,
    val errorMessage: String? = null,
)

enum class WakeWordPhase {
    Stopped,
    Arming,
    ListeningForWakeWord,
    WakeDetected,
    AssistantActive,
    Rearming,
    Error,
}

const val DEFAULT_WAKE_PHRASE = "Hey Lucas"
