package com.switchboard.providers.openai

import com.switchboard.providers.api.AssistantRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockOpenAiProviderTest {
    @Test
    fun `provider is offline mock and creates a response`() = runTest {
        val provider = MockOpenAiProvider()
        val session = provider.createSession()

        val response = session.send(AssistantRequest("hello"))

        assertTrue(provider.descriptor.isMock)
        assertEquals("mock-openai", session.providerId)
        assertEquals("Mock response: I heard “hello”.", response.text)
    }

    @Test
    fun `structured action channel is preserved`() = runTest {
        val session = MockOpenAiProvider().createSession()

        val response = session.send(AssistantRequest("run mock action"))

        assertEquals("mock.echo", response.actions.single().name)
    }
}

