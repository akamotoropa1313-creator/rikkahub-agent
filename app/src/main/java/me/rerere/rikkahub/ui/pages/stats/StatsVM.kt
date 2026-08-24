package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.HarnessTokenStats
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getHarnessTokenStats
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.harness.AgentHarnessIds
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    val harnessUsage: List<HarnessUsageStats> = emptyList(),
)

data class HarnessUsageStats(
    val harnessId: String,
    val runCount: Int = 0,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()
        val persistedHarnessStats = messageNodeDAO.getHarnessTokenStats()

        val settings = settingsStore.settingsFlow.value
        val launchCount = settings.launchCount
        val configuredHarnessIds = buildList {
            if (settings.assistants.any { it.codexAppServerEnabled }) add(AgentHarnessIds.CODEX)
        }

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
            harnessUsage = mergeHarnessUsage(persistedHarnessStats, configuredHarnessIds),
        )
    }
}

/** Keeps configured zero-usage harnesses visible and accepts unknown future ids from storage. */
internal fun mergeHarnessUsage(
    persisted: List<HarnessTokenStats>,
    configuredHarnessIds: List<String>,
): List<HarnessUsageStats> {
    val byId = persisted.associateBy { it.harnessId }
    val orderedIds = buildList {
        configuredHarnessIds.forEach { id -> if (id.isNotBlank() && id !in this) add(id) }
        persisted.forEach { row -> if (row.harnessId.isNotBlank() && row.harnessId !in this) add(row.harnessId) }
    }
    return orderedIds.map { id ->
        val row = byId[id]
        HarnessUsageStats(
            harnessId = id,
            runCount = row?.runCount ?: 0,
            promptTokens = row?.promptTokens ?: 0L,
            completionTokens = row?.completionTokens ?: 0L,
            cachedTokens = row?.cachedTokens ?: 0L,
            reasoningTokens = row?.reasoningTokens ?: 0L,
            cacheWriteTokens = row?.cacheWriteTokens ?: 0L,
        )
    }
}
