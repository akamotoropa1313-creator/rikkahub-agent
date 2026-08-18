package me.rerere.rikkahub.ui.components.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexApprovalCardsTest {
    private val raw = buildJsonObject { put("secret", "must-not-render") }
    @Test fun `known command actions have friendly text without raw json`() {
        val actions = listOf(
            CodexAppServerCommandAction.Read("cat a", "a", "/a", raw) to "読み取り: /a",
            CodexAppServerCommandAction.ListFiles("find", "/work", raw) to "ファイル一覧: /work",
            CodexAppServerCommandAction.Search("rg q", "q", "/work", raw) to "検索: q（/work）",
            CodexAppServerCommandAction.UnknownCommand("custom", raw) to "コマンド: custom",
            CodexAppServerCommandAction.Other("future", raw) to "不明なコマンド操作",
        )
        actions.forEach { (action, expected) -> assertEquals(expected, commandActionPresentation(action)); assertFalse(commandActionPresentation(action).contains("secret")) }
    }
    @Test fun `missing preview disables approval but leaves rejection enabled`() {
        val state=approvalActionAvailability(true,false,false,false);assertFalse(state.approveEnabled);assertTrue(state.rejectEnabled)
    }
    @Test fun `preview permits approval while submitting and resolved disable all actions`() {
        assertTrue(approvalActionAvailability(true,false,false,true).approveEnabled)
        listOf(approvalActionAvailability(true,true,false,true),approvalActionAvailability(true,false,true,true)).forEach{assertFalse(it.approveEnabled);assertFalse(it.rejectEnabled)}
    }
}
