package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity

class CodexAppServerSessionBindingRepository(
    private val bindingDao: CodexAppServerSessionBindingDao,
    private val localState: CodexAppServerLocalState,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun createPersistentThreadBinding(
        conversationId: String,
        workspaceId: String,
        workspaceCwd: String,
        thread: CodexAppServerThreadSnapshot,
    ): CodexAppServerSessionBindingEntity {
        val binding = persistentBinding(conversationId, workspaceId, workspaceCwd, thread, null)
        if (bindingDao.insertIfAbsent(binding) == -1L) {
            throw CodexAppServerBindingConflictException(conversationId, thread.id)
        }
        return binding
    }

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
        val existing = bindingDao.getByConversationId(conversationId)
        val binding = persistentBinding(conversationId, workspaceId, workspaceCwd, thread, existing)
        val owner = bindingDao.getByThreadId(thread.id)
        check(owner == null || owner.conversationId == conversationId) {
            "Codex thread ${thread.id} is already bound to conversation ${owner?.conversationId}"
        }

        bindingDao.upsert(binding)
        return binding
    }

    private suspend fun persistentBinding(
        conversationId: String,
        workspaceId: String,
        workspaceCwd: String,
        thread: CodexAppServerThreadSnapshot,
        existing: CodexAppServerSessionBindingEntity?,
    ): CodexAppServerSessionBindingEntity {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(thread.id.isNotBlank()) { "threadId must not be blank" }
        validateRelativeCwd(workspaceCwd)
        check(localState.conversationExists(conversationId)) { "Conversation $conversationId does not exist" }
        check(localState.getWorkspace(workspaceId) != null) { "Workspace $workspaceId does not exist" }
        val ephemeral = thread.raw["ephemeral"] as? JsonPrimitive
        require(ephemeral != null && !ephemeral.isString && ephemeral.booleanOrNull == false) {
            "Only an explicitly boolean false ephemeral field can be persisted"
        }
        val now = nowMs()
        return CodexAppServerSessionBindingEntity(
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
    }

    suspend fun clearBinding(conversationId: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        bindingDao.deleteByConversationId(conversationId)
    }

    suspend fun recordTurnStarted(conversationId: String, expectedThreadId: String, turnId: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(expectedThreadId.isNotBlank()) { "expectedThreadId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        if (bindingDao.transitionTurnStarted(conversationId, expectedThreadId, turnId, nowMs()) == -1) {
            throw CodexAppServerBindingChangedException(conversationId, expectedThreadId)
        }
    }

    suspend fun recordTurnCompleted(conversationId: String, expectedThreadId: String, turnId: String, status: String) =
        recordTurn(conversationId, expectedThreadId, turnId, status)

    suspend fun recordTurnCompleted(
        conversationId: String,
        expectedThreadId: String,
        turnId: String,
        status: CodexAppServerTurnStatus,
    ) = recordTurn(conversationId, expectedThreadId, turnId, status.wireValue)

    suspend fun markResumed(conversationId: String, expectedThreadId: String): Long {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(expectedThreadId.isNotBlank()) { "expectedThreadId must not be blank" }
        val resumedAtMs = nowMs()
        if (bindingDao.updateLastResumed(conversationId, expectedThreadId, resumedAtMs) != 1) {
            throw CodexAppServerBindingChangedException(conversationId, expectedThreadId)
        }
        return resumedAtMs
    }

    private suspend fun recordTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(expectedThreadId.isNotBlank()) { "expectedThreadId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(status.isNotBlank()) { "status must not be blank" }
        if (bindingDao.updateLastObservedTurn(conversationId, expectedThreadId, turnId, status, nowMs()) != 1) {
            throw CodexAppServerBindingChangedException(conversationId, expectedThreadId)
        }
    }

    private fun validateRelativeCwd(cwd: String) {
        require(!cwd.startsWith('/') && !cwd.startsWith('\\')) { "workspaceCwd must be relative" }
        require(cwd.split('/', '\\').none { it == ".." }) { "workspaceCwd must stay inside the workspace" }
    }
}

class CodexAppServerBindingChangedException(conversationId: String, expectedThreadId: String) :
    IllegalStateException("Binding for conversation $conversationId no longer owns thread $expectedThreadId")

class CodexAppServerBindingConflictException(conversationId: String, threadId: String) :
    IllegalStateException("Conversation $conversationId or thread $threadId was bound concurrently")

interface CodexAppServerLocalState {
    suspend fun conversationExists(id: String): Boolean
    suspend fun getWorkspace(id: String): WorkspaceEntity?
}

class RoomCodexAppServerLocalState(
    private val conversationDao: ConversationDAO,
    private val workspaceDao: WorkspaceDAO,
) : CodexAppServerLocalState {
    override suspend fun conversationExists(id: String) = conversationDao.existsById(id)
    override suspend fun getWorkspace(id: String) = workspaceDao.getById(id)
}
