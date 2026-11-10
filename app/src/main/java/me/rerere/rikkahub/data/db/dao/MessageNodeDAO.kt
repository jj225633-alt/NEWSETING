package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 消息节点数（重新生成的分支不另算）
    @Query("SELECT COUNT(*) FROM message_node")
    suspend fun countAll(): Int

    // 星汐首页：用 @RawQuery 绕过 Room 编译期校验，才能用 json_each() 虚拟表
    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageDayCount(val day: String, val count: Int)

/** 星汐首页「在一起的日子」：依她每则讯息的 createdAt（ISO 字串前 10 码就是日期）统计每天传了几则 */
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf<Any>(startDate),
        )
    )
