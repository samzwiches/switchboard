package com.switchboard.providers.openai

import android.util.Log
import com.switchboard.providers.api.AiProvider
import com.switchboard.providers.api.AiProviderDescriptor
import com.switchboard.providers.api.AiProviderErrorKind
import com.switchboard.providers.api.AiProviderException
import com.switchboard.providers.api.AssistantContext
import com.switchboard.providers.api.AssistantRequest
import com.switchboard.providers.api.AssistantResponse
import com.switchboard.providers.api.AssistantSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OpenAiBackendConfig(
    val baseUrl: String,
    val configurationErrorMessage: String = "OpenAI backend URL is not configured.",
    val conversationHistoryLimit: Int = 10,
    val connectTimeoutMillis: Long = 10_000L,
    val readTimeoutMillis: Long = 35_000L,
    val callTimeoutMillis: Long = 40_000L,
) {
    val isConfigured: Boolean
        get() = baseUrl.trim().toHttpUrlOrNull() != null
}

class OpenAiBackendProvider(
    val config: OpenAiBackendConfig,
    private val transport: BackendTransport = OkHttpBackendTransport(config),
) : AiProvider {
    override val descriptor = AiProviderDescriptor(
        id = OPENAI_BACKEND_PROVIDER_ID,
        displayName = "OpenAI",
        isMock = false,
    )

    override suspend fun createSession(context: AssistantContext): AssistantSession =
        OpenAiBackendSession(config, transport)
}

interface BackendTransport {
    suspend fun postJson(url: String, body: String): BackendTransportResponse
}

data class BackendTransportResponse(
    val statusCode: Int,
    val body: String,
)

private class OkHttpBackendTransport(config: OpenAiBackendConfig) : BackendTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun postJson(url: String, body: String): BackendTransportResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!continuation.isActive) return
                            val responseBody = it.body
                            val contentLength = responseBody.contentLength()
                            if (contentLength > MAX_RESPONSE_BYTES) {
                                continuation.resumeWithException(IOException("Backend response was too large"))
                                return
                            }
                            val source = responseBody.source()
                            source.request(MAX_RESPONSE_BYTES + 1L)
                            val bytes = source.buffer.readByteArray()
                            if (bytes.size > MAX_RESPONSE_BYTES) {
                                continuation.resumeWithException(IOException("Backend response was too large"))
                                return
                            }
                            continuation.resume(
                                BackendTransportResponse(
                                    statusCode = it.code,
                                    body = bytes.decodeToString(),
                                ),
                            )
                        }
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_BYTES = 65_536
    }
}

private class OpenAiBackendSession(
    private val config: OpenAiBackendConfig,
    private val transport: BackendTransport,
) : AssistantSession {
    override val id: String = UUID.randomUUID().toString()
    override val providerId: String = OPENAI_BACKEND_PROVIDER_ID

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val history = ArrayDeque<ConversationItem>()
    private var closed = false

    override suspend fun send(request: AssistantRequest): AssistantResponse = mutex.withLock {
        check(!closed) { "Assistant session is closed" }
        val message = request.text.trim()
        require(message.isNotEmpty()) { "Assistant message cannot be blank" }
        val endpoint = endpointUrl()
        val requestBody = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("conversation", buildJsonArray {
                history.forEach { item ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(item.role))
                        put("text", JsonPrimitive(item.text))
                    })
                }
            })
        }.toString()

        Log.i(TAG, "OPENAI REQUEST STARTED: ${message.length} input characters")
        try {
            val response = transport.postJson(endpoint, requestBody)
            if (response.statusCode !in 200..299) throw mapHttpError(response)
            val assistantText = parseAssistantText(response.body)
            history.addLast(ConversationItem("user", message))
            history.addLast(ConversationItem("assistant", assistantText))
            trimHistory()
            Log.i(TAG, "OPENAI RESPONSE RECEIVED: ${assistantText.length} output characters")
            AssistantResponse(text = assistantText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (providerError: AiProviderException) {
            Log.w(TAG, "OPENAI REQUEST FAILED: ${providerError.kind}")
            throw providerError
        } catch (timeout: InterruptedIOException) {
            Log.w(TAG, "OPENAI REQUEST FAILED: timeout")
            throw AiProviderException(
                kind = AiProviderErrorKind.Timeout,
                userMessage = "Lucas couldn’t reach the AI service in time.",
                cause = timeout,
            )
        } catch (network: IOException) {
            Log.w(TAG, "OPENAI REQUEST FAILED: network")
            throw AiProviderException(
                kind = AiProviderErrorKind.Network,
                userMessage = "Lucas couldn’t reach the AI service.",
                cause = network,
            )
        }
    }

    override suspend fun close() = mutex.withLock {
        closed = true
        history.clear()
    }

    private fun endpointUrl(): String {
        val base = config.baseUrl.trim().toHttpUrlOrNull()
            ?: throw AiProviderException(
                kind = AiProviderErrorKind.Configuration,
                userMessage = config.configurationErrorMessage,
            )
        return base.newBuilder()
            .addPathSegments("api/assistant")
            .build()
            .toString()
    }

    private fun parseAssistantText(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error ->
                throw AiProviderException(
                    kind = AiProviderErrorKind.MalformedResponse,
                    userMessage = "Lucas received an invalid response from the AI service.",
                    cause = error,
                )
            }
        val value = root["text"] as? JsonPrimitive
        val text = value?.takeIf { it.isString }?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) {
            throw AiProviderException(
                kind = AiProviderErrorKind.MalformedResponse,
                userMessage = "Lucas received an empty response from the AI service.",
            )
        }
        return text
    }

    private fun mapHttpError(response: BackendTransportResponse): AiProviderException {
        return when (response.statusCode) {
            401, 403 -> AiProviderException(
                AiProviderErrorKind.Authentication, "The AI service rejected the request.",
            )
            429 -> AiProviderException(
                AiProviderErrorKind.RateLimit, "The AI service is busy. Try again shortly.",
            )
            408, 504 -> AiProviderException(
                AiProviderErrorKind.Timeout, "Lucas couldn’t reach the AI service in time.",
            )
            else -> AiProviderException(
                AiProviderErrorKind.Unavailable, "Lucas couldn’t reach the AI service.",
            )
        }
    }

    private fun trimHistory() {
        val limit = config.conversationHistoryLimit.coerceIn(2, 12)
        // Remove complete turns to keep user/assistant ordering and fit Worker limits.
        while (history.isNotEmpty() && (history.size > limit ||
                history.sumOf { it.text.length } > 12_000 || history.any { it.text.length > 4_000 })) {
            history.removeFirst()
            history.removeFirst()
        }
    }

    private data class ConversationItem(val role: String, val text: String)

    private companion object {
        const val TAG = "SwitchboardProvider"
    }
}

const val OPENAI_BACKEND_PROVIDER_ID: String = "openai"
