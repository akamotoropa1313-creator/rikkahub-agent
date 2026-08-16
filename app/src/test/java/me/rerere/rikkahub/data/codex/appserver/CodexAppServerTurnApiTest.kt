package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class CodexAppServerTurnApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun `stage 22 review targets use exact native inline wire and decode response`() = runBlocking {
        fixture().use { f ->
            val api = CodexAppServerReviewApi(f.connection)
            val targets = listOf(
                CodexAppServerReviewTarget.UncommittedChanges to buildJsonObject { put("type", "uncommittedChanges") },
                CodexAppServerReviewTarget.BaseBranch("main") to buildJsonObject { put("type", "baseBranch"); put("branch", "main") },
                CodexAppServerReviewTarget.Commit("abc", "Fix") to buildJsonObject { put("type", "commit"); put("sha", "abc"); put("title", "Fix") },
                CodexAppServerReviewTarget.Commit("def") to buildJsonObject { put("type", "commit"); put("sha", "def"); put("title", JsonNull) },
                CodexAppServerReviewTarget.Custom("Audit security") to buildJsonObject { put("type", "custom"); put("instructions", "Audit security") },
            )
            targets.forEachIndexed { index, (target, expected) ->
                val call = async { api.startReview("thread-1", target) }
                val raw = codec.json.parseToJsonElement(f.transport.takeClientLine()).jsonObject
                assertEquals("review/start", raw["method"]!!.jsonPrimitive.content)
                assertEquals(buildJsonObject {
                    put("threadId", "thread-1"); put("target", expected); put("delivery", "inline")
                }, raw["params"]!!.jsonObject)
                val request = decodeRequest(raw.toString())
                f.respond(request, buildJsonObject {
                    put("turn", buildJsonObject { put("id", "review-$index"); put("status", "inProgress") })
                    put("reviewThreadId", "thread-1")
                })
                assertEquals("review-$index", call.await().turn.id)
            }
        }
    }

    @Test
    fun `stage 22 review mode items are typed preserve raw and reject malformed known fields`() {
        val enteredRaw = buildJsonObject { put("id", "in"); put("type", "enteredReviewMode"); put("review", "starting"); put("future", 1) }
        val exitedRaw = buildJsonObject { put("id", "out"); put("type", "exitedReviewMode"); put("review", "final review"); put("future", true) }
        val entered = decodeItemSnapshot(enteredRaw) as CodexAppServerItemSnapshot.EnteredReviewMode
        val exited = decodeItemSnapshot(exitedRaw) as CodexAppServerItemSnapshot.ExitedReviewMode
        assertEquals("starting", entered.review); assertSame(enteredRaw, entered.raw)
        assertEquals("final review", exited.review); assertSame(exitedRaw, exited.raw)
        assertTrue(decodeItemSnapshot(buildJsonObject { put("id", "x"); put("type", "futureReview") }) is CodexAppServerItemSnapshot.Other)
        expect<CodexAppServerTurnProtocolException> {
            decodeItemSnapshot(buildJsonObject { put("id", "bad"); put("type", "exitedReviewMode"); put("review", 1) })
        }
    }

    @Test
    fun `stage 8 notifications remain simultaneously raw and typed while deprecated output stays raw only`() = runBlocking {
        fixture().use { f ->
            val raw = mutableListOf<CodexAppServerEvent.UnknownNotification>()
            val typed = mutableListOf<CodexAppServerTurnEvent>()
            val rawReady = kotlinx.coroutines.CompletableDeferred<Unit>()
            val typedReady = kotlinx.coroutines.CompletableDeferred<Unit>()
            val rawCollector = launch(start = CoroutineStart.UNDISPATCHED) { f.connection.events.collect { event -> if (event is CodexAppServerEvent.UnknownNotification) { raw += event; if (raw.size == 4) rawReady.complete(Unit) } } }
            val typedCollector = launch(start = CoroutineStart.UNDISPATCHED) { f.api.events.collect { event -> typed += event; if (typed.size == 3) typedReady.complete(Unit) } }
            val common = { itemId: String -> buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", itemId) } }
            f.transport.injectServerLine(codec.encode(JsonRpcNotification("item/fileChange/outputDelta", JsonObject(common("legacy") + ("delta" to JsonPrimitive("legacy"))))))
            f.transport.injectServerLine(codec.encode(JsonRpcNotification("item/commandExecution/outputDelta", JsonObject(common("command") + ("delta" to JsonPrimitive("out"))))))
            val change = buildJsonObject { put("path", "a"); put("kind", buildJsonObject { put("type", "add") }); put("diff", "+a\n") }
            f.transport.injectServerLine(codec.encode(JsonRpcNotification("item/fileChange/patchUpdated", JsonObject(common("file") + ("changes" to JsonArray(listOf(change)))))))
            f.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/diff/updated", buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("diff", "+a\n") })))
            withTimeout(1_000) { rawReady.await(); typedReady.await() }
            assertEquals(listOf("item/fileChange/outputDelta", "item/commandExecution/outputDelta", "item/fileChange/patchUpdated", "turn/diff/updated"), raw.map { it.method })
            assertEquals(listOf(CodexAppServerTurnEvent.CommandExecutionOutputDelta::class, CodexAppServerTurnEvent.FileChangePatchUpdated::class, CodexAppServerTurnEvent.TurnDiffUpdated::class), typed.map { it::class })
            rawCollector.cancelAndJoin(); typedCollector.cancelAndJoin()
        }
    }

    @Test
    fun `interrupt emits exact wire contract and preserves future result fields`() {
        runBlocking {
            fixture().use { f ->
                val call = async { f.api.interruptTurn("thread-1", "turn-1") }
                val raw = codec.json.parseToJsonElement(f.transport.takeClientLine()).jsonObject
                assertEquals("turn/interrupt", raw["method"]!!.jsonPrimitive.content)
                assertFalse("jsonrpc" in raw)
                assertEquals(
                    buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1") },
                    raw["params"]!!.jsonObject,
                )
                val request = decodeRequest(raw.toString())
                val result = buildJsonObject { put("futureField", 123) }
                f.respond(request, result)
                assertEquals(result, call.await().rawResult)
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test
    fun `interrupt validates ids and ready state before writing`() {
        runBlocking {
            fixture().use { f ->
                val writes = f.transport.successfulWriteCount()
                expect<IllegalArgumentException> { f.api.interruptTurn(" ", "turn") }
                expect<IllegalArgumentException> { f.api.interruptTurn("thread", " ") }
                assertEquals(writes, f.transport.successfulWriteCount())
            }
            val transport = FakeCodexAppServerTransport()
            val connection = CodexAppServerConnection(
                CodexAppServerRequestDispatcher(transport),
                CodexAppServerClientInfo(name = "test", version = "1"),
            )
            expect<CodexAppServerNotReadyException> {
                CodexAppServerTurnApi(connection).interruptTurn("thread", "turn")
            }
            assertEquals(0, transport.successfulWriteCount())
            connection.close()
        }
    }

    @Test
    fun `interrupt rejects non-object success results and propagates rpc errors without events`() {
        runBlocking { supervisorScope {
            listOf(JsonArray(emptyList()), JsonPrimitive("bad"), JsonNull).forEach { result ->
                fixture().use { f ->
                    val call = async { f.api.interruptTurn("thread", "turn") }
                    f.respond(decodeRequest(f.transport.takeClientLine()), result)
                    expect<CodexAppServerTurnProtocolException> { call.await() }
                    assertEquals(0, f.dispatcher.pendingRequestCount())
                }
            }
            fixture().use { f ->
                val events = mutableListOf<CodexAppServerTurnEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { f.api.events.collect { events += it } }
                val call = async { f.api.interruptTurn("thread", "turn") }
                val request = decodeRequest(f.transport.takeClientLine())
                f.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(request.id, JsonRpcError(409, "already completed"))))
                assertEquals(409, expect<CodexAppServerResponseException> { call.await() }.error.code)
                assertTrue(events.isEmpty())
                assertEquals(0, f.dispatcher.pendingRequestCount())
                collector.cancelAndJoin()
            }
        } }
    }

    @Test
    fun `turn completed notification may precede interrupt response`() {
        runBlocking {
            fixture().use { f ->
                val completed = kotlinx.coroutines.CompletableDeferred<CodexAppServerTurnEvent.TurnCompleted>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    f.api.events.collect { if (it is CodexAppServerTurnEvent.TurnCompleted) completed.complete(it) }
                }
                val call = async { f.api.interruptTurn("thread", "turn") }
                val request = decodeRequest(f.transport.takeClientLine())
                f.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/completed", turnEventParams("thread", "turn", "interrupted"))))
                assertEquals(CodexAppServerTurnStatus.Interrupted, withTimeout(1_000) { completed.await() }.turn.status)
                assertFalse(call.isCompleted)
                f.respond(request, JsonObject(emptyMap()))
                assertEquals(JsonObject(emptyMap()), withTimeout(1_000) { call.await() }.rawResult)
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `interrupt response may precede turn completed notification`() {
        runBlocking {
            fixture().use { f ->
                val completed = kotlinx.coroutines.CompletableDeferred<CodexAppServerTurnEvent.TurnCompleted>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    f.api.events.collect { if (it is CodexAppServerTurnEvent.TurnCompleted) completed.complete(it) }
                }
                val call = async { f.api.interruptTurn("thread", "turn") }
                val request = decodeRequest(f.transport.takeClientLine())
                f.respond(request, JsonObject(emptyMap()))
                withTimeout(1_000) { call.await() }
                assertFalse(completed.isCompleted)
                f.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/completed", turnEventParams("thread", "turn", "interrupted"))))
                assertEquals(CodexAppServerTurnStatus.Interrupted, withTimeout(1_000) { completed.await() }.turn.status)
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `minimum text wire is exact ordered unicode input and raw response is preserved`() {
        runBlocking {
            fixture().use { f ->
                val call = async { f.api.startTurn("thread-1", listOf(CodexAppServerTurnInput.Text("Hello"), CodexAppServerTurnInput.Text("日本語 🌏"))) }
                val raw = codec.json.parseToJsonElement(f.transport.takeClientLine()).jsonObject
                assertEquals("turn/start", raw["method"]!!.jsonPrimitive.content)
                assertFalse("jsonrpc" in raw); assertFalse(raw["id"]!!.jsonPrimitive.isString)
                val params = raw["params"]!!.jsonObject
                assertEquals(setOf("threadId", "input"), params.keys)
                assertEquals("thread-1", params["threadId"]!!.jsonPrimitive.content)
                assertEquals(listOf("Hello", "日本語 🌏"), params["input"]!!.let { it as JsonArray }.map { it.jsonObject["text"]!!.jsonPrimitive.content })
                assertTrue((params["input"] as JsonArray).all { it.jsonObject.keys == setOf("type", "text") && it.jsonObject["type"]!!.jsonPrimitive.content == "text" })
                val request = decodeRequest(raw.toString()); val result = turnResult("turn-1", "inProgress", true)
                f.respond(request, result)
                val opened = call.await()
                assertEquals("turn-1", opened.turn.id); assertEquals(CodexAppServerTurnStatus.InProgress, opened.turn.status)
                assertSame(opened.rawResult["turn"], opened.turn.raw); assertEquals(result, opened.rawResult)
            }
        }
    }

    @Test
    fun `stable multimodal inputs have exact wire shape and ordering`() = runBlocking {
        fixture().use { f ->
            val inputs = listOf(
                CodexAppServerTurnInput.Text("prompt"),
                CodexAppServerTurnInput.Image("https://example.test/image.png"),
                CodexAppServerTurnInput.LocalImage("/tmp/image.png"),
                CodexAppServerTurnInput.Audio("data:audio/wav;base64,AA=="),
                CodexAppServerTurnInput.LocalAudio("/tmp/audio.wav"),
                CodexAppServerTurnInput.Skill("review", "/skills/review"),
            )
            val call = async { f.api.startTurn("thread", inputs) }
            val request = decodeRequest(f.transport.takeClientLine())
            assertEquals(
                """[{"type":"text","text":"prompt"},{"type":"image","url":"https://example.test/image.png"},{"type":"localImage","path":"/tmp/image.png"},{"type":"audio","url":"data:audio/wav;base64,AA=="},{"type":"localAudio","path":"/tmp/audio.wav"},{"type":"skill","name":"review","path":"/skills/review"}]""",
                request.params!!.jsonObject["input"].toString(),
            )
            f.respond(request, turnResult("turn", "completed"))
            call.await()
            Unit
        }
    }

    @Test
    fun `stable overrides serialize and unsupported fields remain absent`() {
        runBlocking {
            fixture().use { f ->
                val schema = buildJsonObject { put("type", "object") }
                val call = async { f.api.startTurn("t", emptyList(), CodexAppServerTurnStartParams("message", "/x", "gpt", "future-effort", CodexAppServerReasoningSummary.DETAILED, CodexAppServerPersonality.FRIENDLY, schema)) }
                val request = decodeRequest(f.transport.takeClientLine()); val p = request.params!!.jsonObject
                assertEquals(setOf("threadId", "input", "clientUserMessageId", "cwd", "model", "effort", "summary", "personality", "outputSchema"), p.keys)
                assertEquals("future-effort", p["effort"]!!.jsonPrimitive.content); assertEquals("detailed", p["summary"]!!.jsonPrimitive.content)
                listOf("approvalPolicy", "sandboxPolicy", "serviceTier", "additionalContext", "permissions", "collaborationMode", "multiAgentMode").forEach { assertFalse(it in p) }
                f.respond(request, turnResult("id", "completed")); call.await()
            }
        }
    }

    @Test
    fun `all reasoning summary values use official spelling`() {
        runBlocking {
            CodexAppServerReasoningSummary.entries.forEach { value -> fixture().use { f ->
                val call = async { f.api.startTurn("t", emptyList(), CodexAppServerTurnStartParams(summary = value)) }
                val request = decodeRequest(f.transport.takeClientLine())
                assertEquals(value.wireValue, request.params!!.jsonObject["summary"]!!.jsonPrimitive.content)
                f.respond(request, turnResult("id", "completed")); call.await()
            } }
        }
    }

    @Test
    fun `service tier distinguishes omission default modern legacy and future strings`() = runBlocking {
        listOf(null, "default", "priority", "fast", "future-tier").forEach { tier -> fixture().use { f ->
            val call = async { f.api.startTurn("t", listOf(CodexAppServerTurnInput.Text("x")), CodexAppServerTurnStartParams(serviceTier = tier)) }
            val request = decodeRequest(f.transport.takeClientLine())
            if (tier == null) assertFalse("serviceTier" in request.params!!.jsonObject)
            else assertEquals(tier, request.params!!.jsonObject["serviceTier"]!!.jsonPrimitive.content)
            assertFalse("service_tier" in request.params!!.jsonObject)
            f.respond(request, turnResult("id", "completed")); call.await()
        } }
    }

    @Test
    fun `blank thread id rejects before write and ready gate is required`() {
        runBlocking {
            fixture().use { f ->
                val writes = f.transport.successfulWriteCount()
                expect<IllegalArgumentException> { f.api.startTurn("  ", emptyList()) }
                assertEquals(writes, f.transport.successfulWriteCount()); assertEquals(0, f.dispatcher.pendingRequestCount())
            }
            val t = FakeCodexAppServerTransport(); val d = CodexAppServerRequestDispatcher(t)
            val c = CodexAppServerConnection(
                d,
                CodexAppServerClientInfo(name = "test", version = "1"),
            )
            expect<CodexAppServerNotReadyException> { CodexAppServerTurnApi(c).startTurn("t", emptyList()) }
            c.close()
        }
    }

    @Test
    fun `malformed response projection is rejected while unknown status is retained`() {
        runBlocking { supervisorScope {
            val malformed = listOf<JsonElementCase>(
                JsonElementCase(JsonNull), JsonElementCase(JsonPrimitive("x")), JsonElementCase(JsonObject(emptyMap())),
                JsonElementCase(buildJsonObject { put("turn", "x") }), JsonElementCase(buildJsonObject { put("turn", buildJsonObject { put("status", "completed") }) }),
                JsonElementCase(buildJsonObject { put("turn", buildJsonObject { put("id", 2); put("status", "completed") }) }),
                JsonElementCase(turnResult(" ", "completed")), JsonElementCase(buildJsonObject { put("turn", buildJsonObject { put("id", "id") }) }),
                JsonElementCase(buildJsonObject { put("turn", buildJsonObject { put("id", "id"); put("status", 2) }) }),
            )
            malformed.forEach { (value) -> fixture().use { f -> val call = async { f.api.startTurn("t", emptyList()) }; f.respond(decodeRequest(f.transport.takeClientLine()), value); expect<CodexAppServerTurnProtocolException> { call.await() } } }
            fixture().use { f -> val call = async { f.api.startTurn("t", emptyList()) }; val request = decodeRequest(f.transport.takeClientLine()); f.respond(request, turnResult("id", "future")); assertEquals(CodexAppServerTurnStatus.Unknown("future"), call.await().turn.status) }
        } }
    }

    @Test
    fun `concurrent starts correlate out of order without retry and cancellation cleans pending`() {
        runBlocking {
            fixture().use { f ->
                val calls = (0..2).map { n -> async { f.api.startTurn("t$n", listOf(CodexAppServerTurnInput.Text("$n"))) } }
                val requests = (0..2).map { decodeRequest(f.transport.takeClientLine()) }
                listOf(2, 0, 1).forEach { f.respond(requests[it], turnResult("turn-$it", "inProgress")) }
                assertEquals(listOf("turn-0", "turn-1", "turn-2"), calls.map { it.await().turn.id })
                val before = f.transport.successfulWriteCount()
                val timed = async { f.api.startTurn("t", emptyList(), timeout = 10.milliseconds) }; f.transport.takeClientLine()
                expect<kotlinx.coroutines.TimeoutCancellationException> { timed.await() }; assertEquals(0, f.dispatcher.pendingRequestCount()); assertEquals(before + 1, f.transport.successfulWriteCount())
                val cancelled = async { f.api.startTurn("t", emptyList()) }; f.transport.takeClientLine(); cancelled.cancelAndJoin()
                assertEquals(0, f.dispatcher.pendingRequestCount()); assertEquals(before + 2, f.transport.successfulWriteCount())
            }
        }
    }

    @Test
    fun `json rpc error propagates and response alone completes`() {
        runBlocking { supervisorScope { fixture().use { f ->
            val call = async { f.api.startTurn("t", emptyList()) }; val request = decodeRequest(f.transport.takeClientLine())
            f.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(request.id, JsonRpcError(9, "denied"))))
            assertEquals(9, expect<CodexAppServerResponseException> { call.await() }.error.code)
            val onlyResponse = async { f.api.startTurn("t", emptyList()) }; val second = decodeRequest(f.transport.takeClientLine()); f.respond(second, turnResult("ok", "completed")); assertEquals("ok", onlyResponse.await().turn.id)
        } } }
    }

    @Test
    fun `response completes before a later typed turn started notification`() {
        runBlocking {
            fixture().use { f ->
                val events = mutableListOf<CodexAppServerTurnEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    f.api.events.collect { events += it }
                }
                val call = async { f.api.startTurn("thread", emptyList()) }
                val request = decodeRequest(f.transport.takeClientLine())
                f.respond(request, turnResult("turn", "inProgress"))
                assertEquals("turn", call.await().turn.id)
                assertTrue(events.isEmpty())

                f.transport.injectServerLine(codec.encode(JsonRpcNotification(
                    "turn/started",
                    buildJsonObject {
                        put("threadId", "thread")
                        put("turn", buildJsonObject { put("id", "turn"); put("status", "inProgress") })
                    },
                )))
                withTimeout(1_000) { while (events.isEmpty()) yield() }
                assertTrue(events.single() is CodexAppServerTurnEvent.TurnStarted)
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `raw connection events remain observable beside typed turn events`() {
        runBlocking {
            fixture().use { f ->
                val rawEvents = mutableListOf<CodexAppServerEvent>()
                val typedEvents = mutableListOf<CodexAppServerTurnEvent>()
                val rawCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    f.connection.events.collect { rawEvents += it }
                }
                val typedCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    f.api.events.collect { typedEvents += it }
                }
                val unrelated = buildJsonObject { put("x", 1) }
                f.transport.injectServerLine(codec.encode(JsonRpcNotification("future/event", unrelated)))
                val delta = buildJsonObject {
                    put("threadId", "thread"); put("turnId", "turn")
                    put("itemId", "item"); put("delta", "hello")
                }
                f.transport.injectServerLine(codec.encode(JsonRpcNotification("item/agentMessage/delta", delta)))
                withTimeout(1_000) { while (rawEvents.size < 2 || typedEvents.isEmpty()) yield() }

                val future = rawEvents[0] as CodexAppServerEvent.UnknownNotification
                assertEquals("future/event", future.method); assertEquals(unrelated, future.params)
                assertEquals(
                    "item/agentMessage/delta",
                    (rawEvents[1] as CodexAppServerEvent.UnknownNotification).method,
                )
                assertEquals("hello", (typedEvents.single() as CodexAppServerTurnEvent.AgentMessageDelta).delta)
                rawCollector.cancelAndJoin(); typedCollector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `explicit skill invocation uses one normal turn start request`() = runBlocking {
        fixture().use { f ->
            val invocation = explicitSkillInvocation("demo", "/skills/demo", "do work")
            assertEquals(CodexAppServerTurnInput.Text("\$demo do work"), invocation.input[0])
            assertEquals(CodexAppServerTurnInput.Skill("demo", "/skills/demo"), invocation.input[1])
            val before = f.transport.successfulWriteCount()
            val call = async { f.api.startTurn("thread", invocation.input) }
            val raw = codec.json.parseToJsonElement(f.transport.takeClientLine()).jsonObject
            assertEquals("turn/start", raw["method"]!!.jsonPrimitive.content)
            assertEquals(
                JsonArray(listOf(
                    buildJsonObject { put("type", "text"); put("text", "\$demo do work") },
                    buildJsonObject { put("type", "skill"); put("name", "demo"); put("path", "/skills/demo") },
                )),
                raw["params"]!!.jsonObject["input"],
            )
            f.respond(decodeRequest(raw.toString()), turnResult("turn", "inProgress"))
            assertEquals("turn", call.await().turn.id)
            assertEquals(before + 1, f.transport.successfulWriteCount())
        }
    }

    @Test
    fun `skill inputs reject blank identity without writing`() = runBlocking {
        fixture().use { f ->
            val writes = f.transport.successfulWriteCount()
            expect<IllegalArgumentException> { explicitSkillInvocation(" ", "/skill") }
            expect<IllegalArgumentException> { explicitSkillInvocation("demo", " ") }
            expect<IllegalArgumentException> { CodexAppServerTurnInput.Skill(" ", "/skill") }
            assertEquals(writes, f.transport.successfulWriteCount())
        }
    }

    private suspend fun fixture(): Fixture {
        val t = FakeCodexAppServerTransport(); val d = CodexAppServerRequestDispatcher(t)
        val c = CodexAppServerConnection(
            d,
            CodexAppServerClientInfo(name = "test", version = "1"),
        )
        val init = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async { c.initialize() }; val request = decodeRequest(t.takeClientLine())
        t.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject { put("userAgent", "fake"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") })))
        init.await(); t.takeClientLine(); return Fixture(t, d, c, CodexAppServerTurnApi(c))
    }
    private fun turnResult(id: String, status: String, extra: Boolean = false) = buildJsonObject { put("turn", buildJsonObject { put("id", id); put("status", status); if (extra) put("future", 1) }); if (extra) put("extra", true) }
    private fun turnEventParams(threadId: String, turnId: String, status: String) = buildJsonObject {
        put("threadId", threadId)
        put("turn", buildJsonObject { put("id", turnId); put("status", status) })
    }
    private fun decodeRequest(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value
    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T = try { block(); fail("Expected ${T::class.java.simpleName}"); error("unreachable") } catch (e: Throwable) { if (e is T) e else if (e.cause is T) e.cause as T else throw e }
    private data class JsonElementCase(val value: kotlinx.serialization.json.JsonElement)
    private inner class Fixture(val transport: FakeCodexAppServerTransport, val dispatcher: CodexAppServerRequestDispatcher, val connection: CodexAppServerConnection, val api: CodexAppServerTurnApi) : AutoCloseable {
        fun respond(request: JsonRpcRequest, value: kotlinx.serialization.json.JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, value)))
        override fun close() = connection.close()
    }
}
