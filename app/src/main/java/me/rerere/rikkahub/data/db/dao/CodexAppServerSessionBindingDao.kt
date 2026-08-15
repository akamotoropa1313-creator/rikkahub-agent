package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity

@Dao
interface CodexAppServerSessionBindingDao {
    @Query("SELECT * FROM codex_app_server_session_bindings WHERE conversation_id = :conversationId")
    suspend fun getByConversationId(conversationId: String): CodexAppServerSessionBindingEntity?

    @Query("SELECT * FROM codex_app_server_session_bindings WHERE conversation_id = :conversationId")
    fun observeByConversationId(conversationId: String): Flow<CodexAppServerSessionBindingEntity?>

    @Query("SELECT * FROM codex_app_server_session_bindings WHERE thread_id = :threadId")
    suspend fun getByThreadId(threadId: String): CodexAppServerSessionBindingEntity?

    @Upsert
    suspend fun upsert(binding: CodexAppServerSessionBindingEntity)

    /** Returns -1 when either the conversation primary key or unique thread owner already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long

    @Query("UPDATE codex_app_server_session_bindings SET last_observed_turn_id = :turnId, last_observed_turn_status = :status, updated_at_ms = :updatedAtMs WHERE conversation_id = :conversationId AND thread_id = :expectedThreadId")
    suspend fun updateLastObservedTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String, updatedAtMs: Long): Int

    @Query("UPDATE codex_app_server_session_bindings SET last_resumed_at_ms = :resumedAtMs, updated_at_ms = :resumedAtMs WHERE conversation_id = :conversationId AND thread_id = :expectedThreadId")
    suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long): Int

    @Query("DELETE FROM codex_app_server_session_bindings WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
}
