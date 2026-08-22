package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexAppServerSessionCatalogRefreshTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun `first connected session populates empty process model catalog automatically`() = runBlocking {
        CodexModelCatalogKnowledge.clearForTest()
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = "conversation-catalog",
            workspaceId = "workspace-catalog",
            threadId = "thread-catalog",
            workspaceCwd = "",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val repository = CodexAppServerSessionBindingRepository(
            FakeBindingDao(binding),
            object : CodexAppServerLocalState {
                override suspend fun conversationExists(id: String) = true
                override suspend fun getWorkspace(id: String): WorkspaceEntity? = null
            },
        ) { 2L }
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(
            dispatcher,
            CodexAppServerClientInfo(name = "test", title = "Test", version = "1"),
        )

        val initialize = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val initRequest = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(codec.encode(JsonRpcResponse(initRequest.id, buildJsonObject {
            put("userAgent", "fake")
            put("codexHome", "/tmp")
            put("platformFamily", "unix")
            put("platformOs", "linux")
        })))
        initialize.await()
        transport.takeClientLine() // initialized notification

        val session = CodexAppServerConversationSession(binding, connection, repository)
        try {
            val request = withTimeout(5_000) { decodeRequest(transport.takeClientLine()) }
            assertEquals("model/list", request.method)
            assertEquals(false, request.params!!.let { params ->
                params.jsonObject["includeHidden"]!!.toString().toBoolean()
            })

            transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, catalogPage())))

            val models = withTimeout(5_000) {
                CodexModelCatalogKnowledge.modelsFlow.first { it.isNotEmpty() }
            }
            assertEquals(listOf("auto-wire-model"), models.map { it.model })
            assertEquals("auto-wire-model", CodexModelCatalogKnowledge.defaultModel())
        } finally {
            session.close()
            CodexModelCatalogKnowledge.clearForTest()
        }
    }

    private fun catalogPage() = buildJsonObject {
        putJsonArray("data") {
            addJsonObject {
                put("id", "catalog-auto-wire-model")
                put("model", "auto-wire-model")
                put("displayName", "Automatic model")
                put("description", "Loaded automatically after session connection")
                put("hidden", false)
                put("defaultReasoningEffort", "medium")
                put("supportsPersonality", true)
                put("isDefault", true)
                putJsonArray("supportedReasoningEfforts") {
                    addJsonObject {
                        put("reasoningEffort", "medium")
                        put("description", "Medium reasoning")
                    }
                }
            }
        }
        put("nextCursor", JsonNull)
    }

    private fun decodeRequest(line: String) =
        (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value

    private class FakeBindingDao(initial: CodexAppServerSessionBindingEntity) :
        CodexAppServerSessionBindingDao {
        private var binding: CodexAppServerSessionBindingEntity? = initial

        override suspend fun getByConversationId(conversationId: String) =
            binding?.takeIf { it.conversationId == conversationId }

        override fun observeByConversationId(conversationId: String) =
            flowOf(binding?.takeIf { it.conversationId == conversationId })

        override suspend fun getByThreadId(threadId: String) =
            binding?.takeIf { it.threadId == threadId }

        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) {
            this.binding = binding
        }

        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (this.binding != null) return -1L
            this.binding = binding
            return 1L
        }

        override suspend fun updateLastObservedTurn(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            status: String,
            updatedAtMs: Long,
        ): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(
                lastObservedTurnId = turnId,
                lastObservedTurnStatus = status,
                updatedAtMs = updatedAtMs,
            )
            return 1
        }

        override suspend fun updateLastObservedTurnStarted(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            updatedAtMs: Long,
        ): Int = updateLastObservedTurn(
            conversationId,
            expectedThreadId,
            turnId,
            "inProgress",
            updatedAtMs,
        )

        override suspend fun updateLastResumed(
            conversationId: String,
            expectedThreadId: String,
            resumedAtMs: Long,
        ): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs)
            return 1
        }

        override suspend fun deleteByConversationId(conversationId: String): Int {
            if (binding?.conversationId != conversationId) return 0
            binding = null
            return 1
        }
    }
}
