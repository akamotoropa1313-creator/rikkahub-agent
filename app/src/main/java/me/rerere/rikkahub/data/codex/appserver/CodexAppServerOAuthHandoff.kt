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
        validateCodexAppServerAuthUrl(authUrl)
        try {
            launcher.launch(authUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            throw CodexAppServerBrowserLaunchException(started.loginId, cause)
        }
        return CodexAppServerPendingChatGptLogin(started.loginId)
    }
}

class CodexAppServerInvalidAuthUrlException(message: String) : IllegalArgumentException(message)
class CodexAppServerBrowserLaunchException(val loginId: String, cause: Throwable) :
    Exception("Unable to open ChatGPT sign-in; the pending login may be canceled explicitly", cause)

fun validateCodexAppServerAuthUrl(value: String) {
    val uri = try { URI(value) } catch (_: Exception) { throw CodexAppServerInvalidAuthUrlException("Malformed authentication URL") }
    if (!uri.isAbsolute || uri.host == null) throw CodexAppServerInvalidAuthUrlException("Authentication URL must be absolute")
    val scheme = uri.scheme.lowercase()
    val loopback = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    if (scheme != "https" && !(scheme == "http" && loopback)) {
        throw CodexAppServerInvalidAuthUrlException("Authentication URL must use HTTPS or loopback HTTP")
    }
}
