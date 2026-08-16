package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.codex.appserver.CodexModelCatalogKnowledge
import me.rerere.rikkahub.data.codex.appserver.toTurnPolicy

/**
 * A saved personality request is intentionally distinct from the protocol enum. The request can
 * survive SettingsStore persistence while wire emission remains conditional on model/list having
 * confirmed support in the current process.
 */
internal data class RequestedCodexPersonality(
    val value: me.rerere.rikkahub.data.codex.appserver.CodexAppServerPersonality,
)

/** Keeps the unchanged ChatService call site expressive without making an unconfirmed value wire-safe. */
internal object CodexAppServerPersonality {
    fun valueOf(name: String): RequestedCodexPersonality = RequestedCodexPersonality(
        me.rerere.rikkahub.data.codex.appserver.CodexAppServerPersonality.valueOf(name),
    )
}

internal typealias CodexAppServerReasoningSummary =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerReasoningSummary

/**
 * Stage16 turn settings factory. Personality is emitted only after an explicit model/list refresh
 * has confirmed support for the selected wire model; unknown support means omit, not guess.
 * Service tier intentionally differs: a saved explicit tier is allowed on a turn when catalog
 * knowledge is unavailable, leaving the App Server as the final authority after the runtime exists.
 */
internal fun CodexAppServerTurnStartParams(
    model: String? = null,
    effort: String? = null,
    summary: CodexAppServerReasoningSummary? = null,
    personality: RequestedCodexPersonality? = null,
    serviceTier: String? = null,
    sandbox: me.rerere.rikkahub.data.codex.appserver.CodexAppServerSandboxMode? = null,
    approvalPolicy: me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalPolicy? = null,
): me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStartParams =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStartParams(
        model = model,
        effort = effort,
        summary = summary,
        personality = personality?.value?.takeIf { CodexModelCatalogKnowledge.personalitySupported(model) },
        serviceTier = serviceTier,
        sandboxPolicy = sandbox?.toTurnPolicy(),
        approvalPolicy = approvalPolicy,
    )

/**
 * New threads omit unconfirmed model-sensitive overrides so a stale persisted preference cannot
 * prevent the runtime from being created. `default` is protocol-defined and safe to emit without
 * catalog confirmation; a specific tier is emitted only when the current-process catalog confirms
 * it for the selected wire model (or its explicit isDefault model when [model] is null). The saved
 * preference itself is not changed, so a later turn can still apply it under the Stage18 Unknown
 * policy once the runtime exists.
 */
internal fun CodexAppServerThreadStartParams(
    model: String? = null,
    developerInstructions: String? = null,
    personality: RequestedCodexPersonality? = null,
    serviceTier: String? = null,
    sandbox: me.rerere.rikkahub.data.codex.appserver.CodexAppServerSandboxMode? = null,
    approvalPolicy: me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalPolicy? = null,
): me.rerere.rikkahub.data.codex.appserver.CodexAppServerThreadStartParams =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerThreadStartParams(
        model = model,
        developerInstructions = developerInstructions,
        personality = null,
        serviceTier = when (serviceTier) {
            null -> null
            "default" -> "default"
            else -> serviceTier.takeIf { CodexModelCatalogKnowledge.serviceTierConfirmed(model, it) }
        },
        sandbox = sandbox,
        approvalPolicy = approvalPolicy,
    )
