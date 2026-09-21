package com.switchboard.core.assistant

import com.switchboard.core.actions.ActionResult
import com.switchboard.core.actions.ActionRouter
import com.switchboard.core.wakeword.WakeWordConfig
import com.switchboard.core.wakeword.WakeWordEvent
import com.switchboard.core.wakeword.WakeWordEventSource
import com.switchboard.core.wakeword.WakeWordPhase
import com.switchboard.core.wakeword.WakeWordProvider
import com.switchboard.core.wakeword.WakeWordState
import com.switchboard.providers.api.AiProvider
import com.switchboard.providers.api.AiProviderDescriptor
import com.switchboard.providers.api.AiProviderErrorKind
import com.switchboard.providers.api.AiProviderException
import com.switchboard.providers.api.AssistantContext
import com.switchboard.providers.api.AssistantRequest
import com.switchboard.providers.api.AssistantResponse
import com.switchboard.providers.api.AssistantSession
import com.switchboard.providers.api.StructuredAction
import com.switchboard.voice.api.VoiceProvider
import com.switchboard.voice.api.VoiceProviderDescriptor
import com.switchboard.voice.api.VoiceState
import com.switchboard.voice.speech.api.SpeechInputConfig
import com.switchboard.voice.speech.api.SpeechInputDescriptor
import com.switchboard.voice.speech.api.SpeechInputEngine
import com.switchboard.voice.speech.api.SpeechInputResult
import com.switchboard.voice.speech.api.SpeechInputState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantControllerTest {
    @Test
    fun `typed input uses the selected provider and TTS pipeline`() = runTest {
        val provider = RecordingAiProvider("real", "OpenAI", "Real response")
        val fixture = fixture(provider)
        fixture.controller.startListening("Hey Lucas")

        fixture.controller.sendText("typed question")
        advanceUntilIdle()

        assertEquals(listOf("typed question"), provider.received)
        assertEquals(listOf("Real response"), fixture.voice.spoken)
        assertEquals(AssistantPhase.Idle, fixture.controller.state.value.phase)
        fixture.controller.close()
    }

    @Test
    fun `spoken transcript uses the same provider message pipeline as typed input`() = runTest {
        val provider = RecordingAiProvider("real", "OpenAI", "Spoken response")
        val fixture = fixture(
            provider,
            speechResults = listOf(SpeechInputResult.Transcript("spoken question")),
        )
        fixture.controller.startListening("Hey Lucas")

        fixture.wakeWord.emitWake()
        advanceUntilIdle()

        assertEquals(listOf("spoken question"), provider.received)
        assertEquals(
            listOf(MessageAuthor.User, MessageAuthor.Assistant),
            fixture.controller.state.value.messages.map(AssistantMessage::author),
        )
        assertEquals("spoken question", fixture.controller.state.value.messages.first().text)
        fixture.controller.close()
    }

    @Test
    fun `wake detector is paused before speech input begins`() = runTest {
        val fixture = fixture(
            RecordingAiProvider(),
            speechResults = listOf(SpeechInputResult.NoSpeech("none")),
        )
        fixture.speech.onListen = {
            assertEquals(WakeWordPhase.AssistantActive, fixture.wakeWord.state.value.phase)
            assertFalse(fixture.wakeWord.state.value.microphoneActive)
        }
        fixture.controller.startListening("Hey Lucas")

        fixture.wakeWord.emitWake()
        advanceUntilIdle()

        assertEquals(1, fixture.wakeWord.assistantStartedCalls)
        fixture.controller.close()
    }

    @Test
    fun `TTS starts only after speech recognition has stopped`() = runTest {
        val fixture = fixture(
            RecordingAiProvider(),
            speechResults = listOf(SpeechInputResult.Transcript("question")),
        )
        fixture.voice.onSpeak = {
            assertFalse(fixture.speech.listening)
            assertEquals(WakeWordPhase.AssistantActive, fixture.wakeWord.state.value.phase)
        }
        fixture.controller.startListening("Hey Lucas")

        fixture.wakeWord.emitWake()
        advanceUntilIdle()

        assertEquals(1, fixture.voice.spoken.size)
        fixture.controller.close()
    }

    @Test
    fun `spoken flow transitions Listening Thinking Speaking then Idle`() = runTest {
        val fixture = fixture(
            RecordingAiProvider(),
            speechResults = listOf(SpeechInputResult.Transcript("question")),
        )
        val phases = mutableListOf<AssistantPhase>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.controller.state.collect { phases += it.phase }
        }
        fixture.controller.startListening("Hey Lucas")

        fixture.wakeWord.emitWake()
        advanceUntilIdle()

        assertContainsInOrder(
            phases,
            listOf(
                AssistantPhase.Listening,
                AssistantPhase.Thinking,
                AssistantPhase.Speaking,
                AssistantPhase.Idle,
            ),
        )
        collection.cancel()
        fixture.controller.close()
    }

    @Test
    fun `provider failure is shown safely and wake detector rearms`() = runTest {
        val provider = RecordingAiProvider(
            failure = AiProviderException(
                AiProviderErrorKind.Network,
                "Lucas couldn’t reach the AI service.",
            ),
        )
        val fixture = fixture(
            provider,
            speechResults = listOf(SpeechInputResult.Transcript("question")),
        )
        fixture.controller.startListening("Hey Lucas")

        fixture.wakeWord.emitWake()
        advanceUntilIdle()

        assertEquals(AssistantPhase.Idle, fixture.controller.state.value.phase)
        assertEquals("Lucas couldn’t reach the AI service.", fixture.controller.state.value.error)
        assertEquals(WakeWordPhase.ListeningForWakeWord, fixture.wakeWord.state.value.phase)
        fixture.controller.close()
    }

    @Test
    fun `provider switching changes the next session without corrupting the old one`() = runTest {
        val mock = RecordingAiProvider("mock", "Mock", "Mock response")
        val real = RecordingAiProvider("real", "OpenAI", "Real response")
        var selected: AiProvider = mock
        val fixture = fixture(mock, providerResolver = { selected })
        fixture.controller.startListening("Hey Lucas")
        fixture.controller.sendText("first")
        advanceUntilIdle()

        selected = real
        fixture.controller.onProviderChanged()
        fixture.controller.sendText("second")
        advanceUntilIdle()

        assertEquals(listOf("first"), mock.received)
        assertEquals(listOf("second"), real.received)
        assertEquals("OpenAI", fixture.controller.state.value.providerName)
        fixture.controller.close()
    }

    @Test
    fun `duplicate wake events do not create multiple sessions`() = runTest {
        val pendingSpeech = CompletableDeferred<SpeechInputResult>()
        val speech = FakeSpeechInputEngine(blockingResult = pendingSpeech)
        val provider = RecordingAiProvider()
        val fixture = fixture(provider, speech = speech)
        fixture.controller.startListening("Hey Lucas")

        fixture.wakeWord.emitWake()
        runCurrent()
        fixture.wakeWord.emitWake()
        runCurrent()

        assertEquals(1, speech.listenCalls)
        assertEquals(0, provider.createSessionCalls)
        pendingSpeech.complete(SpeechInputResult.NoSpeech("none"))
        advanceUntilIdle()
        assertEquals(1, speech.listenCalls)
        fixture.controller.close()
    }

    @Test
    fun `stopping cancels active speech and leaves wake detector stopped`() = runTest {
        val pendingSpeech = CompletableDeferred<SpeechInputResult>()
        val speech = FakeSpeechInputEngine(blockingResult = pendingSpeech)
        val fixture = fixture(RecordingAiProvider(), speech = speech)
        fixture.controller.startListening("Hey Lucas")
        fixture.wakeWord.emitWake()
        runCurrent()

        fixture.controller.stopListening()
        advanceUntilIdle()

        assertTrue(speech.stopCalls > 0)
        assertEquals(WakeWordPhase.Stopped, fixture.wakeWord.state.value.phase)
        assertEquals(AssistantPhase.Idle, fixture.controller.state.value.phase)
        fixture.controller.close()
    }

    @Test
    fun `assistant remains Idle while waiting for the wake phrase`() = runTest {
        val fixture = fixture(RecordingAiProvider())

        fixture.controller.startListening("Hey Lucas")
        runCurrent()

        assertEquals(AssistantPhase.Idle, fixture.controller.state.value.phase)
        assertEquals(WakeWordPhase.ListeningForWakeWord, fixture.wakeWord.state.value.phase)
        fixture.controller.close()
    }

    @Test
    fun `session creation failure is contained and returns idle`() = runTest {
        val broken = object : AiProvider {
            override val descriptor = AiProviderDescriptor("broken", "Broken", false)
            override suspend fun createSession(context: AssistantContext): AssistantSession {
                error("internal server details")
            }
        }
        val fixture = fixture(RecordingAiProvider(), providerResolver = { broken })
        fixture.controller.startListening("Hey Lucas")
        fixture.controller.sendText("Hello")
        advanceUntilIdle()
        assertEquals(AssistantPhase.Idle, fixture.controller.state.value.phase)
        assertEquals("Lucas couldn’t complete that request. Please try again.", fixture.controller.state.value.error)
        assertEquals(WakeWordPhase.ListeningForWakeWord, fixture.wakeWord.state.value.phase)
        fixture.controller.close()
    }

    @Test
    fun `successive spoken turns retain displayed conversation until cleared`() = runTest {
        val provider = RecordingAiProvider()
        val fixture = fixture(provider, speechResults = listOf(
            SpeechInputResult.Transcript("first"), SpeechInputResult.Transcript("second"),
        ))
        fixture.controller.startListening("Hey Lucas")
        fixture.wakeWord.emitWake()
        advanceUntilIdle()
        fixture.wakeWord.emitWake()
        advanceUntilIdle()
        assertEquals(4, fixture.controller.state.value.messages.size)
        assertEquals(1, provider.createSessionCalls)
        fixture.controller.dismissConversation()
        assertTrue(fixture.controller.state.value.messages.isEmpty())
        fixture.controller.sendText("new conversation")
        advanceUntilIdle()
        assertEquals(2, provider.createSessionCalls)
        fixture.controller.close()
    }

    private fun TestScope.fixture(
        provider: RecordingAiProvider,
        speechResults: List<SpeechInputResult> = emptyList(),
        speech: FakeSpeechInputEngine = FakeSpeechInputEngine(speechResults),
        providerResolver: () -> AiProvider = { provider },
    ): Fixture {
        val wakeWord = RecordingWakeWordProvider()
        val voice = FakeVoiceProvider()
        val controller = AssistantController(
            providerResolver = providerResolver,
            wakeWordProvider = wakeWord,
            actionRouter = FakeActionRouter(),
            voiceProvider = voice,
            speechInputEngine = speech,
            scope = this,
        )
        return Fixture(controller, wakeWord, speech, voice)
    }
}

