package me.rerere.rikkahub.data.codex.appserver

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessExecutionPlanTest {
    @Test
    fun `Responses provider becomes credential-brokering passthrough gateway`() {
        val model = Model(modelId = "vendor/model")
        val provider = ProviderSetting.OpenAI(
            name = "Vendor",
            models = listOf(model),
            apiKey = "must-not-leak",
            useResponseApi = true,
        )

        val plan = CodexHarnessExecutionPlanner.from(
            CodexHarnessModelRoute.DirectResponses(model, provider),
        )

        assertTrue(plan is CodexHarnessExecutionPlan.LocalResponsesGateway)
        plan as CodexHarnessExecutionPlan.LocalResponsesGateway
        assertEquals(CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH, plan.mode)
        assertEquals("vendor/model", plan.wireModel)
        assertFalse(plan.toString().contains("must-not-leak"))
    }

    @Test
    fun `native provider becomes translation gateway`() {
        val model = Model(modelId = "claude-model")
        val provider = ProviderSetting.Claude(
            name = "Claude",
            models = listOf(model),
            apiKey = "must-not-leak",
        )

        val plan = CodexHarnessExecutionPlanner.from(
            CodexHarnessModelRoute.BridgeRequired(model, provider),
        )

        assertTrue(plan is CodexHarnessExecutionPlan.LocalResponsesGateway)
        plan as CodexHarnessExecutionPlan.LocalResponsesGateway
        assertEquals(CodexHarnessExecutionPlan.GatewayMode.TRANSLATE, plan.mode)
        assertFalse(plan.toString().contains("must-not-leak"))
    }
}
