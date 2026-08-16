package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerClientInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnection
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerJsonRpc
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerRequestDispatcher
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnApi
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnInput
import me.rerere.rikkahub.data.codex.appserver.CodexModelCatalogKnowledge
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.FakeCodexAppServerTransport
import me.rerere.rikkahub.data.codex.appserver.JsonRpcMessage
import me.rerere.rikkahub.data.codex.appserver.JsonRpcRequest
import me.rerere.rikkahub.data.codex.appserver.JsonRpcResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexStage16TurnWireIntegrationTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun `normal turn carries concrete Stage16 settings in one turn start`() = runBlocking<Unit> {
        fixture().use { f ->
            CodexModelCatalogKnowledge.replace(listOf(model("wire-model", supportsPersonality = true)))
            val params = CodexAppServerTurnStartParams(
                model = "wire-model",
                effort = "focused-future",
                summary = CodexAppServerReasoningSummary.DETAILED,
                personality = CodexAppServerPersonality.valueOf("FRIENDLY"),
            )
            val call = async {
                f.api.startTurn("thread-1", listOf(CodexAppServerTurnInput.Text("hello")), params)
            }
            val request = f.request()
            assertEquals("turn/start", request.method)
            val p = request.params!!.jsonObject
            assertEquals("wire-model", (p["model"] as JsonPrimitive).content)
            assertEquals("focused-future", (p["effort"] as JsonPrimitive).content)
            assertEquals("detailed", (p["summary"] as JsonPrimitive).content)
            assertEquals("friendly", (p["personality"] as JsonPrimitive).content)
            val input = p["input"] as JsonArray
            assertEquals(1, input.size)
            assertEquals("text", (input[0].jsonObject["type"] as JsonPrimitive).content)
            assertEquals("hello", (input[0].jsonObject["text"] as JsonPrimitive).content)
            f.respond(request, turnResult("turn-1"))
            assertEquals("turn-1", call.await().turn.id)
            assertEquals(3, f.transport.successfulWriteCount())
        }
    }

    @Test
    fun `explicit skill keeps Text then Skill order and shares Stage16 settings`() = runBlocking<Unit> {
        fixture().use { f ->
            CodexModelCatalogKnowledge.replace(listOf(model("wire-model", supportsPersonality = true)))
            val skill = CodexSkillMetadata(
                name = "review",
                description = "Review code",
                shortDescription = null,
                path = "/skills/review/SKILL.md",
                scope = "user",
                enabled = true,
                interfaceMetadata = null,
                dependencies = null,
                raw = JsonObject(emptyMap()),
            )
            val input = buildCodexSkillInvocation(skill, "inspect this")
            val params = CodexAppServerTurnStartParams(
                model = "wire-model",
                effort = "max-future",
                summary = CodexAppServerReasoningSummary.CONCISE,
                personality = CodexAppServerPersonality.valueOf("PRAGMATIC"),
            )
            val call = async { f.api.startTurn("thread-1", input, params) }
            val request = f.request()
            assertEquals("turn/start", request.method)
            val p = request.params!!.jsonObject
            assertEquals("wire-model", (p["model"] as JsonPrimitive).content)
            assertEquals("max-future", (p["effort"] as JsonPrimitive).content)
            assertEquals("concise", (p["summary"] as JsonPrimitive).content)
            assertEquals("pragmatic", (p["personality"] as JsonPrimitive).content)
            val wireInput = p["input"] as JsonArray
            assertEquals(2, wireInput.size)
            assertEquals("text", (wireInput[0].jsonObject["type"] as JsonPrimitive).content)
            assertEquals("\$review inspect this", (wireInput[0].jsonObject["text"] as JsonPrimitive).content)
            assertEquals("skill", (wireInput[1].jsonObject["type"] as JsonPrimitive).content)
            assertEquals("review", (wireInput[1].jsonObject["name"] as JsonPrimitive).content)
            assertEquals("/skills/review/SKILL.md", (wireInput[1].jsonObject["path"] as JsonPrimitive).content)
            f.respond(request, turnResult("turn-skill"))
            assertEquals("turn-skill", call.await().turn.id)
            assertEquals(3, f.transport.successfulWriteCount())
        }
    }

    @Test
    fun `unconfirmed or unsupported personality stays off the wire while saved request survives`() = runBlocking<Unit> {
        fixture().use { f ->
            CodexModelCatalogKnowledge.clearForTest()
            val requested = CodexAppServerPersonality.valueOf("FRIENDLY")
            val unknown = CodexAppServerTurnStartParams(model = "wire-model", personality = requested)
            val unknownCall = async { f.api.startTurn("thread-1", emptyList(), unknown) }
            val unknownRequest = f.request()
            assertFalse("personality" in unknownRequest.params!!.jsonObject)
            f.respond(unknownRequest, turnResult("turn-unknown"))
            unknownCall.await()

            CodexModelCatalogKnowledge.replace(listOf(model("wire-model", supportsPersonality = false)))
            val unsupported = CodexAppServerTurnStartParams(model = "wire-model", personality = requested)
            val unsupportedCall = async { f.api.startTurn("thread-1", emptyList(), unsupported) }
            val unsupportedRequest = f.request()
            assertFalse("personality" in unsupportedRequest.params!!.jsonObject)
            f.respond(unsupportedRequest, turnResult("turn-unsupported"))
            unsupportedCall.await()
        }
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(
            dispatcher,
            CodexAppServerClientInfo(name = "test", title = "Test", version = "1"),
        )
        val initialize = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val initRequest = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(
            codec.encode(
                JsonRpcResponse(
                    initRequest.id,
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
        transport.takeClientLine()
        return Fixture(transport, connection, CodexAppServerTurnApi(connection))
    }

    private fun turnResult(id: String) = buildJsonObject {
        put("turn", buildJsonObject {
            put("id", id)
            put("status", "inProgress")
        })
    }

    private fun model(model: String, supportsPersonality: Boolean) = CodexAppServerModel(
        id = "id-$model",
        model = model,
        displayName = model,
        description = "description",
        hidden = false,
        supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "High")),
        defaultReasoningEffort = "high",
        supportsPersonality = supportsPersonality,
        isDefault = false,
        raw = JsonObject(emptyMap()),
    )

    private fun decodeRequest(line: String): JsonRpcRequest =
        (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value

    private inner class Fixture(
        val transport: FakeCodexAppServerTransport,
        val connection: CodexAppServerConnection,
        val api: CodexAppServerTurnApi,
    ) : AutoCloseable {
        suspend fun request() = decodeRequest(transport.takeClientLine())
        fun respond(request: JsonRpcRequest, result: JsonObject) =
            transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, result)))
        override fun close() = connection.close()
    }
}