private data class Fixture(
    val controller: AssistantController,
    val wakeWord: RecordingWakeWordProvider,
    val speech: FakeSpeechInputEngine,
    val voice: FakeVoiceProvider,
)

private class RecordingWakeWordProvider : WakeWordProvider {
    private val mutableEvents = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 2)
    private val mutableState = MutableStateFlow(WakeWordState(detectorName = "Test detector"))
    override val events: Flow<WakeWordEvent> = mutableEvents
    override val state: StateFlow<WakeWordState> = mutableState
    var assistantStartedCalls = 0
    var assistantFinishedCalls = 0
    private var requested = false

    override suspend fun start(config: WakeWordConfig) {
        requested = true
        mutableState.value = mutableState.value.copy(
            phase = WakeWordPhase.ListeningForWakeWord,
            phrase = config.phrase,
            microphoneActive = true,
        )
    }

    override suspend fun stop() {
        requested = false
        mutableState.value = mutableState.value.copy(phase = WakeWordPhase.Stopped, microphoneActive = false)
    }

    override suspend fun release() = stop()

    override suspend fun simulateDetection() = emitWake()

    override suspend fun onAssistantSessionStarted() {
        if (!requested) return
        assistantStartedCalls++
        mutableState.value = mutableState.value.copy(
            phase = WakeWordPhase.AssistantActive,
            microphoneActive = false,
        )
    }

    override suspend fun onAssistantSessionFinished() {
        assistantFinishedCalls++
        if (requested) {
            mutableState.value = mutableState.value.copy(
                phase = WakeWordPhase.ListeningForWakeWord,
                microphoneActive = true,
            )
        }
    }

    suspend fun emitWake() {
        mutableEvents.emit(
            WakeWordEvent.Detected(
                phrase = "Hey Lucas",
                detectedAtMillis = 1L,
                source = WakeWordEventSource.Detector,
            ),
        )
    }
}

