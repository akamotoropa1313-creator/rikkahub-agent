package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-transient knowledge learned from an explicit model/list refresh. It is intentionally not
 * persisted: after process restart saved preferences remain durable, but new thread creation only
 * emits model-sensitive overrides that the current App Server catalog has confirmed again.
 */
object CodexModelCatalogKnowledge {
    private val personalitySupport = ConcurrentHashMap<String, Boolean>()
    private val serviceTierSupport = ConcurrentHashMap<String, List<String>>()
    private val serviceTierMetadataKnown = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var defaultModel: String? = null

    fun replace(models: List<CodexAppServerModel>) {
        personalitySupport.clear()
        serviceTierSupport.clear()
        serviceTierMetadataKnown.clear()
        defaultModel = models.firstOrNull { it.isDefault }?.model
        models.forEach { model ->
            personalitySupport[model.model] = model.supportsPersonality
            model.serviceTierIds().confirmed?.let { confirmed ->
                serviceTierMetadataKnown += model.model
                serviceTierSupport[model.model] = confirmed
            }
        }
    }

    fun personalitySupported(model: String?): Boolean =
        model != null && personalitySupport[model] == true

    /**
     * Returns true only when this process has catalog knowledge for the selected wire model and the
     * saved specific tier is explicitly advertised. A null model may resolve to the catalog's
     * explicit isDefault model; a missing explicit model never falls back. Unknown support is false
     * here because thread/start must not be made fragile by stale saved preferences before model
     * discovery is possible.
     */
    fun serviceTierConfirmed(model: String?, tier: String): Boolean {
        val resolvedModel = model ?: defaultModel ?: return false
        return resolvedModel in serviceTierMetadataKnown && serviceTierSupport[resolvedModel]?.contains(tier) == true
    }

    internal fun clearForTest() {
        personalitySupport.clear()
        serviceTierSupport.clear()
        serviceTierMetadataKnown.clear()
        defaultModel = null
    }
}
