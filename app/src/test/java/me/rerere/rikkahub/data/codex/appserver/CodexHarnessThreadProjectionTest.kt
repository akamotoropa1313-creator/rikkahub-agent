package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexHarnessThreadProjectionTest {
    @Test
    fun `chatgpt account keeps native provider`() {
        val projection = CodexHarnessThreadProjector.project(
            CodexHarnessExecutionPlan.ChatGptAccount("gpt-5.6-sol")
        )

        assertEquals("gpt-5.6-sol", projection.model)
        assertNull(projection.modelProvider)
        assertNull(projection.config)
    }

    @Test
    fun `external provider uses loopback responses gateway`() {
        val projection = CodexHarnessThreadProjector.project(
            CodexHarnessExecutionPlan.LocalResponsesGateway(
                providerId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
                modelId = Uuid.parse("550e8400-e29b-41d4-a716-446655440001"),
                wireModel = "claude-sonnet-4-6",
                mode = CodexHarnessExecutionPlan.GatewayMode.TRANSLATE,
            ),
            gatewayBaseUrl = "http://127.0.0.1:43123/v1/",
            gatewayBearerToken = "opaque-token",
        )

        assertEquals("claude-sonnet-4-6", projection.model)
        assertEquals(CODEX_HARNESS_GATEWAY_PROVIDER_ID, projection.modelProvider)
        val providers = projection.config?.get("model_providers") as JsonObject
        val gateway = providers[CODEX_HARNESS_GATEWAY_PROVIDER_ID] as JsonObject
        assertEquals(JsonPrimitive("http://127.0.0.1:43123/v1"), gateway["base_url"])
        assertEquals(JsonPrimitive("responses"), gateway["wire_api"])
        assertEquals(JsonPrimitive("opaque-token"), gateway["experimental_bearer_token"])
        assertTrue("real provider ids must not leak into Codex config", projection.toString().contains("550e8400").not())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gateway projection rejects non-loopback base url`() {
        CodexHarnessThreadProjector.project(
            CodexHarnessExecutionPlan.LocalResponsesGateway(
                providerId = Uuid.random(),
                modelId = Uuid.random(),
                wireModel = "model",
                mode = CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH,
            ),
            gatewayBaseUrl = "https://example.com/v1",
            gatewayBearerToken = "opaque-token",
        )
    }
}
