package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
