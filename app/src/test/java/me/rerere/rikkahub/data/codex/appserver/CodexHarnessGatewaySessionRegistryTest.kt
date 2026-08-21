package me.rerere.rikkahub.data.codex.appserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class CodexHarnessGatewaySessionRegistryTest {
    @Test
    fun `issued token resolves without exposing provider credentials`() {
        var now = 1_000L
        val registry = CodexHarnessGatewaySessionRegistry(nowMillis = { now }, ttl = 60.seconds)
        val plan = CodexHarnessExecutionPlan.LocalResponsesGateway(
            providerId = Uuid.random(),
            modelId = Uuid.random(),
            wireModel = "vendor/model",
            mode = CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH,
        )

        val (token, issued) = registry.issue(plan)

        assertTrue(token.length >= 40)
        assertFalse(token.contains(plan.wireModel))
        assertEquals(issued, registry.resolve(token))
        assertEquals(plan.providerId, issued.providerId)
        assertEquals(plan.modelId, issued.modelId)
    }

    @Test
    fun `expired and revoked tokens stop resolving`() {
        var now = 10_000L
        val registry = CodexHarnessGatewaySessionRegistry(nowMillis = { now }, ttl = 2.seconds)
        val plan = CodexHarnessExecutionPlan.LocalResponsesGateway(
            providerId = Uuid.random(),
            modelId = Uuid.random(),
            wireModel = "model",
            mode = CodexHarnessExecutionPlan.GatewayMode.TRANSLATE,
        )

        val (first, _) = registry.issue(plan)
        registry.revoke(first)
        assertNull(registry.resolve(first))

        val (second, _) = registry.issue(plan)
        now += 2_001
        assertNull(registry.resolve(second))
        assertEquals(0, registry.sizeForTest())
    }
}
