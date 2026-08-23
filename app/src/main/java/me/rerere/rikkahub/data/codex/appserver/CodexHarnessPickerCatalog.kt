package me.rerere.rikkahub.data.codex.appserver

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting

/**
 * UI-neutral catalog consumed by the RikkaHub-style unified model sheet. ChatGPT models remain
 * process-transient App Server catalog entries; configured provider models keep their persistent
 * RikkaHub UUIDs. No synthetic ProviderSetting or UUID is created for account models.
 */
data class CodexHarnessPickerCatalog(
    val chatGptModels: List<CodexAppServerModel>,
    val providerGroups: List<CodexHarnessProviderGroup>,
)

data class CodexHarnessProviderGroup(
    val provider: ProviderSetting,
    val models: List<Model>,
)

object CodexHarnessPickerCatalogBuilder {
    fun build(
        providers: List<ProviderSetting>,
        chatGptModels: List<CodexAppServerModel> = CodexModelCatalogKnowledge.modelsSnapshot(),
    ): CodexHarnessPickerCatalog = CodexHarnessPickerCatalog(
        chatGptModels = chatGptModels.filterNot { it.hidden },
        providerGroups = providers.mapNotNull { provider ->
            if (!provider.enabled) return@mapNotNull null
            val chatModels = provider.models.filter { it.type == ModelType.CHAT }
            chatModels.takeIf { it.isNotEmpty() }?.let { CodexHarnessProviderGroup(provider, it) }
        },
    )

    fun selectedChatGptModel(
        target: CodexHarnessModelTarget,
        catalog: List<CodexAppServerModel> = CodexModelCatalogKnowledge.modelsSnapshot(),
    ): CodexAppServerModel? {
        val model = (target as? CodexHarnessModelTarget.ChatGptAccount)?.model ?: return null
        return catalog.firstOrNull { it.model == model }
    }

    fun matchesSearch(model: CodexAppServerModel, query: String): Boolean =
        query.isBlank() || model.displayName.contains(query, ignoreCase = true) ||
            model.model.contains(query, ignoreCase = true) ||
            model.description.contains(query, ignoreCase = true)

    fun matchesSearch(model: Model, query: String): Boolean =
        query.isBlank() || model.displayName.contains(query, ignoreCase = true) ||
            model.modelId.contains(query, ignoreCase = true)
}
