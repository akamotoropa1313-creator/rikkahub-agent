package me.rerere.rikkahub.data.codex.appserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexHarnessResponsesGatewayServerTest {
    companion object {
        init {
            // The production app intentionally ships an Android SLF4J provider. Local JVM unit
            // tests have android.jar stubs rather than a real android.util.Log implementation, so
            // loading that provider throws from Log.isLoggable before Ktor can start. SLF4J 2.x
            // supports an explicit provider; its API-bundled NOP provider keeps this HTTP boundary
            // test JVM-only without changing the Android runtime logging configuration.
            System.setProperty(
                "slf4j.provider",
                "org.slf4j.helpers.NOP_FallbackServiceProvider",
            )
        }
    }

    private val client = OkHttpClient()

    @Test
    fun `gateway rejects missing bearer token over HTTP`() = runBlocking {
        val registry = CodexHarnessGatewaySessionRegistry()
        val server = newServer(registry)
        try {
            val endpoint = server.ensureStarted()
            val response = post(endpoint, "{\"stream\":false}")
            response.use {
                assertEquals(401, it.code)
                assertTrue(it.body.string().contains("invalid_gateway_token"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `translated route streams Responses events and done sentinel over HTTP`() = runBlocking {
        val registry = CodexHarnessGatewaySessionRegistry()
        val plan = CodexHarnessExecutionPlan.LocalResponsesGateway(
            providerId = Uuid.random(),
            modelId = Uuid.random(),
            wireModel = "translated-model",
            mode = CodexHarnessExecutionPlan.GatewayMode.TRANSLATE,
        )
        val token = registry.issue(plan).first
        var translatedCalled = false
        val translated = object : CodexHarnessTranslatedResponsesBackend {
            override fun stream(
                bearerToken: String?,
                request: JsonObject,
            ): Flow<CodexHarnessResponsesSseEvent> {
                translatedCalled = true
                assertEquals(token, bearerToken)
                assertEquals("client-model", (request["model"] as JsonPrimitive).content)
                return flowOf(
                    CodexHarnessResponsesSseEvent(
                        type = "response.created",
                        payload = JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("response.created"),
                                "response" to JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("resp_test"),
                                        "status" to JsonPrimitive("in_progress"),
                                    )
                                ),
                            )
                        ),
                    ),
                )
            }
        }
        val server = newServer(registry, translated = translated)
        try {
            val endpoint = server.ensureStarted()
            val response = post(
                endpoint,
                "{\"model\":\"client-model\",\"stream\":true}",
                token,
            )
            response.use {
                assertEquals(200, it.code)
                assertTrue(it.header("Content-Type").orEmpty().startsWith("text/event-stream"))
                val body = it.body.string()
                assertTrue(body.contains("event: response.created"))
                assertTrue(body.contains("data: [DONE]"))
                assertFalse(body.contains("invalid_gateway_token"))
            }
            assertTrue(translatedCalled)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `raw route relays upstream status content type and body over HTTP`() = runBlocking {
        val registry = CodexHarnessGatewaySessionRegistry()
        val plan = CodexHarnessExecutionPlan.LocalResponsesGateway(
            providerId = Uuid.random(),
            modelId = Uuid.random(),
            wireModel = "raw-model",
            mode = CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH,
        )
        val token = registry.issue(plan).first
        var rawCalled = false
        val raw = object : CodexHarnessRawResponsesBackend {
            override suspend fun open(
                bearerToken: String?,
                request: JsonObject,
            ): CodexHarnessRawResponsesProxy.OpenedResponse {
                rawCalled = true
                assertEquals(token, bearerToken)
                assertEquals("client-model", (request["model"] as JsonPrimitive).content)
                val upstreamRequest = Request.Builder().url("http://127.0.0.1/upstream").build()
                val call: Call = client.newCall(upstreamRequest)
                val response = Response.Builder()
                    .request(upstreamRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .header("Content-Type", "application/json")
                    .body("{\"id\":\"resp_raw\",\"status\":\"completed\"}".toResponseBody("application/json".toMediaType()))
                    .build()
                return CodexHarnessRawResponsesProxy.OpenedResponse(call, response)
            }
        }
        val server = newServer(registry, raw = raw)
        try {
            val endpoint = server.ensureStarted()
            val response = post(
                endpoint,
                "{\"model\":\"client-model\",\"stream\":false}",
                token,
            )
            response.use {
                assertEquals(201, it.code)
                assertTrue(it.header("Content-Type").orEmpty().startsWith("application/json"))
                assertEquals("{\"id\":\"resp_raw\",\"status\":\"completed\"}", it.body.string())
            }
            assertTrue(rawCalled)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `raw route brokers provider credential and authoritative model through real loopback upstream`() = runBlocking {
        var upstreamAuthorization: String? = null
        var upstreamTrace: String? = null
        var upstreamBody: String? = null
        val upstream = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/v1/responses") {
                    upstreamAuthorization = call.request.headers["Authorization"]
                    upstreamTrace = call.request.headers["X-Trace"]
                    upstreamBody = call.receiveText()
                    call.respondText(
                        text = "{\"id\":\"resp_upstream\",\"status\":\"completed\"}",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Accepted,
                    )
                }
            }
        }.start(wait = false)

        val registry = CodexHarnessGatewaySessionRegistry()
        var gateway: CodexHarnessResponsesGatewayServer? = null
        try {
            val upstreamPort = upstream.engine.resolvedConnectors().first().port
            val providerId = Uuid.random()
            val modelId = Uuid.random()
            val model = Model(
                id = modelId,
                modelId = "provider-wire-model",
                displayName = "Provider Wire Model",
                customHeaders = listOf(
                    CustomHeader("Authorization", "Bearer attacker"),
                    CustomHeader("X-Trace", "kept"),
                ),
                customBodies = listOf(
                    CustomBody("model", JsonPrimitive("custom-body-model")),
                    CustomBody("service_tier", JsonPrimitive("fast")),
                ),
            )
            val provider = ProviderSetting.OpenAI(
                id = providerId,
                models = listOf(model),
                apiKey = "provider-secret",
                baseUrl = "http://127.0.0.1:$upstreamPort/v1",
                useResponseApi = true,
            )
            val rawProxy = CodexHarnessRawResponsesProxy(
                sessionRegistry = registry,
                providerSettingsSource = CodexHarnessProviderSettingsSource { listOf(provider) },
                client = client,
            )
            gateway = newServer(registry, raw = rawProxy)

            val plan = CodexHarnessExecutionPlan.LocalResponsesGateway(
                providerId = providerId,
                modelId = modelId,
                wireModel = "provider-wire-model",
                mode = CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH,
            )
            val gatewayToken = registry.issue(plan).first
            val endpoint = gateway.ensureStarted()
            val response = post(
                endpoint = endpoint,
                json = "{\"model\":\"client-controlled-model\",\"input\":\"hello\",\"stream\":false}",
                token = gatewayToken,
            )

            response.use {
                assertEquals(202, it.code)
                assertTrue(it.header("Content-Type").orEmpty().startsWith("application/json"))
                assertEquals("{\"id\":\"resp_upstream\",\"status\":\"completed\"}", it.body.string())
            }

            assertEquals("Bearer provider-secret", upstreamAuthorization)
            assertFalse(upstreamAuthorization.orEmpty().contains(gatewayToken))
            assertEquals("kept", upstreamTrace)
            val parsed = Json.parseToJsonElement(checkNotNull(upstreamBody)) as JsonObject
            assertEquals("provider-wire-model", (parsed["model"] as JsonPrimitive).content)
            assertEquals("hello", (parsed["input"] as JsonPrimitive).content)
            assertEquals("fast", (parsed["service_tier"] as JsonPrimitive).content)
            assertFalse(checkNotNull(upstreamBody).contains("client-controlled-model"))
            assertFalse(checkNotNull(upstreamBody).contains(gatewayToken))
        } finally {
            gateway?.stop()
            upstream.stop(100, 1_000)
        }
    }

    private fun newServer(
        registry: CodexHarnessGatewaySessionRegistry,
        translated: CodexHarnessTranslatedResponsesBackend = object : CodexHarnessTranslatedResponsesBackend {
            override fun stream(
                bearerToken: String?,
                request: JsonObject,
            ): Flow<CodexHarnessResponsesSseEvent> = flowOf()
        },
        raw: CodexHarnessRawResponsesBackend = object : CodexHarnessRawResponsesBackend {
            override suspend fun open(
                bearerToken: String?,
                request: JsonObject,
            ): CodexHarnessRawResponsesProxy.OpenedResponse = error("raw backend should not be called")
        },
    ) = CodexHarnessResponsesGatewayServer(
        sessionRegistry = registry,
        dispatcher = translated,
        rawResponsesProxy = raw,
    )

    private fun post(
        endpoint: CodexHarnessGatewayEndpoint,
        json: String,
        token: String? = null,
    ): Response {
        val builder = Request.Builder()
            .url("${endpoint.baseUrl}/responses")
            .post(json.toRequestBody("application/json".toMediaType()))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.newCall(builder.build()).execute()
    }
}
