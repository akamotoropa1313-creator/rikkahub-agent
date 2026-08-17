package me.rerere.rikkahub.data.codex.appserver

import java.net.URI
import kotlinx.coroutines.CancellationException

fun interface CodexAppServerAuthUrlLauncher { fun launch(authUrl: String) }

data class CodexAppServerPendingChatGptLogin(val loginId: String)

class CodexAppServerOAuthHandoff(
    private val accountApi: CodexAppServerAccountApi,
    private val launcher: CodexAppServerAuthUrlLauncher,
) {
    suspend fun beginChatGptLogin(): CodexAppServerPendingChatGptLogin {
        val started = accountApi.startChatGptLogin()
        val authUrl = started.authUrlForLaunch()
        try {
            validateCodexAppServerAuthUrl(authUrl)
        } catch (cause: CodexAppServerInvalidAuthUrlException) {
            throw CodexAppServerLoginHandoffException(
                started.loginId,
                "Unable to use the ChatGPT sign-in URL; the pending login may be canceled explicitly",
                cause,
            )
        }
        try {
            launcher.launch(authUrl)
        } catch (cancelled: CancellationException) {
            // account/login/start already succeeded, so plain cancellation would lose the only
            // correlation key for a still-live server-side login. Preserve the ID while retaining
            // coroutine cancellation semantics; the runtime can publish/cancel this exact attempt.
            throw CodexAppServerLoginHandoffCancellationException(started.loginId, cancelled)
        } catch (cause: Throwable) {
            throw CodexAppServerBrowserLaunchException(started.loginId, cause)
        }
        return CodexAppServerPendingChatGptLogin(started.loginId)
    }
}

open class CodexAppServerLoginHandoffException(
    val loginId: String,
    message: String,
    cause: Throwable,
) : Exception(message, cause)

/** Cancellation after account/login/start: still carries the live server-side login ID. */
class CodexAppServerLoginHandoffCancellationException(
    val loginId: String,
    cause: CancellationException,
) : CancellationException("ChatGPT sign-in launcher was canceled after login start") {
    init { initCause(cause) }
}

class CodexAppServerInvalidAuthUrlException(message: String) : IllegalArgumentException(message)
class CodexAppServerBrowserLaunchException(loginId: String, cause: Throwable) :
    CodexAppServerLoginHandoffException(
        loginId,
        "Unable to open ChatGPT sign-in; the pending login may be canceled explicitly",
        cause,
    )

fun validateCodexAppServerAuthUrl(value: String) {
    val uri = try { URI(value) } catch (_: Exception) { throw CodexAppServerInvalidAuthUrlException("Malformed authentication URL") }
    if (!uri.isAbsolute || uri.host == null) throw CodexAppServerInvalidAuthUrlException("Authentication URL must be absolute")
    val scheme = uri.scheme.lowercase()
    val loopback = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    if (scheme != "https" && !(scheme == "http" && loopback)) {
        throw CodexAppServerInvalidAuthUrlException("Authentication URL must use HTTPS or loopback HTTP")
    }
}
