package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Minimal local pointer to Codex-owned durable thread history. */
@Entity(
    tableName = "codex_app_server_session_bindings",
    indices = [
        Index(value = ["thread_id"], unique = true),
        Index(value = ["workspace_id"]),
        Index(value = ["updated_at_ms"]),
    ],
)
data class CodexAppServerSessionBindingEntity(
    @PrimaryKey @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("workspace_id") val workspaceId: String,
    @ColumnInfo("thread_id") val threadId: String,
    @ColumnInfo("workspace_cwd") val workspaceCwd: String,
    @ColumnInfo("last_observed_turn_id") val lastObservedTurnId: String? = null,
    @ColumnInfo("last_observed_turn_status") val lastObservedTurnStatus: String? = null,
    @ColumnInfo("created_at_ms") val createdAtMs: Long,
    @ColumnInfo("updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo("last_resumed_at_ms") val lastResumedAtMs: Long? = null,
    /** Hash of thread/start.dynamicTools; null means no client-hosted tools were attached. */
    @ColumnInfo("dynamic_tools_fingerprint") val dynamicToolsFingerprint: String? = null,
)
