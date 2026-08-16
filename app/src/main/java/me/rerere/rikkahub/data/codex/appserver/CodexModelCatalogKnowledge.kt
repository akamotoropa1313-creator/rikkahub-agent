package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-transient knowledge learned from an explicit model/list refresh. It is intentionally not
 * persisted: after process restart personality remains a saved preference but is not sent until a
 * current App Server catalog has confirmed support again.
 */
object CodexModelCatalogKnowledge {
    private val personalitySupport = ConcurrentHashMap<String, Boolean>()

    fun replace(models: List<CodexAppServerModel>) {
        personalitySupport.clear()
        models.forEach { model -> personalitySupport[model.model] = model.supportsPersonality }
    }

    fun personalitySupported(model: String?): Boolean =
        model != null && personalitySupport[model] == true

    internal fun clearForTest() {
        personalitySupport.clear()
    }
}
