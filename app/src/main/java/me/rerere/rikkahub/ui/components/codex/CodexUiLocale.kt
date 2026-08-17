package me.rerere.rikkahub.ui.components.codex

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Keeps the newly added Codex surface bilingual without changing App Server wire values.
 * LocalConfiguration follows the app's active locale, including per-app language overrides.
 */
@Composable
internal fun isJapaneseCodexUi(): Boolean =
    LocalConfiguration.current.locales[0].language == "ja"

internal fun codexText(
    japanese: Boolean,
    english: String,
    japaneseText: String,
): String = if (japanese) japaneseText else english
