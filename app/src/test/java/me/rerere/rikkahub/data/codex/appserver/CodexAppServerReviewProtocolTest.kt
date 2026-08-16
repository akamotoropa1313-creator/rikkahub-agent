package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerReviewProtocolTest {
    @Test
    fun `review start decoder rejects malformed response shapes and ids`() {
        val malformed = listOf(
            JsonNull,
            JsonPrimitive("bad"),
            JsonArray(emptyList()),
            buildJsonObject { },
            buildJsonObject { put("turn", "bad") },
            buildJsonObject { put("turn", buildJsonObject { put("id", "turn-1"); put("status", "inProgress") }) },
            buildJsonObject {
                put("turn", buildJsonObject { put("id", "turn-1"); put("status", "inProgress") })
                put("reviewThreadId", "")
            },
            buildJsonObject {
                put("turn", buildJsonObject { put("id", "turn-1"); put("status", "inProgress") })
                put("reviewThreadId", 123)
            },
            buildJsonObject {
                put("turn", buildJsonObject { put("id", 123); put("status", "inProgress") })
                put("reviewThreadId", "thread-1")
            },
            buildJsonObject {
                put("turn", buildJsonObject { put("id", "turn-1") })
                put("reviewThreadId", "thread-1")
            },
        )

        malformed.forEach { value ->
            expect<CodexAppServerTurnProtocolException> { decodeReviewStartResult(value) }
        }
    }

    @Test
    fun `review start decoder retains future turn status and exact review thread id`() {
        val decoded = decodeReviewStartResult(buildJsonObject {
            put("turn", buildJsonObject {
                put("id", "turn-1")
                put("status", "futureReviewStatus")
                put("future", true)
            })
            put("reviewThreadId", "thread-1")
            put("futureResponseField", 42)
        })

        assertEquals("turn-1", decoded.turn.id)
        assertEquals(CodexAppServerTurnStatus.Unknown("futureReviewStatus"), decoded.turn.status)
        assertEquals("thread-1", decoded.reviewThreadId)
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    } catch (failure: Throwable) {
        if (failure is T) failure else throw failure
    }
}
