package com.switchboard.core.assistant

import android.util.Log
import com.switchboard.core.actions.ActionResult
import com.switchboard.core.actions.ActionRouter
import com.switchboard.core.wakeword.DEFAULT_WAKE_PHRASE
import com.switchboard.core.wakeword.WakeWordConfig
import com.switchboard.core.wakeword.WakeWordEvent
import com.switchboard.core.wakeword.WakeWordProvider
import com.switchboard.providers.api.AiProvider
import com.switchboard.providers.api.AiProviderException
import com.switchboard.providers.api.AssistantRequest
import com.switchboard.providers.api.AssistantSession
import com.switchboard.voice.api.VoiceProvider
import com.switchboard.voice.speech.api.SpeechInputConfig
import com.switchboard.voice.speech.api.SpeechInputEngine
import com.switchboard.voice.speech.api.SpeechInputResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AssistantController(
    private val providerResolver: () -> AiProvider,
    private val wakeWordProvider: WakeWordProvider,
    private val actionRouter: ActionRouter,
    private val voiceProvider: VoiceProvider,
    private val speechInputEngine: SpeechInputEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val interactionMutex = Mutex()
    private val mutableState = MutableStateFlow(
        AssistantUiState(providerName = providerResolver().descriptor.displayName),
    )
    private var session: AssistantSession? = null
    private var activeInteraction: Job? = null
    private val eventCollection: Job = collectWakeWordEvents()

    val state: StateFlow<AssistantUiState> = mutableState.asStateFlow()

    suspend fun startListening(phrase: String) {
        val normalized = phrase.trim().ifBlank { DEFAULT_WAKE_PHRASE }
        wakeWordProvider.start(WakeWordConfig(normalized))
        mutableState.update { current ->
            current.copy(
                status = waitingStatus(normalized),
                wakePhrase = normalized,
                providerName = selectedProvider().descriptor.displayName,
            )
        }
    }

    suspend fun stopListening() {
        wakeWordProvider.release()
        cancelActiveInteraction()
        speechInputEngine.stop()
        voiceProvider.stop()
        interactionMutex.withLock { closeSession() }
        mutableState.value = AssistantUiState(providerName = selectedProvider().descriptor.displayName)
    }

    suspend fun simulateWakeWord() {
        wakeWordProvider.simulateDetection()
    }

    fun sendText(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        launchInteraction("typed message") {
            runAssistantInteraction {
                processMessage(normalized)
            }
        }
    }

    suspend fun dismissConversation() {
        cancelActiveInteraction()
        speechInputEngine.stop()
        voiceProvider.stop()
        interactionMutex.withLock { closeSession() }
        wakeWordProvider.onAssistantSessionFinished()
        mutableState.value = AssistantUiState(
            status = waitingStatus(mutableState.value.wakePhrase ?: DEFAULT_WAKE_PHRASE),
            providerName = selectedProvider().descriptor.displayName,
            wakePhrase = mutableState.value.wakePhrase,
        )
    }

    suspend fun onProviderChanged() {
        cancelActiveInteraction()
        speechInputEngine.stop()
        voiceProvider.stop()
        interactionMutex.withLock { closeSession() }
        wakeWordProvider.onAssistantSessionFinished()
        mutableState.value = AssistantUiState(
            status = waitingStatus(mutableState.value.wakePhrase ?: DEFAULT_WAKE_PHRASE),
            providerName = selectedProvider().descriptor.displayName,
            wakePhrase = mutableState.value.wakePhrase,
        )
    }

    fun close() {
        eventCollection.cancel()
        synchronized(this) {
            activeInteraction?.cancel()
            activeInteraction = null
        }
        speechInputEngine.close()
        voiceProvider.close()
    }

    private fun collectWakeWordEvents(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        wakeWordProvider.events.collect { event ->
            when (event) {
                is WakeWordEvent.Detected -> launchInteraction("wake event") {
                    handleWakeWord(event)
                }
            }
        }
    }

    private suspend fun handleWakeWord(event: WakeWordEvent.Detected) {
        runAssistantInteraction {
            voiceProvider.stop()
            mutableState.value = mutableState.value.copy(
                phase = AssistantPhase.Listening,
                error = null,
                status = "Lucas is listening…",
                providerName = selectedProvider().descriptor.displayName,
                wakePhrase = event.phrase,
            )
            Log.i(TAG, "SPEECH LISTENING STARTED")

            when (val result = speechInputEngine.listen(SpeechInputConfig())) {
                is SpeechInputResult.Transcript -> {
                    mutableState.update { it.copy(lastSpeechAtMillis = clock()) }
                    processMessage(result.text)
                }
                is SpeechInputResult.NoSpeech -> showRecoverableError(result.reason)
                is SpeechInputResult.Failed -> showRecoverableError(result.reason)
                SpeechInputResult.Cancelled -> Unit
            }
        }
    }

    private suspend fun runAssistantInteraction(block: suspend () -> Unit) {
        interactionMutex.withLock {
            wakeWordProvider.onAssistantSessionStarted()
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showRecoverableError("Lucas couldn’t complete that request. Please try again.")
            } finally {
                speechInputEngine.stop()
                wakeWordProvider.onAssistantSessionFinished()
                mutableState.update { current ->
                    if (current.phase == AssistantPhase.Error) current.copy(
                        phase = AssistantPhase.Idle,
                        status = waitingStatus(current.wakePhrase ?: DEFAULT_WAKE_PHRASE),
                    ) else current
                }
                Log.i(TAG, "WAKE DETECTOR REARMED")
            }
        }
    }

    private suspend fun processMessage(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        mutableState.update { current ->
            current.copy(
                phase = AssistantPhase.Thinking,
                status = "Lucas is thinking…",
                messages = current.messages + AssistantMessage(MessageAuthor.User, normalized),
                error = null,
            )
        }

        try {
            val activeSession = session ?: createSession()
            val response = activeSession.send(AssistantRequest(normalized))
            val actionResults = actionRouter.route(response.actions)
            mutableState.update { current ->
                current.copy(
                    phase = AssistantPhase.Speaking,
                    status = "Lucas is speaking…",
                    messages = current.messages + AssistantMessage(MessageAuthor.Assistant, response.text),
                    actionResults = current.actionResults + actionResults,
                    lastProviderResultAtMillis = clock(),
                )
            }

            Log.i(TAG, "TTS STARTED")
            voiceProvider.speak(response.text).getOrElse { error ->
                throw IllegalStateException(error.message ?: "Voice playback failed", error)
            }
            Log.i(TAG, "TTS FINISHED")
            mutableState.update { current ->
                current.copy(
                    phase = AssistantPhase.Idle,
                    status = waitingStatus(current.wakePhrase ?: DEFAULT_WAKE_PHRASE),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Assistant request failed: ${error::class.java.simpleName}")
            val message = (error as? AiProviderException)?.userMessage
                ?: "Lucas couldn’t complete that request. Please try again."
            showRecoverableError(message)
        }
    }

    private suspend fun createSession(): AssistantSession {
        val provider = selectedProvider()
        val created = provider.createSession()
        session = created
        mutableState.update { it.copy(providerName = provider.descriptor.displayName) }
        Log.i(TAG, "ASSISTANT SESSION STARTED: ${provider.descriptor.id}")
        return created
    }

    private suspend fun closeSession() {
        session?.close()
        session = null
    }

    private fun showRecoverableError(message: String) {
        mutableState.update {
            it.copy(
                phase = AssistantPhase.Error,
                status = "Lucas hit a snag",
                error = message,
            )
        }
    }

    @Synchronized
    private fun launchInteraction(label: String, block: suspend () -> Unit) {
        if (activeInteraction?.isActive == true) {
            Log.i(TAG, "Ignored duplicate interaction while active: $label")
            return
        }
        lateinit var launched: Job
        launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showRecoverableError("Lucas couldn’t complete that request. Please try again.")
            } finally {
                synchronized(this@AssistantController) {
                    if (activeInteraction === launched) activeInteraction = null
                }
            }
        }
        activeInteraction = launched
        launched.start()
    }

    private suspend fun cancelActiveInteraction() {
        val job = synchronized(this) {
            activeInteraction.also { activeInteraction = null }
        }
        job?.cancelAndJoin()
    }

    private fun selectedProvider(): AiProvider = providerResolver()

    private fun waitingStatus(phrase: String): String = "Waiting for “$phrase”"

    private companion object {
        const val TAG = "SwitchboardAssistant"
    }
}

data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.Idle,
    val status: String = "Ready when you are",
    val providerName: String,
    val wakePhrase: String? = null,
    val messages: List<AssistantMessage> = emptyList(),
    val actionResults: List<ActionResult> = emptyList(),
    val error: String? = null,
    val lastSpeechAtMillis: Long? = null,
    val lastProviderResultAtMillis: Long? = null,
)

enum class AssistantPhase {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Error,
}

data class AssistantMessage(
    val author: MessageAuthor,
    val text: String,
)

enum class MessageAuthor { User, Assistant }
