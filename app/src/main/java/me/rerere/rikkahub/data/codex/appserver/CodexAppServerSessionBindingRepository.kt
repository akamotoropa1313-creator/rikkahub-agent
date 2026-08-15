package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity

class CodexAppServerSessionBindingRepository(
    private val bindingDao: CodexAppServerSessionBindingDao,
    private val conversationDao: ConversationDAO,
    private val workspaceDao: WorkspaceDAO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun getBinding(conversationId: String): CodexAppServerSessionBindingEntity? {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        return bindingDao.getByConversationId(conversationId)
    }

    suspend fun bindPersistentThread(
        conversationId: String,
        workspaceId: String,
        workspaceCwd: String,
        thread: CodexAppServerThreadSnapshot,
    ): CodexAppServerSessionBindingEntity {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(thread.id.isNotBlank()) { "threadId must not be blank" }
        validateRelativeCwd(workspaceCwd)
        check(conversationDao.getConversationById(conversationId) != null) {
            "Conversation $conversationId does not exist"
        }
        check(workspaceDao.getById(workspaceId) != null) { "Workspace $workspaceId does not exist" }

        val ephemeral = (thread.raw["ephemeral"] as? JsonPrimitive)?.booleanOrNull
        require(ephemeral == false) { "Only explicitly non-ephemeral Codex threads can be persisted" }
        val owner = bindingDao.getByThreadId(thread.id)
        check(owner == null || owner.conversationId == conversationId) {
            "Codex thread ${thread.id} is already bound to conversation ${owner?.conversationId}"
        }

        val existing = bindingDao.getByConversationId(conversationId)
        val now = nowMs()
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = conversationId,
            workspaceId = workspaceId,
            threadId = thread.id,
            workspaceCwd = workspaceCwd,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            // These observations belong to the old thread and are intentionally reset on rebind.
            lastObservedTurnId = null,
            lastObservedTurnStatus = null,
            lastResumedAtMs = null,
        )
        bindingDao.upsert(binding)
        return binding
    }

    suspend fun clearBinding(conversationId: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        bindingDao.deleteByConversationId(conversationId)
    }

    suspend fun recordTurnStarted(conversationId: String, turnId: String) =
        recordTurn(conversationId, turnId, "inProgress")

    suspend fun recordTurnCompleted(conversationId: String, turnId: String, status: String) =
        recordTurn(conversationId, turnId, status)

    suspend fun recordTurnCompleted(
        conversationId: String,
        turnId: String,
        status: CodexAppServerTurnStatus,
    ) = recordTurn(conversationId, turnId, status.wireValue)

    suspend fun markResumed(conversationId: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        check(bindingDao.updateLastResumed(conversationId, nowMs()) == 1) {
            "No Codex binding exists for conversation $conversationId"
        }
    }

    private suspend fun recordTurn(conversationId: String, turnId: String, status: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(status.isNotBlank()) { "status must not be blank" }
        check(bindingDao.updateLastObservedTurn(conversationId, turnId, status, nowMs()) == 1) {
            "No Codex binding exists for conversation $conversationId"
        }
    }

    private fun validateRelativeCwd(cwd: String) {
        require(!cwd.startsWith('/') && !cwd.startsWith('\\')) { "workspaceCwd must be relative" }
        require(cwd.split('/', '\\').none { it == ".." }) { "workspaceCwd must stay inside the workspace" }
    }
}
