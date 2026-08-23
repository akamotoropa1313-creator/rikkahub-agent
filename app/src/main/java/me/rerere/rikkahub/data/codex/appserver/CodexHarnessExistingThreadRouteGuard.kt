package me.rerere.rikkahub.data.codex.appserver

/**
 * Validates the provider identity stored on an already-persisted Codex thread before resume.
 * This prevents a settings change from silently continuing old conversation history through a
 * different underlying RikkaHub provider/model route.
 */
sealed interface CodexHarnessExistingThreadRouteGuard {
    fun accepts(existingModelProvider: String?): Boolean

    data object NativeAccount : CodexHarnessExistingThreadRouteGuard {
        override fun accepts(existingModelProvider: String?): Boolean =
            existingModelProvider?.startsWith(CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX) != true
    }

    data class Gateway(val expectedModelProvider: String) : CodexHarnessExistingThreadRouteGuard {
        init {
            require(expectedModelProvider.startsWith(CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX))
        }

        override fun accepts(existingModelProvider: String?): Boolean =
            existingModelProvider == expectedModelProvider
    }

    companion object {
        fun from(projection: CodexHarnessThreadProjection): CodexHarnessExistingThreadRouteGuard =
            projection.modelProvider?.let(::Gateway) ?: NativeAccount
    }
}
