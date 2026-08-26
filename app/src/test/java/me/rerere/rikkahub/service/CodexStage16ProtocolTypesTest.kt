package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSandboxMode
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSandboxPolicy
import me.rerere.rikkahub.data.codex.appserver.CodexModelCatalogKnowledge
import me.rerere.rikkahub.data.codex.appserver.CodexModelServiceTier
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexSandboxMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexStage16ProtocolTypesTest {
    @Test
    fun `legacy missing preference becomes Workspace write on both thread and turn`() {
        val effective = effectiveCodexSandboxMode(null)

        assertEquals(
            CodexAppServerSandboxMode.WORKSPACE_WRITE,
            CodexAppServerThreadStartParams(sandbox = effective).sandbox,
        )
        assertEquals(
            CodexAppServerSandboxPolicy.WorkspaceWrite,
            CodexAppServerTurnStartParams(sandbox = effective).sandboxPolicy,
        )
    }

    @Test
    fun `turn personality is omitted until selected model support is confirmed`() {
        CodexModelCatalogKnowledge.clearForTest()
        val requested = CodexAppServerPersonality.valueOf("FRIENDLY")
        val unknown = CodexAppServerTurnStartParams(model = "model-a", personality = requested)
        assertNull(unknown.personality)

        CodexModelCatalogKnowledge.replace(listOf(model("model-a", supportsPersonality = true)))
        val confirmed = CodexAppServerTurnStartParams(model = "model-a", personality = requested)
        assertEquals(me.rerere.rikkahub.data.codex.appserver.CodexAppServerPersonality.FRIENDLY, confirmed.personality)
    }

    @Test
    fun `unsupported model never receives personality and new thread omits unconfirmed override`() {
        CodexModelCatalogKnowledge.replace(listOf(model("model-a", supportsPersonality = false)))
        val requested = CodexAppServerPersonality.valueOf("PRAGMATIC")
        assertNull(CodexAppServerTurnStartParams(model = "model-a", personality = requested).personality)
        assertNull(CodexAppServerThreadStartParams(model = "model-a", personality = requested).personality)
    }

    @Test
    fun `new thread emits default tier but omits unconfirmed specific tier`() {
        CodexModelCatalogKnowledge.clearForTest()
        assertEquals("default", CodexAppServerThreadStartParams(model = "model-a", serviceTier = "default").serviceTier)
        assertNull(CodexAppServerThreadStartParams(model = "model-a", serviceTier = "priority").serviceTier)

        // Turn semantics are intentionally more permissive once a runtime exists: saved exact
        // strings survive catalog Unknown and the App Server remains the final authority.
        assertEquals("priority", CodexAppServerTurnStartParams(model = "model-a", serviceTier = "priority").serviceTier)
    }

    @Test
    fun `new thread emits specific tier only after current catalog confirms it`() {
        CodexModelCatalogKnowledge.replace(
            listOf(model("model-a", tiers = listOf(CodexModelServiceTier("priority", "Fast", "Faster"))))
        )
        assertEquals("priority", CodexAppServerThreadStartParams(model = "model-a", serviceTier = "priority").serviceTier)
        assertNull(CodexAppServerThreadStartParams(model = "model-a", serviceTier = "future").serviceTier)
    }

    @Test
    fun `null model resolves only the explicit catalog default for thread tier confirmation`() {
        CodexModelCatalogKnowledge.replace(
            listOf(
                model("model-a", isDefault = false, tiers = listOf(CodexModelServiceTier("other", "Other", "Other"))),
                model("model-default", isDefault = true, tiers = listOf(CodexModelServiceTier("priority", "Fast", "Faster"))),
            )
        )
        assertEquals("priority", CodexAppServerThreadStartParams(model = null, serviceTier = "priority").serviceTier)
        assertNull(CodexAppServerThreadStartParams(model = "missing", serviceTier = "priority").serviceTier)
    }

    @Test
    fun `unreported or explicitly empty tier metadata never confirms a specific thread tier`() {
        CodexModelCatalogKnowledge.replace(listOf(model("legacy-unknown", tiers = null, legacy = null)))
        assertNull(CodexAppServerThreadStartParams(model = "legacy-unknown", serviceTier = "priority").serviceTier)

        CodexModelCatalogKnowledge.replace(listOf(model("explicit-empty", tiers = emptyList(), legacy = null)))
        assertNull(CodexAppServerThreadStartParams(model = "explicit-empty", serviceTier = "priority").serviceTier)
    }

    private fun model(
        model: String,
        supportsPersonality: Boolean = true,
        isDefault: Boolean = false,
        tiers: List<CodexModelServiceTier>? = null,
        legacy: List<String>? = null,
    ) = CodexAppServerModel(
        id = "id-$model",
        model = model,
        displayName = model,
        description = "description",
        hidden = false,
        supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "High")),
        defaultReasoningEffort = "high",
        supportsPersonality = supportsPersonality,
        isDefault = isDefault,
        raw = JsonObject(emptyMap()),
        serviceTiers = tiers,
        additionalSpeedTiers = legacy,
    )
}
