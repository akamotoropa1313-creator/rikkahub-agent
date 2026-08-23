package me.rerere.rikkahub.data.codex.appserver

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

data class CodexHarnessModelPresentation(
    val providerName: String,
    val modelName: String,
    val model: Model? = null,
    val available: Boolean = true,
) {
    val compactLabel: String
        get() = if (providerName.isBlank()) modelName else "$providerName · $modelName"
}

/**
 * UI-neutral presentation resolver for the persisted typed harness target. Keeping provider/model
 * lookup here prevents the Assistant picker, composer and diagnostics from each inventing their
 * own fallback semantics.
 */
object CodexHarnessModelPresentationResolver {
    fun resolve(
        target: CodexHarnessModelTarget,
        providers: List<ProviderSetting>,
        chatGptModels: List<CodexAppServerModel>,
    ): CodexHarnessModelPresentation = when (target) {
        is CodexHarnessModelTarget.ChatGptAccount -> {
            if (target.model == null) {
                CodexHarnessModelPresentation(
                    providerName = "ChatGPT",
                    modelName = "サーバー既定",
                )
            } else {
                val catalogModel = chatGptModels.firstOrNull { it.model == target.model }
                CodexHarnessModelPresentation(
                    providerName = "ChatGPT",
                    modelName = catalogModel?.displayName ?: target.model,
                    available = catalogModel != null || chatGptModels.isEmpty(),
                )
            }
        }

        is CodexHarnessModelTarget.RikkaHubProvider -> {
            providers.firstNotNullOfOrNull { provider ->
                provider.models.firstOrNull { it.id == target.modelId }?.let { model ->
                    CodexHarnessModelPresentation(
                        providerName = provider.name,
                        modelName = model.displayName.ifBlank { model.modelId },
                        model = model,
                    )
                }
            } ?: CodexHarnessModelPresentation(
                providerName = "RikkaHub",
                modelName = "利用できないモデル",
                available = false,
            )
        }
    }
}
