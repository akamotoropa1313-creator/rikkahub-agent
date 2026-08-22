package me.rerere.rikkahub.data.codex.appserver

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.dao.ConversationDAO

/**
 * Resolves the currently persisted conversation's Assistant into the thread-level Codex harness
 * projection. Keeping this at the App Server opener boundary makes all start/recovery call sites
 * use the same typed model target without duplicating routing logic inside ChatService.
 */
class CodexHarnessConversationProjectionResolver(
    private val conversationDao: ConversationDAO,
    private val settingsStore: SettingsStore,
    private val threadConfigurationResolver: CodexHarnessThreadConfigurationResolver,
) {
    suspend fun resolve(conversationId: String): CodexHarnessThreadProjection? {
        val conversation = conversationDao.getConversationById(conversationId) ?: return null
        val settings = settingsStore.settingsFlow.value
        val assistantId = runCatching { Uuid.parse(conversation.assistantId) }.getOrNull()
        val assistant = assistantId?.let(settings::getAssistantById) ?: settings.getCurrentAssistant()
        if (!assistant.codexAppServerEnabled) return null
        return threadConfigurationResolver.resolve(conversationId, assistant, settings)
    }

    fun release(conversationId: String) {
        threadConfigurationResolver.release(conversationId)
    }
}
