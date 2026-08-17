from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- needle ---\n{old}")
    path.write_text(text.replace(old, new, 1))


runtime = Path("app/src/main/java/me/rerere/rikkahub/service/CodexChatRuntime.kt")
replace_once(
    runtime,
    "    private val closed = AtomicBoolean(false)\n    private val stopController = CodexTurnStopController()",
    "    private val closed = AtomicBoolean(false)\n    private val failed = AtomicBoolean(false)\n    private val stopController = CodexTurnStopController()",
)

terminal_marker = "    private suspend fun terminal(turnId: String, status: CodexAppServerTurnStatus, snapshot: CodexAppServerTurnSnapshot? = null) {"
failure_helper = '''    private fun failRuntime(failure: Throwable) {
        if (failure is CancellationException) throw failure
        if (!failed.compareAndSet(false, true)) return
        activeTurnId = null
        synchronized(approvalLock) {
            pendingApprovals.clear()
        }
        _state.value = CodexConversationUiState.Failed(failure.message ?: failure.toString())
        turns.values.forEach { it.terminal.completeExceptionally(failure) }
        runCatching { onFailure(this, failure) }
    }

'''
replace_once(runtime, terminal_marker, failure_helper + terminal_marker)

replace_once(
    runtime,
    '''        session.turnApi.events.collect { event ->
            if (event.threadIdOrNull() != session.threadId) return@collect
            when (event) {''',
    '''        session.turnApi.events.collect { event ->
            if (failed.get()) return@collect
            if (event is CodexAppServerTurnEvent.MalformedNotification) {
                val rawThreadId = ((event.rawParams as? kotlinx.serialization.json.JsonObject)?.get("threadId")
                    as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                if (rawThreadId == null || rawThreadId == session.threadId) failRuntime(event.cause)
                return@collect
            }
            if (event.threadIdOrNull() != session.threadId) return@collect
            try {
                when (event) {''',
)

replace_once(
    runtime,
    '''                is CodexAppServerTurnEvent.MalformedNotification -> _state.value = CodexConversationUiState.Failed(event.cause.message ?: "Malformed Codex event")
            }
        }
    }

    private fun registerApproval''',
    '''                is CodexAppServerTurnEvent.MalformedNotification -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failRuntime(failure)
            }
        }
    }

    private fun registerApproval''',
)

replace_once(
    runtime,
    '''        session.failure.collect { failure ->
            failure ?: return@collect
            activeTurnId = null
            synchronized(approvalLock) {
                pendingApprovals.clear()
            }
            _state.value = CodexConversationUiState.Failed(failure.message ?: failure.toString())
            turns.values.forEach { it.terminal.completeExceptionally(failure) }
            onFailure(this@CodexChatRuntime, failure)
        }''',
    '''        session.failure.collect { failure ->
            failure ?: return@collect
            failRuntime(failure)
        }''',
)

runtime_test = Path("app/src/test/java/me/rerere/rikkahub/service/CodexChatRuntimeIntegrationTest.kt")
replace_once(
    runtime_test,
    "import kotlinx.coroutines.CoroutineScope\n",
    "import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.async\n",
)
replace_once(
    runtime_test,
    '''    private fun harness(scope: CoroutineScope): Harness {''',
    '''    private fun harness(
        scope: CoroutineScope,
        onAgentText: suspend (turnId: String, itemId: String, text: String) -> Unit = { _, _, _ -> },
    ): Harness {''',
)
replace_once(
    runtime_test,
    '''        return Harness(CodexChatRuntime(session, scope, onAgentText = { _, _, _ -> }), transport)''',
    '''        return Harness(CodexChatRuntime(session, scope, onAgentText = onAgentText), transport)''',
)

tests_marker = '''    private fun assertActivity(activity: CodexConversationActivity) {'''
regression_tests = '''    @Test
    fun `agent text callback failure releases terminal waiter with failure`() = runBlocking {
        val harness = harness(this) { _, _, _ -> error("persist failed") }
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val waiter = async { runCatching { harness.runtime.awaitTurnTerminal("turn-1") } }

            harness.transport.emit(notification("item/agentMessage/delta", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "agent-1")
                put("delta", "hello")
            }))

            val result = withTimeout(2_000) { waiter.await() }
            assertTrue(result.isFailure)
            assertEquals("persist failed", result.exceptionOrNull()?.message)
            assertTrue(awaitState(harness.runtime) { it is CodexConversationUiState.Failed } is CodexConversationUiState.Failed)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `malformed turn completed releases active terminal waiter with protocol failure`() = runBlocking {
        val harness = harness(this)
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val waiter = async { runCatching { harness.runtime.awaitTurnTerminal("turn-1") } }

            harness.transport.emit(notification("turn/completed", buildJsonObject {
                put("threadId", "thread-1")
                put("turn", buildJsonObject {
                    put("id", "turn-1")
                })
            }))

            val result = withTimeout(2_000) { waiter.await() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("status") == true)
            assertTrue(awaitState(harness.runtime) { it is CodexConversationUiState.Failed } is CodexConversationUiState.Failed)
        } finally {
            harness.runtime.close()
        }
    }

'''
replace_once(runtime_test, tests_marker, regression_tests + tests_marker)

process = Path("workspace/src/main/java/me/rerere/workspace/WorkspaceInteractiveProcess.kt")
replace_once(
    process,
    '''    @Synchronized
    override fun close() {
        if (closed) return
        runCatching { stdin.close() }
        if (process.isAlive) process.destroy()
        var stopped = awaitStopped()
        if (!stopped && process.isAlive) {
            process.destroyForcibly()
            stopped = awaitStopped()
        }
        if (!stopped && process.isAlive) {
            throw WorkspaceProcessCleanupException("Process remained alive after forceful shutdown")
        }
        closed = true
        onClose(this)
    }''',
    '''    override fun close() {
        val notifyClose = synchronized(this) {
            if (closed) return
            runCatching { stdin.close() }
            if (process.isAlive) process.destroy()
            var stopped = awaitStopped()
            if (!stopped && process.isAlive) {
                process.destroyForcibly()
                stopped = awaitStopped()
            }
            if (!stopped && process.isAlive) {
                throw WorkspaceProcessCleanupException("Process remained alive after forceful shutdown")
            }
            closed = true
            true
        }
        if (notifyClose) onClose(this)
    }''',
)

process_test = Path("workspace/src/test/java/me/rerere/workspace/WorkspaceInteractiveProcessTest.kt")
workspace_marker = '''    @Test fun `background still receives bind mounts and deletion kills it`() {'''
workspace_test = '''    @Test fun `onClose callback runs outside handle monitor`() {
        val process = ControlledProcess()
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val monitorAcquired = CountDownLatch(1)
        lateinit var handle: WorkspaceInteractiveProcess
        handle = WorkspaceInteractiveProcess(
            process,
            onClose = {
                callbackEntered.countDown()
                assertTrue(callbackRelease.await(2, TimeUnit.SECONDS))
            },
            closeTimeoutMillis = 1,
        )

        val closeThread = thread { handle.close() }
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        val monitorThread = thread {
            synchronized(handle) {
                monitorAcquired.countDown()
            }
        }
        try {
            assertTrue("handle monitor must be released before onClose", monitorAcquired.await(500, TimeUnit.MILLISECONDS))
        } finally {
            callbackRelease.countDown()
        }
        closeThread.join(2_000)
        monitorThread.join(2_000)
        assertFalse(closeThread.isAlive)
        assertFalse(monitorThread.isAlive)
    }

'''
replace_once(process_test, workspace_marker, workspace_test + workspace_marker)
