package com.switchboard.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Health only: never sends a key, conversation, or paid inference request. */
class BackendHealthChecker(
    private val fetch: suspend (String) -> BackendTransportResponse = ::fetchHealth,
) {
    suspend fun check(baseUrl: String): String {
        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: return "Switchboard backend isn’t configured. Open Debug Settings to set the backend address."
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            return "Enter only the backend address, without credentials or query parameters."
        }
        val address = "${url.host}:${url.port}"
        return try {
            val response = fetch(url.newBuilder().addPathSegment("health").build().toString())
            if (response.statusCode != 200) return "Backend at $address returned HTTP ${response.statusCode}."
            val json = Json.parseToJsonElement(response.body).jsonObject
            fun field(name: String): String? = (json[name] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            val ok = (json["ok"] as? JsonPrimitive)?.booleanOrNull
            val configured = (json["openaiConfigured"] as? JsonPrimitive)?.booleanOrNull
            val version = field("version")?.takeIf { it.matches(Regex("[0-9.]{1,20}")) }
            val model = field("model")?.takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,80}")) }
            if (ok != true || field("service") != "switchboard" || configured == null || version == null || model == null) {
                return "Reached $address, but it did not return valid Switchboard health information."
            }
            "Connected to $address\nBackend v$version\n" +
                (if (configured) "OpenAI configured" else "OpenAI key missing on backend") +
                "\nModel: $model\nConnection test only; OpenAI account access has not been tested."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Cannot reach backend at $address. Start the backend and check your network/address."
        }
    }
}

private val healthClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()

private suspend fun fetchHealth(url: String): BackendTransportResponse = withContext(Dispatchers.IO) {
    healthClient.newCall(Request.Builder().url(url).get().build()).execute().use {
        BackendTransportResponse(it.code, it.peekBody(8_192).string())
    }
}
