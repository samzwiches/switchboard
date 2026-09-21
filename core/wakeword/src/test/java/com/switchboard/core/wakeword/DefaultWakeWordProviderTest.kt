package com.switchboard.core.wakeword

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultWakeWordProviderTest {
    @Test
    fun `detector callback and simulation share the wake event stream`() = runTest {
        var now = 10_000L
        val detector = FakeDetector()
        val provider = DefaultWakeWordProvider(detector, backgroundScope, clock = { now })
        val events = mutableListOf<WakeWordEvent.Detected>()
        backgroundScope.launch {
            provider.events.collect { events += it as WakeWordEvent.Detected }
        }

        provider.start(WakeWordConfig("Hey Lucas"))
        runCurrent()
        detector.detect("HEY LUCAS")
        runCurrent()

        provider.onAssistantSessionStarted()
        provider.onAssistantSessionFinished()
        now += 2_000L
        provider.simulateDetection()
        runCurrent()

        assertEquals(
            listOf(WakeWordEventSource.Detector, WakeWordEventSource.Simulation),
            events.map { it.source },
        )
        assertTrue(events.all { it.phrase == "Hey Lucas" })
    }

    @Test
    fun `duplicate detections are rejected by state guard`() = runTest {
        val detector = FakeDetector()
        val provider = DefaultWakeWordProvider(detector, backgroundScope, clock = { 42L })
        val events = mutableListOf<WakeWordEvent>()
        backgroundScope.launch { provider.events.collect(events::add) }
        provider.start(WakeWordConfig("Hey Lucas"))
        runCurrent()

        detector.detect("Hey Lucas")
        detector.detect("Hey Lucas")
        runCurrent()

        assertEquals(1, events.size)
        assertEquals(WakeWordPhase.WakeDetected, provider.state.value.phase)
    }

    @Test
    fun `assistant session pauses microphone and rearms afterward`() = runTest {
        val detector = FakeDetector()
        val provider = DefaultWakeWordProvider(detector, backgroundScope)
        provider.start(WakeWordConfig("Hey Lucas"))
        runCurrent()

        provider.onAssistantSessionStarted()
        assertEquals(WakeWordPhase.AssistantActive, provider.state.value.phase)
        assertFalse(provider.state.value.microphoneActive)

        provider.onAssistantSessionFinished()
        runCurrent()
        assertEquals(WakeWordPhase.ListeningForWakeWord, provider.state.value.phase)
        assertTrue(provider.state.value.microphoneActive)
        assertEquals(2, detector.startCalls)
        assertTrue(detector.stopCalls >= 1)
    }

    @Test
    fun `release stops resources and repeated start is idempotent`() = runTest {
        val detector = FakeDetector()
        val provider = DefaultWakeWordProvider(detector, backgroundScope)
        val config = WakeWordConfig("Hey Lucas")

        provider.start(config)
        provider.start(config)
        runCurrent()
        assertEquals(1, detector.startCalls)

        provider.release()
        assertEquals(1, detector.releaseCalls)
        assertEquals(WakeWordPhase.Stopped, provider.state.value.phase)
    }

    @Test
    fun `detector initialization failure becomes error state`() = runTest {
        val detector = FakeDetector().apply {
            startFailure = WakeWordDetectorException(
                message = "Model missing",
                kind = WakeWordErrorKind.Detector,
                recoverable = false,
            )
        }
        val provider = DefaultWakeWordProvider(detector, backgroundScope)

        provider.start(WakeWordConfig("Hey Lucas"))
        runCurrent()

        assertEquals(WakeWordPhase.Error, provider.state.value.phase)
        assertEquals("Model missing", provider.state.value.errorMessage)
        assertFalse(provider.state.value.microphoneActive)
    }
}

private class FakeDetector : WakeWordDetector {
    private val mutableEvents = MutableSharedFlow<WakeWordDetectorEvent>(extraBufferCapacity = 16)
    override val name = "Fake detector"
    override val events: Flow<WakeWordDetectorEvent> = mutableEvents
    var startCalls = 0
    var stopCalls = 0
    var releaseCalls = 0
    var startFailure: Throwable? = null

    override suspend fun start(config: WakeWordConfig) {
        startCalls++
        startFailure?.let { throw it }
        mutableEvents.emit(WakeWordDetectorEvent.MicrophoneActive)
    }

    override suspend fun stop() {
        stopCalls++
        mutableEvents.emit(WakeWordDetectorEvent.MicrophoneStopped)
    }

    override suspend fun release() {
        releaseCalls++
        stop()
    }

    suspend fun detect(phrase: String) {
        mutableEvents.emit(WakeWordDetectorEvent.Detected(phrase))
    }
}
