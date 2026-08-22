package me.rerere.rikkahub.data.codex.appserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable

private const val CODEX_HARNESS_GATEWAY_HOST = "127.0.0.1"

/** Public endpoint information safe to inject into a Codex thread config. */
data class CodexHarnessGatewayEndpoint(
    val baseUrl: String,
    val port: Int,
)

/**
 * App-private OpenAI Responses-compatible gateway used only by the managed Codex subprocess.
 *
 * The listener is hard-bound to IPv4 loopback and additionally rejects any non-loopback peer.
 * Each request must carry a short-lived opaque bearer token issued by
 * [CodexHarnessGatewaySessionRegistry]. No provider credential is accepted from, or returned to,
 * the Codex subprocess.
 */
class CodexHarnessResponsesGatewayServer(
    private val sessionRegistry: CodexHarnessGatewaySessionRegistry,
    private val dispatcher: CodexHarnessResponsesDispatcher,
    private val rawResponsesProxy: CodexHarnessRawResponsesProxy,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Closeable {
    private val lifecycleMutex = Mutex()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var endpoint: CodexHarnessGatewayEndpoint? = null

    suspend fun ensureStarted(): CodexHarnessGatewayEndpoint = lifecycleMutex.withLock {
        endpoint?.let { return@withLock it }

        val created = embeddedServer(CIO, port = 0, host = CODEX_HARNESS_GATEWAY_HOST) {
            routing {
                post("/v1/responses") {
                    if (!call.request.local.remoteHost.isLoopbackPeer()) {
                        call.respondGatewayError(HttpStatusCode.Forbidden, "loopback_required", "Loopback access required")
                        return@post
                    }
                    val token = call.request.headers[HttpHeaders.Authorization].extractBearerToken()
                    val session = token?.let(sessionRegistry::resolve)
                    if (token == null || session == null) {
                        call.respondGatewayError(HttpStatusCode.Unauthorized, "invalid_gateway_token", "Invalid gateway token")
                        return@post
                    }
                    val request = runCatching {
                        json.parseToJsonElement(call.receiveText()) as? JsonObject
                            ?: throw IllegalArgumentException("Responses request must be a JSON object")
                    }.getOrElse {
                        call.respondGatewayError(HttpStatusCode.BadRequest, "invalid_request", "Invalid Responses request")
                        return@post
                    }

                    val streaming = (request["stream"] as? JsonPrimitive)
                        ?.takeUnless { it.isString }
                        ?.content
                        ?.toBooleanStrictOrNull() == true

                    if (session.mode == CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH) {
                        try {
                            rawResponsesProxy.open(token, request).use { opened ->
                                val status = HttpStatusCode.fromValue(opened.statusCode)
                                val contentType = opened.contentType
                                    ?.let { raw -> runCatching { ContentType.parse(raw) }.getOrNull() }
                                    ?: if (streaming) ContentType.Text.EventStream else ContentType.Application.Json

                                if (streaming) {
                                    call.respondTextWriter(contentType = contentType, status = status) {
                                        val source = opened.response.body.source()
                                        while (true) {
                                            val line = source.readUtf8Line() ?: break
                                            write(line)
                                            write("\n")
                                            flush()
                                        }
                                    }
                                } else {
                                    call.respondText(
                                        opened.response.body.string(),
                                        contentType,
                                        status,
                                    )
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            // Closing the downstream Codex request cancels the OkHttp call owned by
                            // the raw proxy through its Closeable response lifecycle.
                            throw cancelled
                        } catch (failure: Throwable) {
                            val status = if (failure is CodexHarnessGatewayUnauthorizedException) {
                                HttpStatusCode.Unauthorized
                            } else {
                                HttpStatusCode.BadGateway
                            }
                            call.respondGatewayError(status, "provider_request_failed", failure.safeGatewayMessage())
                        }
                        return@post
                    }

                    if (streaming) {
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            try {
                                dispatcher.stream(token, request).collect { event ->
                                    write(event.wireData())
                                    flush()
                                }
                                write("data: [DONE]\n\n")
                                flush()
                            } catch (cancelled: CancellationException) {
                                // Client disconnect / turn cancellation must cancel collection so
                                // ProviderManager can stop the upstream network request as well.
                                throw cancelled
                            } catch (failure: Throwable) {
                                val payload = JsonObject(mapOf(
                                    "type" to JsonPrimitive("error"),
                                    "error" to JsonObject(mapOf(
                                        "type" to JsonPrimitive("gateway_error"),
                                        "code" to JsonPrimitive("provider_request_failed"),
                                        "message" to JsonPrimitive(failure.safeGatewayMessage()),
                                    )),
                                ))
                                write("event: error\ndata: $payload\n\n")
                                flush()
                            }
                        }
                    } else {
                        var terminal: JsonObject? = null
                        try {
                            dispatcher.stream(token, request).collect { event ->
                                if (event.type == "response.completed" || event.type == "response.incomplete") {
                                    terminal = event.payload["response"] as? JsonObject
                                }
                            }
                            val body = terminal ?: JsonObject(mapOf(
                                "id" to JsonPrimitive("resp_rikkahub_empty"),
                                "object" to JsonPrimitive("response"),
                                "status" to JsonPrimitive("completed"),
                                "output" to kotlinx.serialization.json.JsonArray(emptyList()),
                            ))
                            call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            val status = if (failure is CodexHarnessGatewayUnauthorizedException) {
                                HttpStatusCode.Unauthorized
                            } else {
                                HttpStatusCode.BadGateway
                            }
                            call.respondGatewayError(
                                status,
                                "provider_request_failed",
                                failure.safeGatewayMessage(),
                            )
                        }
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = created.engine.resolvedConnectors().first().port
            val resolved = CodexHarnessGatewayEndpoint(
                baseUrl = "http://$CODEX_HARNESS_GATEWAY_HOST:$port/v1",
                port = port,
            )
            server = created
            endpoint = resolved
            resolved
        } catch (failure: Throwable) {
            runCatching { created.stop(0, 1_000) }
            throw failure
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        val existing = server
        server = null
        endpoint = null
        existing?.stop(100, 1_000)
    }

    override fun close() {
        // Lifecycle owners that can suspend should call stop(). close() is a best-effort fallback
        // and deliberately does not block an Android main thread waiting for engine shutdown.
        server?.stop(0, 1_000)
        server = null
        endpoint = null
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondGatewayError(
        status: HttpStatusCode,
        code: String,
        message: String,
    ) {
        val body = JsonObject(mapOf(
            "error" to JsonObject(mapOf(
                "type" to JsonPrimitive("invalid_request_error"),
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
            ))
        ))
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    private fun String?.extractBearerToken(): String? {
        val value = this?.trim() ?: return null
        if (!value.startsWith("Bearer ", ignoreCase = true)) return null
        return value.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
    }

    private fun String.isLoopbackPeer(): Boolean =
        this == "127.0.0.1" || this == "::1" || this == "0:0:0:0:0:0:0:1" || equals("localhost", ignoreCase = true)

    private fun Throwable.safeGatewayMessage(): String = when (this) {
        is CodexHarnessGatewayRouteException -> message ?: "Provider route is unavailable"
        is CodexHarnessGatewayUnauthorizedException -> "Invalid gateway token"
        else -> "Upstream provider request failed"
    }
}
