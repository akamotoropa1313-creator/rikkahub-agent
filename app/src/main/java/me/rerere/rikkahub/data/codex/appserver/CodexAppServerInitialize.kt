package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.Serializable

@Serializable
data class CodexAppServerClientInfo(
    val name: String = "rikkahub_agent",
    val title: String = "RikkaHub Agent",
    val version: String,
)

@Serializable
data class CodexAppServerInitializeCapabilities(
    val experimentalApi: Boolean? = null,
)

@Serializable
data class CodexAppServerInitializeParams(
    val clientInfo: CodexAppServerClientInfo,
    val capabilities: CodexAppServerInitializeCapabilities? = null,
)

@Serializable
data class CodexAppServerInitializeResponse(
    val userAgent: String,
    val codexHome: String,
    val platformFamily: String,
    val platformOs: String,
)
