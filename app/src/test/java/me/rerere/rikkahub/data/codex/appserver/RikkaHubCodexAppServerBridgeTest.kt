package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RikkaHubCodexAppServerBridgeTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `bootstrap registers RikkaHub skills then installs provider-owned Codex auth`() = runBlocking {
        fixture().use { f ->
            val source = RecordingCredentialSource()
            val bridge = RikkaHubCodexAppServerBridge(source)
            val bootstrap = async { bridge.bootstrap(f.connection) }

            val roots = f.request()
            assertEquals("skills/extraRoots/set", roots.method)
            assertEquals(
                buildJsonObject {
                    put(
                        "extraRoots",
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("/skills"))
                        },
                    )
                },
                roots.params,
            )
            f.respond(roots, buildJsonObject {})

            val login = f.request()
            assertEquals("account/login/start", login.method)
            assertEquals("chatgptAuthTokens", login.params!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("initial-token", login.params!!.jsonObject["accessToken"]!!.jsonPrimitive.content)
            f.respond(login, buildJsonObject { put("type", "chatgptAuthTokens") })
            bootstrap.await()

            f.transport.injectServerLine(
                codec.encode(
                    JsonRpcRequest(
                        JsonRpcId.StringId("refresh"),
                        "account/chatgptAuthTokens/refresh",
                        buildJsonObject {
                            put("reason", "unauthorized")
                            put("previousAccountId", "chatgpt-account")
                        },
                    ),
                ),
            )
            val response = withTimeout(2_000) {
                codec.decode(f.transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Response
            }
            assertEquals(JsonRpcId.StringId("refresh"), response.value.id)
            assertEquals("fresh-token", response.value.result.jsonObject["accessToken"]!!.jsonPrimitive.content)
            assertEquals(listOf("source-account"), source.refreshedSourceIds)
        }
    }

    @Test fun `bootstrap still exposes RikkaHub skills when no provider account exists`() = runBlocking {
        fixture().use { f ->
            val bridge = RikkaHubCodexAppServerBridge(object : CodexAppServerChatGptCredentialSource {
                override suspend fun acquire() = null
                override suspend fun refresh(sourceAccountId: String) = null
            })
            val bootstrap = async { bridge.bootstrap(f.connection) }
            val roots = f.request()
            f.respond(roots, JsonObject(emptyMap()))
            bootstrap.await()
            assertEquals(3, f.transport.successfulWriteCount()) // initialize, initialized, extra-roots
        }
    }

    @Test fun `refresh never switches ChatGPT workspace identities mid-connection`() = runBlocking {
        fixture().use { f ->
            val bridge = RikkaHubCodexAppServerBridge(
                RecordingCredentialSource(refreshedChatgptAccountId = "different-account"),
            )
            val bootstrap = async { bridge.bootstrap(f.connection) }
            f.respond(f.request(), buildJsonObject {})
            f.respond(f.request(), buildJsonObject { put("type", "chatgptAuthTokens") })
            bootstrap.await()

            f.transport.injectServerLine(
                codec.encode(
                    JsonRpcRequest(
                        JsonRpcId.StringId("mismatch"),
                        "account/chatgptAuthTokens/refresh",
                        buildJsonObject {
                            put("reason", "unauthorized")
                            put("previousAccountId", "chatgpt-account")
                        },
                    ),
                ),
            )
            val response = withTimeout(2_000) {
                codec.decode(f.transport.takeClientLine()).getOrThrow() as JsonRpcMessage.ErrorResponse
            }
            assertEquals(-32001L, response.value.error.code)
            assertEquals("RikkaHub Codex credential refresh failed", response.value.error.message)
            assertFalse(response.value.error.toString().contains("fresh-token"))
            assertFalse(response.value.error.toString().contains("different-account"))
        }
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(name = "test", version = "1"),
            CodexAppServerInitializeCapabilities(experimentalApi = true),
        )
        val initialize = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val request = request(transport)
        transport.injectServerLine(
            codec.encode(
                JsonRpcResponse(
                    request.id,
                    buildJsonObject {
                        put("userAgent", "fake")
                        put("codexHome", "/tmp")
                        put("platformFamily", "unix")
                        put("platformOs", "linux")
                    },
                ),
            ),
        )
        initialize.await()
        transport.takeClientLine() // initialized
        return Fixture(transport, connection)
    }

    private suspend fun request(transport: FakeCodexAppServerTransport) =
        (codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value

    private inner class Fixture(
        val transport: FakeCodexAppServerTransport,
        val connection: CodexAppServerConnection,
    ) : AutoCloseable {
        suspend fun request() = this@RikkaHubCodexAppServerBridgeTest.request(transport)
        fun respond(request: JsonRpcRequest, result: kotlinx.serialization.json.JsonElement) {
            transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, result)))
        }
        override fun close() = connection.close()
    }

    private class RecordingCredentialSource(
        private val refreshedChatgptAccountId: String = "chatgpt-account",
    ) : CodexAppServerChatGptCredentialSource {
        val refreshedSourceIds = mutableListOf<String>()
        override suspend fun acquire() = tokens("initial-token")
        override suspend fun refresh(sourceAccountId: String): CodexAppServerExternalChatGptTokens {
            refreshedSourceIds += sourceAccountId
            return tokens("fresh-token", refreshedChatgptAccountId)
        }
        private fun tokens(
            accessToken: String,
            chatgptAccountId: String = "chatgpt-account",
        ) = CodexAppServerExternalChatGptTokens(
            sourceAccountId = "source-account",
            accessToken = accessToken,
            chatgptAccountId = chatgptAccountId,
        )
    }
}
