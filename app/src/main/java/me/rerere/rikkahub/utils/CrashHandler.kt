package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 16_000
private const val TRUNCATION_MARKER = "\n... crash report truncated; root-cause tail preserved ...\n"

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = formatCrashStackTrace(thread.name, throwable)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }
}

/**
 * Keep crash reports bounded without discarding the deepest `Caused by` chain.
 *
 * Throwable.stackTraceToString() places wrapper frames first and root causes later. Keeping only
 * the prefix therefore loses the diagnostically useful part for deeply wrapped Koin/Compose
 * failures. Preserve both ends, biased toward the tail where the root cause normally lives.
 */
internal fun formatCrashStackTrace(
    threadName: String,
    throwable: Throwable,
    maxLength: Int = MAX_STACKTRACE_LENGTH,
): String {
    require(maxLength > TRUNCATION_MARKER.length + 2) { "maxLength is too small" }
    val full = buildString {
        appendLine("Thread: $threadName")
        append(throwable.stackTraceToString())
    }
    if (full.length <= maxLength) return full

    val payloadLength = maxLength - TRUNCATION_MARKER.length
    val headLength = payloadLength / 3
    val tailLength = payloadLength - headLength
    return full.take(headLength) + TRUNCATION_MARKER + full.takeLast(tailLength)
}
