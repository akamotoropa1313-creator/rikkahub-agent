package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-transient knowledge learned from an explicit model/list refresh. It is intentionally not
 * persisted: after process restart saved preferences remain durable, but new thread creation only
 * emits model-sensitive overrides that the current App Server catalog has confirmed again.
 *
 * The complete visible catalog is also retained as a process-local observable snapshot for the
 * unified model picker. This does not turn ChatGPT models into persisted RikkaHub providers: the
 * picker reads the authoritative App Server catalog while the assistant persists only its typed
 * model target.
 */
object CodexModelCatalogKnowledge {
    private val personalitySupport = ConcurrentHashMap<String, Boolean>()
    private val serviceTierSupport = ConcurrentHashMap<String, List<String>>()
    private val serviceTierMetadataKnown = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var defaultModel: String? = null

    private val mutableModels = MutableStateFlow<List<CodexAppServerModel>>(emptyList())
    val modelsFlow: StateFlow<List<CodexAppServerModel>> = mutableModels.asStateFlow()

    fun replace(models: List<CodexAppServerModel>) {
        personalitySupport.clear()
        serviceTierSupport.clear()
        serviceTierMetadataKnown.clear()
        val visibleModels = models.filterNot { it.hidden }.toList()
        mutableModels.value = visibleModels
        defaultModel = visibleModels.firstOrNull { it.isDefault }?.model
        visibleModels.forEach { model ->
            personalitySupport[model.model] = model.supportsPersonality
            model.serviceTierIds().confirmed?.let { confirmed ->
                serviceTierMetadataKnown += model.model
                serviceTierSupport[model.model] = confirmed
            }
        }
    }

    /** Immutable process-local snapshot for non-reactive consumers. */
    fun modelsSnapshot(): List<CodexAppServerModel> = mutableModels.value.toList()

    fun defaultModel(): String? = defaultModel

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
        mutableModels.value = emptyList()
    }
}
