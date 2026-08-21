package me.rerere.rikkahub.data.codex.appserver

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexHarnessModelRouteResolverTest {
    @Test
    fun `legacy codex model remains a ChatGPT account route`() {
        val assistant = Assistant(codexModel = "gpt-5.6-sol")
        val route = CodexHarnessModelRouteResolver.resolve(assistant, Settings(providers = emptyList()))
        assertEquals(CodexHarnessModelRoute.ChatGptAccount("gpt-5.6-sol"), route)
    }

    @Test
    fun `typed ChatGPT target wins over legacy codex model`() {
        val assistant = Assistant(
            codexModel = "legacy",
            codexHarnessModelTarget = CodexHarnessModelTarget.ChatGptAccount("selected"),
        )
        val route = CodexHarnessModelRouteResolver.resolve(assistant, Settings(providers = emptyList()))
        assertEquals(CodexHarnessModelRoute.ChatGptAccount("selected"), route)
    }

    @Test
    fun `Responses API OpenAI provider resolves direct`() {
        val model = Model(modelId = "vendor/model", displayName = "Vendor Model")
        val provider = ProviderSetting.OpenAI(
            name = "Responses Vendor",
            models = listOf(model),
            baseUrl = "https://example.invalid/v1",
            apiKey = "secret",
            useResponseApi = true,
        )
        val assistant = Assistant(
            codexHarnessModelTarget = CodexHarnessModelTarget.RikkaHubProvider(model.id),
        )

        val route = CodexHarnessModelRouteResolver.resolve(
            assistant,
            Settings(providers = listOf(provider)),
        )

        assertTrue(route is CodexHarnessModelRoute.DirectResponses)
        route as CodexHarnessModelRoute.DirectResponses
        assertSame(model, route.model)
        assertEquals("Responses Vendor", route.provider.name)
    }

    @Test
    fun `native non Responses provider requires bridge`() {
        val model = Model(modelId = "claude-model", displayName = "Claude Model")
        val provider = ProviderSetting.Claude(name = "Claude", models = listOf(model))
        val assistant = Assistant(
            codexHarnessModelTarget = CodexHarnessModelTarget.RikkaHubProvider(model.id),
        )

        val route = CodexHarnessModelRouteResolver.resolve(
            assistant,
            Settings(providers = listOf(provider)),
        )

        assertTrue(route is CodexHarnessModelRoute.BridgeRequired)
    }

    @Test
    fun `deleted provider model is reported explicitly`() {
        val missingId = Uuid.random()
        val assistant = Assistant(
            codexHarnessModelTarget = CodexHarnessModelTarget.RikkaHubProvider(missingId),
        )
        val route = CodexHarnessModelRouteResolver.resolve(assistant, Settings(providers = emptyList()))
        assertEquals(CodexHarnessModelRoute.MissingProviderModel(missingId.toString()), route)
    }
}
