package me.rerere.rikkahub.service

/**
 * Stage16 keeps the large ChatService focused on orchestration while reusing the App Server's
 * canonical protocol types. These aliases are intentionally internal to the service package;
 * they add no second model/settings representation and serialize through the existing protocol
 * adapters.
 */
internal typealias CodexAppServerTurnStartParams =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStartParams
internal typealias CodexAppServerPersonality =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerPersonality
internal typealias CodexAppServerReasoningSummary =
    me.rerere.rikkahub.data.codex.appserver.CodexAppServerReasoningSummary
