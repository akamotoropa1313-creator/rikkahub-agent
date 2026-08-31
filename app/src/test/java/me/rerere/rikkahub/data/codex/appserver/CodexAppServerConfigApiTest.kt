package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerConfigApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `exact stable requests omit layers and requirements params`() = runBlocking {
        fixture().use { f ->
            val config = async { f.api.readConfig("/workspace/project/src") }
            val request = f.request()
            assertEquals("config/read", request.method)
            assertEquals(buildJsonObject { put("cwd", "/workspace/project/src") }, request.params)
            assertFalse("includeLayers" in request.params!!.jsonObject)
            f.respond(request, buildJsonObject { putJsonObject("config") {} })
            config.await()

            val requirements = async { f.api.readRequirements() }
            val raw = codec.json.parseToJsonElement(f.transport.takeClientLine()).jsonObject
            assertEquals("configRequirements/read", raw["method"]!!.jsonPrimitive.content)
            assertFalse("params" in raw)
            f.respond((codec.decode(raw.toString()).getOrThrow() as JsonRpcMessage.Request).value,
                buildJsonObject { put("requirements", JsonNull) })
            assertNull(requirements.await())
        }
    }

    @Test fun `thread agnostic request uses explicit null cwd`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.readConfig(null) }
            val request = f.request()
            assertEquals(JsonNull, request.params!!.jsonObject["cwd"])
            f.respond(request, buildJsonObject { putJsonObject("config") {} })
            assertTrue(call.await().threadAgnostic)
        }
    }

    @Test fun `snake case safe projection discards prompts layers experiments and secrets`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.readConfig("/workspace") }
            val request = f.request()
            val sentinel = "SUPER_SECRET_SENTINEL"
            f.respond(request, buildJsonObject {
                putJsonObject("config") {
                    put("model", "gpt-x"); put("model_context_window", 200000L); put("model_auto_compact_token_limit", 180000L)
                    put("model_provider", "openai"); put("sandbox_mode", "future-sandbox")
                    putJsonObject("sandbox_workspace_write") { put("network_access", true); putJsonArray("writable_roots") { add("/secret/a"); add("/secret/b") } }
                    put("forced_login_method", "chatgpt"); put("web_search", "future-search")
                    put("model_reasoning_effort", "future-effort"); put("model_reasoning_summary", "future-summary")
                    put("model_verbosity", "future-verbose"); put("service_tier", "future-tier")
                    putJsonObject("analytics") { put("enabled", false) }
                    put("instructions", sentinel); put("developer_instructions", sentinel); put("compact_prompt", sentinel)
                    put("forced_chatgpt_workspace_id", sentinel); put("approval_policy", sentinel); put("apps", sentinel); put("secret_future", sentinel)
                }
                putJsonObject("origins") {
                    putJsonObject("model") {
                        putJsonObject("name") { put("type", "user"); put("file", "/secret/config.toml"); put("profile", JsonNull) }
                        put("version", "v-user")
                    }
                    putJsonObject("model_provider") {
                        putJsonObject("name") { put("type", "project"); put("dotCodexFolder", "/secret/project/.codex") }
                        put("version", "v-project")
                    }
                    putJsonObject("model_context_window") {
                        putJsonObject("name") { put("type", "mdm"); put("domain", "private.example"); put("key", "secret-key") }
                        put("version", "v-mdm")
                    }
                    putJsonObject("sandbox_mode") {
                        putJsonObject("name") { put("type", "system"); put("file", "/secret/system/config.toml") }
                        put("version", "v-system")
                    }
                    putJsonObject("web_search") {
                        putJsonObject("name") { put("type", "enterpriseManaged"); put("id", "private-id"); put("name", "Admin policy") }
                        put("version", "v-enterprise")
                    }
                    putJsonObject("service_tier") {
                        putJsonObject("name") { put("type", "sessionFlags") }
                        put("version", "v-session")
                    }
                    putJsonObject("model_reasoning_effort") {
                        putJsonObject("name") { put("type", "legacyManagedConfigTomlFromFile"); put("file", "/secret/managed_config.toml") }
                        put("version", "v-legacy-file")
                    }
                    putJsonObject("model_reasoning_summary") {
                        putJsonObject("name") { put("type", "legacyManagedConfigTomlFromMdm") }
                        put("version", "v-legacy-mdm")
                    }
                    putJsonObject("model_verbosity") {
                        putJsonObject("name") { put("type", "packagedDefaults"); put("file", "/secret/package/defaults.toml") }
                        put("version", "v-packaged")
                    }
                    putJsonObject("analytics.enabled") {
                        putJsonObject("name") { put("type", "futureSource"); put("path", "/private") }
                        put("version", "v-future")
                    }
                    putJsonObject("instructions") {
                        putJsonObject("name") { put("type", "project"); put("dotCodexFolder", "/secret/project/.codex") }
                        put("version", "v-secret")
                    }
                }
                putJsonArray("layers") { addJsonObject { put("config", sentinel) } }
            })
            val snapshot = call.await()
            assertEquals(200000L, snapshot.modelContextWindow); assertEquals(180000L, snapshot.modelAutoCompactTokenLimit)
            assertEquals(2, snapshot.sandboxWorkspaceWrite!!.writableRootsCount)
            assertEquals(CodexConfigLayerSource.USER, snapshot.origins["model"]!!.source)
            assertEquals(CodexConfigLayerSource.PROJECT, snapshot.origins["model_provider"]!!.source)
            assertEquals(CodexConfigLayerSource.MDM, snapshot.origins["model_context_window"]!!.source)
            assertEquals(CodexConfigLayerSource.SYSTEM, snapshot.origins["sandbox_mode"]!!.source)
            assertEquals(CodexConfigLayerSource.ENTERPRISE_MANAGED, snapshot.origins["web_search"]!!.source)
            assertEquals(CodexConfigLayerSource.SESSION_FLAGS, snapshot.origins["service_tier"]!!.source)
            assertEquals(CodexConfigLayerSource.LEGACY_MANAGED_FILE, snapshot.origins["model_reasoning_effort"]!!.source)
            assertEquals(CodexConfigLayerSource.LEGACY_MANAGED_MDM, snapshot.origins["model_reasoning_summary"]!!.source)
            assertEquals(CodexConfigLayerSource.PACKAGED_DEFAULTS, snapshot.origins["model_verbosity"]!!.source)
            assertEquals(CodexConfigLayerSource.UNKNOWN, snapshot.origins["analytics.enabled"]!!.source)
            assertFalse(snapshot.sandboxMode!!.known)
            assertFalse(snapshot.toString().contains(sentinel)); assertFalse(snapshot.toString().contains("/secret"))
            assertFalse(snapshot.toString().contains("private.example")); assertFalse(snapshot.toString().contains("secret-key"))
        }
    }

    @Test fun `requirements keep stable open fields and ignore experimental fields`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.readRequirements() }; val request = f.request()
            f.respond(request, buildJsonObject { putJsonObject("requirements") {
                putJsonArray("allowedSandboxModes") { add("readOnly"); add("future-mode") }
                putJsonArray("allowedWebSearchModes") { add("disabled"); add("future-search") }
                putJsonObject("featureRequirements") { put("future_feature", true) }
                putJsonObject("models") { putJsonObject("newThread") { put("model", "managed"); put("modelReasoningEffort", "high"); put("serviceTier", "flex") } }
                putJsonArray("allowedApprovalPolicies") { add("never") }; put("hooks", "SECRET"); put("network", "SECRET"); put("allowedPermissionProfiles", "SECRET")
            } })
            val value = call.await()!!
            assertEquals(listOf("readOnly", "future-mode"), value.allowedSandboxModes!!.map { it.wireValue })
            assertEquals(true, value.featureRequirements!!["future_feature"])
            assertEquals("managed", value.newThread!!.model)
            assertFalse(value.toString().contains("SECRET")); assertFalse(value.toString().contains("never"))
        }
    }

    @Test fun `numeric strings and decimals are rejected without response disclosure`() = runBlocking {
        fixture().use { f ->
            val stringFailure = configDecodeFailure(f, JsonPrimitive("200000"), "STRING_SECRET")
            assertFalse(stringFailure.message.orEmpty().contains("STRING_SECRET"))
            val decimalFailure = configDecodeFailure(f, JsonPrimitive(200000.5), "DECIMAL_SECRET")
            assertFalse(decimalFailure.message.orEmpty().contains("DECIMAL_SECRET"))
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        }
    }

    @Test fun `effective cwd resolver uses the same proot workspace namespace for storage keys`() {
        assertEquals("/workspace/src", resolveCodexEffectiveCwd("550e8400-e29b-41d4-a716-446655440000", "src"))
        assertEquals("/workspace", resolveCodexEffectiveCwd("workspace-storage-key", ""))
        assertEquals("/workspace/.", resolveCodexEffectiveCwd("workspace-storage-key", "."))
        assertThrows(IllegalArgumentException::class.java) { resolveCodexEffectiveCwd("workspace-storage-key", "../secret") }
        assertThrows(IllegalArgumentException::class.java) { resolveCodexEffectiveCwd("workspace-storage-key", "/absolute") }
    }

    @Test fun `diagnostics cancellation is propagated and leaves connection ready`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.readDiagnostics("/workspace") }
            val first = f.request()
            val second = f.request()
            assertEquals(setOf("config/read", "configRequirements/read"), setOf(first.method, second.method))
            call.cancel()
            try {
                call.await()
                fail("expected cancellation")
            } catch (_: CancellationException) {
                // Expected: cancellation must never be converted into a capability-local Result.failure.
            }
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        }
    }

    private suspend fun configDecodeFailure(
        f: Fixture,
        fieldValue: JsonElement,
        secret: String,
    ): CodexAppServerConfigProtocolException = supervisorScope {
        val call = async { f.api.readConfig("/workspace") }
        val request = f.request()
        f.respond(request, buildJsonObject {
            putJsonObject("config") {
                put("model_context_window", fieldValue)
                put("secret", secret)
            }
        })
        try {
            call.await()
            fail("expected protocol failure")
            throw AssertionError("unreachable")
        } catch (failure: CodexAppServerConfigProtocolException) {
            failure
        }
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport(); val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(dispatcher, CodexAppServerClientInfo("test", "Test", "1"))
        val init = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val request = (codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
        transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject { put("userAgent", "fake"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") })))
        init.await(); transport.takeClientLine()
        return Fixture(transport, connection, CodexAppServerConfigApi(connection))
    }
    private inner class Fixture(val transport: FakeCodexAppServerTransport, val connection: CodexAppServerConnection, val api: CodexAppServerConfigApi) : AutoCloseable {
        suspend fun request() = (codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
        fun respond(request: JsonRpcRequest, value: JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, value)))
        override fun close() = connection.close()
    }
}