private class RecordingAiProvider(
    id: String = "fake",
    name: String = "Fake provider",
    private val response: String = "Lucas response",
    private val failure: Throwable? = null,
) : AiProvider {
    override val descriptor = AiProviderDescriptor(id, name, id == "mock")
    val received = mutableListOf<String>()
    var createSessionCalls = 0

    override suspend fun createSession(context: AssistantContext): AssistantSession {
        createSessionCalls++
        return object : AssistantSession {
            override val id = "session-$createSessionCalls"
            override val providerId = descriptor.id
            override suspend fun send(request: AssistantRequest): AssistantResponse {
                received += request.text
                failure?.let { throw it }
                return AssistantResponse(response)
            }

            override suspend fun close() = Unit
        }
    }
}

private class FakeSpeechInputEngine(
    results: List<SpeechInputResult> = emptyList(),
    private val blockingResult: CompletableDeferred<SpeechInputResult>? = null,
) : SpeechInputEngine {
    private val results = ArrayDeque(results)
    private val mutableState = MutableStateFlow<SpeechInputState>(SpeechInputState.Idle)
    override val state: StateFlow<SpeechInputState> = mutableState
    override val descriptor = SpeechInputDescriptor("fake", "Fake speech", true)
    var onListen: () -> Unit = {}
    var listenCalls = 0
    var stopCalls = 0
    var listening = false

    override suspend fun listen(config: SpeechInputConfig): SpeechInputResult {
        listenCalls++
        listening = true
        mutableState.value = SpeechInputState.Listening
        onListen()
        return try {
            blockingResult?.await() ?: results.removeFirstOrNull()
                ?: SpeechInputResult.NoSpeech("none")
        } finally {
            listening = false
            mutableState.value = SpeechInputState.Idle
        }
    }

    override fun stop() {
        stopCalls++
        listening = false
        mutableState.value = SpeechInputState.Idle
    }

    override fun close() = stop()
}

private class FakeActionRouter : ActionRouter {
    override suspend fun route(actions: List<StructuredAction>): List<ActionResult> = emptyList()
}

private class FakeVoiceProvider : VoiceProvider {
    override val descriptor = VoiceProviderDescriptor("fake", "Fake voice")
    override val state: StateFlow<VoiceState> = MutableStateFlow(VoiceState.Ready)
    val spoken = mutableListOf<String>()
    var onSpeak: () -> Unit = {}
    override suspend fun initialize(): Result<Unit> = Result.success(Unit)
    override suspend fun speak(text: String): Result<Unit> = Result.success(Unit).also {
        onSpeak()
        spoken += text
    }
    override fun stop() = Unit
    override fun close() = Unit
}

private fun assertContainsInOrder(actual: List<AssistantPhase>, expected: List<AssistantPhase>) {
    var index = 0
    actual.forEach { if (index < expected.size && it == expected[index]) index++ }
    assertEquals("Expected $expected in $actual", expected.size, index)
}
