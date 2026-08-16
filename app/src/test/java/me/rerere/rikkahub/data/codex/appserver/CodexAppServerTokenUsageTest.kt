package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerTokenUsageTest {
    private fun breakdown(cacheWrite: Boolean = true) = buildJsonObject {
        put("totalTokens", Long.MAX_VALUE); put("inputTokens", 20L); put("cachedInputTokens", 3L)
        if (cacheWrite) put("cacheWriteInputTokens", 2L)
        put("outputTokens", 5L); put("reasoningOutputTokens", 1L); put("future", true)
    }
    private fun params(cacheWrite: Boolean = true, windowNull: Boolean = false) = buildJsonObject {
        put("threadId", "thread"); put("turnId", "turn")
        put("tokenUsage", buildJsonObject {
            put("total", breakdown(cacheWrite)); put("last", breakdown(cacheWrite))
            if (windowNull) put("modelContextWindow", JsonNull) else put("modelContextWindow", 200_000L)
            put("futureUsage", "preserved")
        })
    }

    @Test fun `decodes 0147 usage preserving long values and raw`() {
        val event = decodeTokenUsageEvent(params())
        assertEquals(Long.MAX_VALUE, event.tokenUsage.total.totalTokens)
        assertEquals(2L, event.tokenUsage.last.cacheWriteInputTokens)
        assertEquals(200_000L, event.tokenUsage.modelContextWindow)
        assertTrue("futureUsage" in event.tokenUsage.raw)
    }
    @Test fun `accepts 0144 missing cache write without fabricating zero`() {
        val event = decodeTokenUsageEvent(params(cacheWrite = false, windowNull = true))
        assertNull(event.tokenUsage.total.cacheWriteInputTokens)
        assertNull(event.tokenUsage.modelContextWindow)
    }
    @Test fun `rejects non integer numeric representations`() {
        listOf("\"123\"", "1.5", "true").forEach { invalid ->
            val json = Json.parseToJsonElement(invalid)
            val malformed = buildJsonObject {
                put("threadId", "thread"); put("turnId", "turn"); put("tokenUsage", buildJsonObject {
                    put("total", breakdown()); put("last", breakdown().let { buildJsonObject { it.forEach { (k,v) -> put(k,v) }; put("inputTokens", json) } })
                    put("modelContextWindow", JsonNull)
                })
            }
            assertThrows(CodexAppServerTurnProtocolException::class.java) { decodeTokenUsageEvent(malformed) }
        }
    }
    @Test fun `rejects malformed identifiers and required objects`() {
        assertThrows(CodexAppServerTurnProtocolException::class.java) { decodeTokenUsageEvent(buildJsonObject { put("threadId", 1); put("turnId", "t") }) }
        assertThrows(CodexAppServerTurnProtocolException::class.java) { decodeTokenUsageEvent(buildJsonObject { put("threadId", "t"); put("turnId", false); put("tokenUsage", buildJsonObject {}) }) }
    }
}
