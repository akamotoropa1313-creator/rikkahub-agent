package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexStage20TurnDiagnosticsTest {
    @Test
    fun `missing itemsView uses protocol Full default and explicit values remain distinct`() {
        val missing = decodeTurnSnapshot(turn())
        assertEquals(CodexAppServerTurnItemsView.Full, missing.itemsView)
        assertEquals(CodexAppServerTurnItemsView.NotLoaded, decodeTurnSnapshot(turn(itemsView = "notLoaded")).itemsView)
        assertEquals(CodexAppServerTurnItemsView.Summary, decodeTurnSnapshot(turn(itemsView = "summary")).itemsView)
        assertEquals(CodexAppServerTurnItemsView.Full, decodeTurnSnapshot(turn(itemsView = "full")).itemsView)
        assertEquals(CodexAppServerTurnItemsView.Unknown("future"), decodeTurnSnapshot(turn(itemsView = "future")).itemsView)
    }

    @Test
    fun `turn timing keeps seconds and milliseconds exactly`() {
        val decoded = decodeTurnSnapshot(buildJsonObject {
            put("id", "turn-1")
            put("status", "completed")
            put("startedAt", 1_700_000_000L)
            put("completedAt", 1_700_000_008L)
            put("durationMs", 8_432L)
        })
        assertEquals(1_700_000_000L, decoded.startedAt)
        assertEquals(1_700_000_008L, decoded.completedAt)
        assertEquals(8_432L, decoded.durationMs)
    }

    @Test
    fun `stable externally tagged HTTP errors preserve category and status`() {
        val categories = listOf(
            "httpConnectionFailed",
            "responseStreamConnectionFailed",
            "responseStreamDisconnected",
            "responseTooManyFailedAttempts",
        )
        categories.forEach { category ->
            val decoded = decodeTurnSnapshot(failedTurn(buildJsonObject {
                put(category, buildJsonObject { put("httpStatusCode", 503) })
            }))
            val info = decoded.error?.codexErrorInfo as CodexAppServerErrorInfo.Known
            assertEquals(category, info.category)
            assertEquals(503, info.httpStatusCode)
        }
    }

    @Test
    fun `tagged HTTP status may be null and active turn error remains known`() {
        val http = decodeTurnSnapshot(failedTurn(buildJsonObject {
            put("httpConnectionFailed", buildJsonObject { put("httpStatusCode", JsonNull) })
        })).error?.codexErrorInfo as CodexAppServerErrorInfo.Known
        assertNull(http.httpStatusCode)

        val steer = decodeTurnSnapshot(failedTurn(buildJsonObject {
            put("activeTurnNotSteerable", buildJsonObject { put("turnKind", "review") })
        })).error?.codexErrorInfo as CodexAppServerErrorInfo.Known
        assertEquals("activeTurnNotSteerable", steer.category)
        assertNull(steer.httpStatusCode)
    }

    @Test
    fun `unknown future error info remains forward compatible`() {
        val raw = buildJsonObject { put("futureError", buildJsonObject { put("value", 1) }) }
        val decoded = decodeTurnSnapshot(failedTurn(raw))
        val info = decoded.error?.codexErrorInfo
        assertTrue(info is CodexAppServerErrorInfo.Unknown)
        assertEquals(raw, info?.raw)
        assertEquals("details", decoded.error?.additionalDetails)
    }

    private fun turn(itemsView: String? = null) = buildJsonObject {
        put("id", "turn-1")
        put("status", "completed")
        itemsView?.let { put("itemsView", it) }
    }

    private fun failedTurn(info: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        put("id", "turn-1")
        put("status", "failed")
        put("error", buildJsonObject {
            put("message", "failed")
            put("codexErrorInfo", info)
            put("additionalDetails", "details")
        })
    }
}
