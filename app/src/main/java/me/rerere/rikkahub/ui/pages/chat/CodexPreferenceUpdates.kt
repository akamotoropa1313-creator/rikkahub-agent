package me.rerere.rikkahub.ui.pages.chat

import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant

private val codexPreferenceLocks = WeakHashMap<ChatVM, Mutex>()
private val codexPreferenceLocksGuard = Any()

private fun ChatVM.codexPreferenceLock(): Mutex = synchronized(codexPreferenceLocksGuard) {
    codexPreferenceLocks.getOrPut(this) { Mutex() }
}

/**
 * Applies a field-level Codex preference delta to the newest assistant snapshot. Rapid taps are
 * serialized so a later control cannot restore stale values captured by an older composition.
 */
fun ChatVM.updateCodexPreferences(transform: (Assistant) -> Assistant) {
    viewModelScope.launch {
        codexPreferenceLock().withLock {
            val latestSettings = settings.value
            val latestConversation = conversation.value
            val latestAssistant = latestSettings.getAssistantById(latestConversation.assistantId)
                ?: latestSettings.getCurrentAssistant()
            val updated = transform(latestAssistant)
            check(updated.id == latestAssistant.id) { "Codex preference update cannot replace the assistant identity" }
            updateSettings(
                latestSettings.copy(
                    assistants = latestSettings.assistants.map { assistant ->
                        if (assistant.id == latestAssistant.id) updated else assistant
                    },
                ),
            ).join()
        }
    }
}
