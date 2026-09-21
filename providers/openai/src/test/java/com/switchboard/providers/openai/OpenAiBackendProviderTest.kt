package com.switchboard.providers.openai

import com.switchboard.providers.api.AiProviderErrorKind
import com.switchboard.providers.api.AiProviderException
import com.switchboard.providers.api.AssistantRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAiBackendProviderTest {
    @Test
    fun `real backend provider can be selected and parses response text`() = runTest {
        val transport = RecordingTransport(BackendTransportResponse(200, "{\"text\":\"Hello from Lucas\"}"))
        val provider = OpenAiBackendProvider(config(), transport)

        val response = provider.createSession().send(AssistantRequest("Hello"))

        assertEquals(OPENAI_BACKEND_PROVIDER_ID, provider.descriptor.id)
        assertFalse(provider.descriptor.isMock)
        assertEquals("Hello from Lucas", response.text)
    }

    @Test
    fun `conversation history is bounded and sent before current message`() = runTest {
        val transport = RecordingTransport(
            BackendTransportResponse(200, "{\"text\":\"one\"}"),
            BackendTransportResponse(200, "{\"text\":\"two\"}"),
            BackendTransportResponse(200, "{\"text\":\"three\"}"),
        )
        val session = OpenAiBackendProvider(config(historyLimit = 2), transport).createSession()

        session.send(AssistantRequest("first"))
        session.send(AssistantRequest("second"))
        session.send(AssistantRequest("third"))

        val third = Json.parseToJsonElement(transport.requestBodies[2]).jsonObject
        val conversation = third.getValue("conversation").jsonArray
        assertEquals(2, conversation.size)
        assertEquals("user", conversation[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("second", conversation[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("assistant", conversation[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("two", conversation[1].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `missing backend URL returns controlled configuration error`() = runTest {
        val provider = OpenAiBackendProvider(OpenAiBackendConfig(""), RecordingTransport())

        val error = runCatching { provider.createSession().send(AssistantRequest("Hello")) }.exceptionOrNull()

        assertTrue(error is AiProviderException)
        assertEquals(AiProviderErrorKind.Configuration, (error as AiProviderException).kind)
        assertEquals("OpenAI backend URL is not configured.", error.userMessage)
    }

    @Test
    fun `HTTP rate limit maps to provider rate limit error`() = runTest {
        val transport = RecordingTransport(
            BackendTransportResponse(
                429,
                "{\"error\":{\"code\":\"rate_limited\",\"message\":\"Try again shortly.\"}}",
            ),
        )

        val error = runCatching {
            OpenAiBackendProvider(config(), transport).createSession().send(AssistantRequest("Hello"))
        }.exceptionOrNull() as AiProviderException

        assertEquals(AiProviderErrorKind.RateLimit, error.kind)
        assertEquals("The AI service is busy. Try again shortly.", error.userMessage)
    }

    @Test
    fun `malformed backend response is rejected safely`() = runTest {
        val transport = RecordingTransport(BackendTransportResponse(200, "not-json"))

        val error = runCatching {
            OpenAiBackendProvider(config(), transport).createSession().send(AssistantRequest("Hello"))
        }.exceptionOrNull() as AiProviderException

        assertEquals(AiProviderErrorKind.MalformedResponse, error.kind)
    }

    @Test
    fun `network failure maps to provider network error`() = runTest {
        val transport = object : BackendTransport {
            override suspend fun postJson(url: String, body: String): BackendTransportResponse {
                throw IOException("offline")
            }
        }

        val error = runCatching {
            OpenAiBackendProvider(config(), transport).createSession().send(AssistantRequest("Hello"))
        }.exceptionOrNull() as AiProviderException

        assertEquals(AiProviderErrorKind.Network, error.kind)
        assertEquals("Lucas couldn’t reach the AI service.", error.userMessage)
    }

    @Test
    fun `short real HTTP response completes instead of requiring full size buffer`() = runTest {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/assistant") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = "{\"text\":\"Hello over HTTP\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val provider = OpenAiBackendProvider(OpenAiBackendConfig("http://127.0.0.1:${server.address.port}"))
            assertEquals("Hello over HTTP", provider.createSession().send(AssistantRequest("Hello")).text)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `malformed text types never escape as raw parser errors`() = runTest {
        for (body in listOf("{\"text\":{}}", "{\"text\":[]}", "{\"text\":123}", "{\"text\":null}")) {
            val error = runCatching {
                OpenAiBackendProvider(config(), RecordingTransport(BackendTransportResponse(200, body)))
                    .createSession().send(AssistantRequest("Hello"))
            }.exceptionOrNull() as AiProviderException
            assertEquals(AiProviderErrorKind.MalformedResponse, error.kind)
        }
    }

    @Test
    fun `server internals are never shown as error messages`() = runTest {
        val error = runCatching {
            OpenAiBackendProvider(config(), RecordingTransport(
                BackendTransportResponse(500, "{\"error\":{\"message\":\"internal secret traceback\"}}"),
            )).createSession().send(AssistantRequest("Hello"))
        }.exceptionOrNull() as AiProviderException
        assertEquals("Lucas couldn’t reach the AI service.", error.userMessage)
    }

    @Test
    fun `history stays within character budget and begins with a user turn`() = runTest {
        val transport = RecordingTransport()
        val session = OpenAiBackendProvider(config(historyLimit = 5), transport).createSession()
        repeat(7) { session.send(AssistantRequest("x".repeat(4000))) }
        val history = Json.parseToJsonElement(transport.requestBodies.last()).jsonObject
            .getValue("conversation").jsonArray
        assertTrue(history.size <= 5)
        assertTrue(history.sumOf { it.jsonObject.getValue("text").jsonPrimitive.content.length } <= 12000)
        assertEquals("user", history.first().jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `new session starts empty after conversation is cleared`() = runTest {
        val transport = RecordingTransport()
        val provider = OpenAiBackendProvider(config(), transport)
        val first = provider.createSession()
        first.send(AssistantRequest("Remember this"))
        first.close()
        provider.createSession().send(AssistantRequest("Fresh start"))
        assertEquals(0, Json.parseToJsonElement(transport.requestBodies.last()).jsonObject
            .getValue("conversation").jsonArray.size)
    }

    private fun config(historyLimit: Int = 10) = OpenAiBackendConfig(
        baseUrl = "https://switchboard.example",
        conversationHistoryLimit = historyLimit,
    )
}

private class RecordingTransport(
    vararg responses: BackendTransportResponse,
) : BackendTransport {
    private val responses = ArrayDeque(responses.toList())
    val requestBodies = mutableListOf<String>()

    override suspend fun postJson(url: String, body: String): BackendTransportResponse {
        requestBodies += body
        return responses.removeFirstOrNull() ?: BackendTransportResponse(200, "{\"text\":\"ok\"}")
    }
}
