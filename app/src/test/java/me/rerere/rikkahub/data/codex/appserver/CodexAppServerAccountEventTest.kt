package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerAccountEventTest {
    @Test fun `cold typed projection preserves nullable and future values and survives malformed`() = runBlocking {
        val source = MutableSharedFlow<CodexAppServerEvent>(); val typed = mutableListOf<CodexAppServerAccountEvent>(); val raw = mutableListOf<CodexAppServerEvent>()
        assertEquals(0, source.subscriptionCount.value)
        val typedJob = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerAccountEvents().collect { typed += it } }
        val rawJob = launch(start = CoroutineStart.UNDISPATCHED) { source.collect { raw += it } }
        source.emit(CodexAppServerEvent.UnknownNotification("unrelated", buildJsonObject {}))
        source.emit(CodexAppServerEvent.UnknownNotification("account/login/completed", buildJsonObject { put("loginId", JsonNull); put("success", "bad"); put("error", JsonNull); put("onboardingEntrypoint", "future") }))
        source.emit(CodexAppServerEvent.UnknownNotification("account/login/completed", buildJsonObject { put("loginId", JsonNull); put("success", false); put("error", "denied"); put("onboardingEntrypoint", "future") }))
        source.emit(CodexAppServerEvent.UnknownNotification("account/updated", buildJsonObject { put("authMode", "futureMode"); put("planType", "futurePlan") }))
        assertEquals(4, raw.size); assertEquals(3, typed.size)
        assertTrue(typed[0] is CodexAppServerAccountEvent.MalformedNotification)
        val completed = typed[1] as CodexAppServerAccountEvent.LoginCompleted
        assertEquals(null, completed.loginId); assertEquals("denied", completed.error)
        assertEquals("future", completed.onboardingEntrypoint?.toString()?.trim('"'))
        val updated = typed[2] as CodexAppServerAccountEvent.Updated
        assertTrue(updated.authMode is CodexAppServerAuthMode.Unknown); assertTrue(updated.planType is CodexAppServerPlanType.Unknown)
        typedJob.cancelAndJoin(); rawJob.cancelAndJoin()
    }
}
