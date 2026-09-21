package com.switchboard.providers.api

/** A replaceable model backend. Implementations own transport and authentication details. */
interface AiProvider {
    val descriptor: AiProviderDescriptor

    suspend fun createSession(context: AssistantContext = AssistantContext()): AssistantSession
}

data class AiProviderDescriptor(
    val id: String,
    val displayName: String,
    val isMock: Boolean,
)

data class AssistantContext(
    val localeTag: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** A conversation created by an [AiProvider]. */
interface AssistantSession {
    val id: String
    val providerId: String

    suspend fun send(request: AssistantRequest): AssistantResponse
    suspend fun close()
}

data class AssistantRequest(
    val text: String,
)

/**
 * Text remains the current output, while actions provide a provider-neutral structured channel.
 */
data class AssistantResponse(
    val text: String,
    val actions: List<StructuredAction> = emptyList(),
)

data class StructuredAction(
    val name: String,
    val parameters: Map<String, String> = emptyMap(),
)

enum class AiProviderErrorKind {
    Configuration,
    Authentication,
    RateLimit,
    Network,
    Timeout,
    MalformedResponse,
    Unavailable,
}

class AiProviderException(
    val kind: AiProviderErrorKind,
    val userMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(userMessage, cause)
