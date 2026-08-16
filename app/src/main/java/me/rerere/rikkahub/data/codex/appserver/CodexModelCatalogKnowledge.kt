package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-transient knowledge learned from an explicit model/list refresh. It is intentionally not
 * persisted: after process restart saved preferences remain durable, but new thread creation only
 * emits model-sensitive overrides that the current App Server catalog has confirmed again.
 */
object CodexModelCatalogKnowledge {
    private val personalitySupport = ConcurrentHashMap<String, Boolean>()
    private val serviceTierSupport = ConcurrentHashMap<String, List<String>?>()

    fun replace(models: List<CodexAppServerModel>) {
        personalitySupport.clear()
        serviceTierSupport.clear()
        models.forEach { model ->
            personalitySupport[model.model] = model.supportsPersonality
            serviceTierSupport[model.model] = model.serviceTierIds().confirmed
        }
    }

    fun personalitySupported(model: String?): Boolean =
        model != null && personalitySupport[model] == true

    /**
     * Returns true only when this process has catalog knowledge for [model] and the saved specific
     * tier is explicitly advertised. Unknown support is false here because thread/start must not
     * be made fragile by stale saved preferences before model discovery is possible.
     */
    fun serviceTierConfirmed(model: String?, tier: String): Boolean =
        model != null && serviceTierSupport.containsKey(model) && serviceTierSupport[model]?.contains(tier) == true

    internal fun clearForTest() {
        personalitySupport.clear()
        serviceTierSupport.clear()
    }
}
