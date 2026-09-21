package com.switchboard.providers.openai

import android.util.Log
import com.switchboard.providers.api.AiProvider
import com.switchboard.providers.api.AiProviderDescriptor
import com.switchboard.providers.api.AssistantContext
import com.switchboard.providers.api.AssistantRequest
import com.switchboard.providers.api.AssistantResponse
import com.switchboard.providers.api.AssistantSession
import com.switchboard.providers.api.StructuredAction
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * An offline fake carrying an OpenAI-facing label for the current provider picker.
 * It contains no OpenAI client, endpoint, model identifier, or credential.
 */
class MockOpenAiProvider : AiProvider {
    override val descriptor = AiProviderDescriptor(
        id = MOCK_OPENAI_PROVIDER_ID,
        displayName = "OpenAI (mock)",
        isMock = true,
    )

    override suspend fun createSession(context: AssistantContext): AssistantSession =
        MockOpenAiSession(providerId = descriptor.id)
}

private class MockOpenAiSession(
    override val providerId: String,
) : AssistantSession {
    override val id: String = UUID.randomUUID().toString()
    private var closed = false

    override suspend fun send(request: AssistantRequest): AssistantResponse {
        check(!closed) { "Assistant session is closed" }
        delay(250)

        val prompt = request.text.trim()
        val action = if (prompt.equals("run mock action", ignoreCase = true)) {
            listOf(StructuredAction(name = "mock.echo", parameters = mapOf("value" to prompt)))
        } else {
            emptyList()
        }
        val text = when {
            prompt == WAKE_EVENT_PROMPT -> "Hi, I’m Lucas. Switchboard’s mock assistant is ready."
            prompt.isBlank() -> "I’m listening."
            else -> "Mock response: I heard “$prompt”."
        }

        Log.d(TAG, "Mock provider returned a local response")
        return AssistantResponse(text = text, actions = action)
    }

    override suspend fun close() {
        closed = true
    }

    private companion object {
        const val TAG = "SwitchboardProvider"
        const val WAKE_EVENT_PROMPT = "__switchboard_wake_event__"
    }
}

const val MOCK_WAKE_EVENT_PROMPT: String = "__switchboard_wake_event__"
const val MOCK_OPENAI_PROVIDER_ID: String = "mock-openai"
