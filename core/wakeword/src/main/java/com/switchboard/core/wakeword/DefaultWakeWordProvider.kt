package com.switchboard.core.wakeword

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns wake lifecycle and state while leaving microphone/model work to [WakeWordDetector].
 * Real and simulated detections are accepted by the same guarded event function.
 */
class DefaultWakeWordProvider(
    private val detector: WakeWordDetector,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) : WakeWordProvider {
    private val mutex = Mutex()
    private val mutableEvents = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 1)
    private val mutableState = MutableStateFlow(
        WakeWordState(detectorName = detector.name),
    )
    private var requested = false
    private var activeConfig = WakeWordConfig(DEFAULT_WAKE_PHRASE)
    private var lastAcceptedDetectionAt = Long.MIN_VALUE
    private var retryJob: Job? = null
    private val detectorEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        detector.events.collect(::handleDetectorEvent)
    }

    override val events: Flow<WakeWordEvent> = mutableEvents.asSharedFlow()
    override val state: StateFlow<WakeWordState> = mutableState.asStateFlow()

    override suspend fun start(config: WakeWordConfig) {
        val normalized = config.copy(phrase = config.phrase.trim().ifBlank { DEFAULT_WAKE_PHRASE })
        val shouldStart = mutex.withLock {
            if (requested && activeConfig == normalized &&
                mutableState.value.phase !in setOf(WakeWordPhase.Error, WakeWordPhase.Stopped)
            ) {
                false
            } else {
                requested = true
                activeConfig = normalized
                retryJob?.cancel()
                mutableState.value = mutableState.value.copy(
                    phase = WakeWordPhase.Arming,
                    phrase = normalized.phrase,
                    microphoneActive = false,
                    audioLevel = 0f,
                    errorMessage = null,
                )
                true
            }
        }
        if (shouldStart) armDetector(WakeWordPhase.Arming)
    }

    override suspend fun stop() {
        mutex.withLock {
            requested = false
            retryJob?.cancel()
            retryJob = null
        }
        detector.stopSafely()
        mutex.withLock {
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.Stopped,
                microphoneActive = false,
                audioLevel = 0f,
                errorMessage = null,
            )
        }
    }

    override suspend fun release() {
        mutex.withLock {
            requested = false
            retryJob?.cancel()
            retryJob = null
        }
        runCatching { detector.release() }
            .onFailure { Log.e(TAG, "WAKE DETECTOR ERROR: release failed", it) }
        mutex.withLock {
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.Stopped,
                microphoneActive = false,
                audioLevel = 0f,
                errorMessage = null,
            )
        }
    }

    override suspend fun simulateDetection() {
        acceptDetection(activeConfig.phrase, WakeWordEventSource.Simulation)
    }

    override suspend fun onAssistantSessionStarted() {
        val shouldPause = mutex.withLock {
            if (!requested || mutableState.value.phase == WakeWordPhase.AssistantActive) {
                false
            } else {
                mutableState.value = mutableState.value.copy(
                    phase = WakeWordPhase.AssistantActive,
                    microphoneActive = false,
                    audioLevel = 0f,
                    errorMessage = null,
                )
                true
            }
        }
        if (shouldPause) detector.stopSafely()
    }

    override suspend fun onAssistantSessionFinished() {
        val shouldRearm = mutex.withLock {
            if (!requested) {
                false
            } else {
                mutableState.value = mutableState.value.copy(
                    phase = WakeWordPhase.Rearming,
                    microphoneActive = false,
                    audioLevel = 0f,
                    errorMessage = null,
                )
                true
            }
        }
        if (shouldRearm) armDetector(WakeWordPhase.Rearming)
    }

    private suspend fun handleDetectorEvent(event: WakeWordDetectorEvent) {
        when (event) {
            WakeWordDetectorEvent.MicrophoneActive -> mutex.withLock {
                if (requested && mutableState.value.phase in MIC_ACTIVATION_PHASES) {
                    Log.i(TAG, "MIC ACTIVE")
                    Log.i(TAG, "WAKE DETECTOR ARMED")
                    mutableState.value = mutableState.value.copy(
                        phase = WakeWordPhase.ListeningForWakeWord,
                        microphoneActive = true,
                        errorMessage = null,
                    )
                }
            }

            WakeWordDetectorEvent.MicrophoneStopped -> mutex.withLock {
                Log.i(TAG, "MIC STOPPED")
                mutableState.value = mutableState.value.copy(
                    microphoneActive = false,
                    audioLevel = 0f,
                )
            }

            is WakeWordDetectorEvent.AudioLevel -> mutex.withLock {
                if (mutableState.value.microphoneActive) {
                    mutableState.value = mutableState.value.copy(
                        audioLevel = event.normalizedLevel.coerceIn(0f, 1f),
                    )
                }
            }

            is WakeWordDetectorEvent.Detected -> {
                acceptDetection(event.phrase, WakeWordEventSource.Detector)
            }

            is WakeWordDetectorEvent.Error -> handleDetectorError(event)
        }
    }

    private suspend fun acceptDetection(phrase: String, source: WakeWordEventSource) {
        val now = clock()
        val event = mutex.withLock {
            val phase = mutableState.value.phase
            val allowedPhase = phase == WakeWordPhase.ListeningForWakeWord ||
                (source == WakeWordEventSource.Simulation && phase in DEBUG_TRIGGER_PHASES)
            val withinCooldown = lastAcceptedDetectionAt != Long.MIN_VALUE &&
                now - lastAcceptedDetectionAt < DETECTION_COOLDOWN_MILLIS
            if (!requested || !allowedPhase || withinCooldown) {
                null
            } else {
                lastAcceptedDetectionAt = now
                val configuredPhrase = activeConfig.phrase
                mutableState.value = mutableState.value.copy(
                    phase = WakeWordPhase.WakeDetected,
                    phrase = configuredPhrase,
                    microphoneActive = false,
                    audioLevel = 0f,
                    lastWakeAtMillis = now,
                    errorMessage = null,
                )
                WakeWordEvent.Detected(
                    phrase = configuredPhrase.ifBlank { phrase },
                    detectedAtMillis = now,
                    source = source,
                )
            }
        } ?: return

        detector.stopSafely()
        Log.i(TAG, "WAKE DETECTED: ${event.phrase}")
        mutableEvents.emit(event)
    }

    private suspend fun handleDetectorError(error: WakeWordDetectorEvent.Error) {
        val shouldRetry = mutex.withLock {
            val prefix = if (error.kind == WakeWordErrorKind.Microphone) {
                "MICROPHONE ERROR"
            } else {
                "WAKE DETECTOR ERROR"
            }
            Log.e(TAG, "$prefix: ${error.message}")
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.Error,
                microphoneActive = false,
                audioLevel = 0f,
                errorMessage = error.message,
            )
            requested && error.recoverable
        }
        if (shouldRetry) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(retryDelayMillis)
                val stillRequested = mutex.withLock {
                    if (!requested || mutableState.value.phase != WakeWordPhase.Error) {
                        false
                    } else {
                        mutableState.value = mutableState.value.copy(
                            phase = WakeWordPhase.Rearming,
                            errorMessage = null,
                        )
                        true
                    }
                }
                if (stillRequested) {
                    detector.release()
                    armDetector(WakeWordPhase.Rearming)
                }
            }
        }
    }

    private suspend fun armDetector(expectedPhase: WakeWordPhase) {
        val config = mutex.withLock {
            if (!requested || mutableState.value.phase != expectedPhase) return
            activeConfig
        }
        runCatching { detector.start(config) }
            .onSuccess {
                mutex.withLock {
                    if (requested && mutableState.value.phase == expectedPhase) {
                        mutableState.value = mutableState.value.copy(
                            phase = WakeWordPhase.ListeningForWakeWord,
                            errorMessage = null,
                        )
                        Log.i(TAG, "WAKE DETECTOR ARMED")
                    }
                }
            }
            .onFailure { failure ->
                val detectorFailure = failure as? WakeWordDetectorException
                handleDetectorError(
                    WakeWordDetectorEvent.Error(
                        message = failure.message ?: "Wake detector could not start",
                        kind = detectorFailure?.kind ?: WakeWordErrorKind.Detector,
                        recoverable = detectorFailure?.recoverable ?: false,
                    ),
                )
            }
    }

    private suspend fun WakeWordDetector.stopSafely() {
        runCatching { stop() }
            .onFailure {
                Log.e(TAG, "MICROPHONE ERROR: stop failed", it)
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(
                        phase = WakeWordPhase.Error,
                        microphoneActive = false,
                        audioLevel = 0f,
                        errorMessage = it.message ?: "Microphone could not be stopped",
                    )
                }
            }
    }

    @Suppress("unused")
    private fun keepCollectorOwnedByProvider() = detectorEvents

    private companion object {
        const val TAG = "SwitchboardWakeWord"
        const val DETECTION_COOLDOWN_MILLIS = 1_500L
        const val DEFAULT_RETRY_DELAY_MILLIS = 1_000L
        val ARMING_PHASES = setOf(WakeWordPhase.Arming, WakeWordPhase.Rearming)
        val MIC_ACTIVATION_PHASES = ARMING_PHASES + WakeWordPhase.ListeningForWakeWord
        val DEBUG_TRIGGER_PHASES = setOf(
            WakeWordPhase.Arming,
            WakeWordPhase.ListeningForWakeWord,
            WakeWordPhase.Error,
        )
    }
}
