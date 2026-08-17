package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashHandlerTest {
    @Test
    fun `long crash report preserves deepest cause`() {
        var throwable: Throwable = IllegalStateException("ROOT_CAUSE_SENTINEL")
        repeat(40) { depth ->
            throwable = RuntimeException("wrapper-$depth", throwable).apply {
                stackTrace = Array(20) { frame ->
                    StackTraceElement(
                        "example.wrapper.Depth$depth",
                        "method$frame",
                        "Depth$depth.kt",
                        depth * 100 + frame + 1,
                    )
                }
            }
        }
        throwable.causeChainRoot().stackTrace = Array(20) { frame ->
            StackTraceElement("example.root.RootCause", "root$frame", "RootCause.kt", frame + 1)
        }

        val report = formatCrashStackTrace("main", throwable, maxLength = 1_200)

        assertTrue(report.length <= 1_200)
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("crash report truncated"))
        assertTrue(report.contains("ROOT_CAUSE_SENTINEL"))
        assertTrue(report.contains("example.root.RootCause"))
    }

    @Test
    fun `short crash report is not marked truncated`() {
        val report = formatCrashStackTrace("worker", IllegalArgumentException("small"), maxLength = 4_000)

        assertTrue(report.contains("Thread: worker"))
        assertTrue(report.contains("IllegalArgumentException: small"))
        assertFalse(report.contains("crash report truncated"))
    }

    private fun Throwable.causeChainRoot(): Throwable = generateSequence(this) { it.cause }.last()
}
