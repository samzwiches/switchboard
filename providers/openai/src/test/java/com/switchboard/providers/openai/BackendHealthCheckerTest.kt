package com.switchboard.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackendHealthCheckerTest {
    @Test fun `health success displays safe metadata and checks only health endpoint`() = runTest {
        val checker = BackendHealthChecker { url ->
            assertEquals("http://10.0.2.2:8787/health", url)
            BackendTransportResponse(200, """{"ok":true,"service":"switchboard","version":"0.2","openaiConfigured":true,"model":"gpt-5.6-luna"}""")
        }
        val result = checker.check("http://10.0.2.2:8787")
        assertTrue(result.contains("Connected to 10.0.2.2:8787"))
        assertTrue(result.contains("Backend v0.2"))
        assertTrue(result.contains("OpenAI configured"))
        assertTrue(result.contains("Model: gpt-5.6-luna"))
    }
    @Test fun `missing key is distinguished from network failure`() = runTest {
        val checker = BackendHealthChecker {
            BackendTransportResponse(200, """{"ok":true,"service":"switchboard","version":"0.2","openaiConfigured":false,"model":"gpt-5.6-luna"}""")
        }
        assertTrue(checker.check("http://localhost:8787").contains("OpenAI key missing on backend"))
    }
    @Test fun `connection failures name address without raw exception details`() = runTest {
        val result = BackendHealthChecker { throw IOException("private internals") }.check("http://192.168.1.5:8787")
        assertTrue(result.contains("Cannot reach backend at 192.168.1.5:8787"))
        assertFalse(result.contains("private internals"))
    }
    @Test fun `empty URL does not make request`() = runTest {
        assertTrue(BackendHealthChecker { error("must not call") }.check("").contains("Open Debug Settings"))
    }
    @Test fun `HTTP error and unrelated health response do not report connected`() = runTest {
        assertTrue(BackendHealthChecker { BackendTransportResponse(503, "private internals") }
            .check("http://localhost:8787").contains("HTTP 503"))
        assertFalse(BackendHealthChecker { BackendTransportResponse(200, """{"ok":true}""") }
            .check("http://localhost:8787").startsWith("Connected"))
    }
    @Test fun `malformed health is contained and cancellation propagates`() = runTest {
        assertFalse(BackendHealthChecker { BackendTransportResponse(200, "bad JSON") }
            .check("http://localhost:8787").startsWith("Connected"))
        val error = runCatching {
            BackendHealthChecker { throw CancellationException() }.check("http://localhost:8787")
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
