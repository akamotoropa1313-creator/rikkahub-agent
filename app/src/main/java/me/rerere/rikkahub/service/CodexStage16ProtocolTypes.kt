package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.codex.appserver.CodexModelCatalogKnowledge

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
 */
internal fun CodexAppServerTurnStartParams(
    model: String? = null,
    effort: String? = null,
    summary: CodexAppServerReasoningSummary? = null,
    personality: RequestedCodexPersonality? = null,
): me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStartParams =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStartParams(
        model = model,
        effort = effort,
        summary = summary,
        personality = personality?.value?.takeIf { CodexModelCatalogKnowledge.personalitySupported(model) },
    )

/**
 * New threads never need an unconfirmed personality override: the same saved preference is applied
 * on a later turn once the current App Server catalog confirms model support. This also prevents a
 * stale saved preference from making thread creation fail before model discovery is possible.
 */
internal fun CodexAppServerThreadStartParams(
    model: String? = null,
    developerInstructions: String? = null,
    personality: RequestedCodexPersonality? = null,
): me.rerere.rikkahub.data.codex.appserver.CodexAppServerThreadStartParams =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerThreadStartParams(
        model = model,
        developerInstructions = developerInstructions,
        personality = null,
    )
