package com.switchboard.core.wakeword

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MockWakeWordProviderTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `simulated detection emits configured phrase`() = runTest {
        val provider = MockWakeWordProvider()
        provider.start(WakeWordConfig("Hello Switchboard"))
        val event = async { provider.events.first() }
        runCurrent()

        provider.simulateDetection()

        assertEquals("Hello Switchboard", (event.await() as WakeWordEvent.Detected).phrase)
    }
}
