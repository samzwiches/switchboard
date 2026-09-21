package com.switchboard.core.wakeword

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Offline debug provider retained for tests. It never opens an audio stream. */
class MockWakeWordProvider : WakeWordProvider {
    private val mutex = Mutex()
    private val mutableEvents = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 1)
    private val mutableState = MutableStateFlow(WakeWordState(detectorName = "Debug simulation"))
    private var requested = false

    override val events: Flow<WakeWordEvent> = mutableEvents.asSharedFlow()
    override val state: StateFlow<WakeWordState> = mutableState.asStateFlow()

    override suspend fun start(config: WakeWordConfig) {
        mutex.withLock {
            requested = true
            val phrase = config.phrase.trim().ifBlank { DEFAULT_WAKE_PHRASE }
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.ListeningForWakeWord,
                phrase = phrase,
                errorMessage = null,
            )
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            requested = false
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.Stopped,
                microphoneActive = false,
                audioLevel = 0f,
            )
        }
    }

    override suspend fun release() = stop()

    override suspend fun simulateDetection() {
        val event = mutex.withLock {
            check(requested) { "Wake word provider must be listening before detection can be simulated" }
            val now = System.currentTimeMillis()
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.WakeDetected,
                lastWakeAtMillis = now,
            )
            WakeWordEvent.Detected(
                phrase = mutableState.value.phrase,
                detectedAtMillis = now,
                source = WakeWordEventSource.Simulation,
            )
        }
        Log.i(TAG, "WAKE DETECTED: ${event.phrase}")
        mutableEvents.emit(event)
    }

    override suspend fun onAssistantSessionStarted() {
        mutex.withLock {
            if (requested) {
                mutableState.value = mutableState.value.copy(phase = WakeWordPhase.AssistantActive)
            }
        }
    }

    override suspend fun onAssistantSessionFinished() {
        mutex.withLock {
            if (requested) {
                mutableState.value = mutableState.value.copy(phase = WakeWordPhase.ListeningForWakeWord)
            }
        }
    }

    companion object {
        const val DEFAULT_WAKE_PHRASE = com.switchboard.core.wakeword.DEFAULT_WAKE_PHRASE
        private const val TAG = "SwitchboardWakeWord"
    }
}
