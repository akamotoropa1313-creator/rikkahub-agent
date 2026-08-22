package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessRawResponsesProxyTest {
    @Test
    fun `authoritative session model wins over request and custom body`() {
        val request = JsonObject(
            mapOf(
                "model" to JsonPrimitive("client-controlled-model"),
                "input" to JsonPrimitive("hello"),
                "stream" to JsonPrimitive(true),
            )
        )
        val model = Model(
            modelId = "provider-wire-model",
            customBodies = listOf(
                CustomBody("model", JsonPrimitive("custom-body-model")),
                CustomBody("service_tier", JsonPrimitive("fast")),
            ),
        )

        val prepared = prepareRawResponsesBody(
            request = request,
            model = model,
            authoritativeWireModel = "provider-wire-model",
        )

        assertEquals("provider-wire-model", (prepared["model"] as JsonPrimitive).content)
        assertEquals("hello", (prepared["input"] as JsonPrimitive).content)
        assertEquals("fast", (prepared["service_tier"] as JsonPrimitive).content)
        assertTrue((prepared["stream"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `transport and authorization headers cannot be overridden by model config`() {
        val model = Model(
            modelId = "provider-wire-model",
            customHeaders = listOf(
                CustomHeader("Authorization", "Bearer attacker"),
                CustomHeader("content-type", "text/plain"),
                CustomHeader("Content-Length", "1"),
                CustomHeader("Host", "example.invalid"),
                CustomHeader("X-Custom", "kept"),
                CustomHeader("X-Empty", "   "),
            ),
        )

        val headers = rawResponsesCustomHeaders(model)

        assertEquals(listOf("X-Custom" to "kept"), headers)
        assertFalse(headers.any { it.first.equals("authorization", ignoreCase = true) })
        assertFalse(headers.any { it.first.equals("host", ignoreCase = true) })
    }
}
