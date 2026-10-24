package net.weero.measix.pilot.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.storageKey
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    internal fun indexConversationInTransaction(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        deleteConversationInTransaction(conversationId)
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                insertMessage(conversationId, node.id.toString(), message, conversation.title, conversation.updateAt.toEpochMilli())
            }
        }
    }

    internal fun deleteConversationInTransaction(conversationId: String) {
        db.execSQL("DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)", arrayOf(conversationId))
        db.execSQL("DELETE FROM message_fts_rowid WHERE conversation_id = ?", arrayOf(conversationId))
    }

    /** 节点级增量：按 conversation/node 删除旧行，再以实际 message ID 插入当前文本。 */
    internal fun reindexNodesInTransaction(
        conversationId: String,
        title: String,
        updateAt: Long,
        nodes: List<MessageNode>,
    ) {
        nodes.forEach { node ->
            deleteNodeRows(conversationId, node.id.toString())
            node.messages.forEach { message ->
                insertMessage(conversationId, node.id.toString(), message, title, updateAt)
            }
        }
    }

    /** 节点删除的索引清理。 */
    internal fun deleteNodesIndexInTransaction(conversationId: String, nodeIds: List<kotlin.uuid.Uuid>) {
        nodeIds.forEach { nodeId ->
            deleteNodeRows(conversationId, nodeId.toString())
        }
    }

    private fun deleteNodeRows(conversationId: String, nodeId: String) {
        val args = arrayOf(conversationId, nodeId)
        db.execSQL("DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ? AND node_id = ?)", args)
        db.execSQL("DELETE FROM message_fts_rowid WHERE conversation_id = ? AND node_id = ?", args)
    }

    private fun insertMessage(conversationId: String, nodeId: String, message: UIMessage, title: String, updateAt: Long) {
        val text = message.extractFtsText()
        if (text.isBlank()) return
        val messageId = message.id.toString()
        val rowId = db.compileStatement(
            "INSERT INTO message_fts_rowid(conversation_id, node_id, message_id) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.bindString(1, conversationId)
            statement.bindString(2, nodeId)
            statement.bindString(3, messageId)
            statement.executeInsert()
        }
        check(rowId > 0) { "message_fts_rowid_insert_failed" }
        db.execSQL(
            "INSERT INTO message_fts(rowid, text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(rowId, text, nodeId, messageId, conversationId, title, updateAt.toString()),
        )
    }

    /** Updates the denormalized conversation title without rewriting message rows. */
    internal fun updateConversationMetadataInTransaction(
        conversationId: String,
        title: String,
        updateAt: Long?,
    ) {
        if (updateAt == null) {
            db.execSQL(
                "UPDATE message_fts SET title = ? WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)",
                arrayOf(title, conversationId),
            )
        } else {
            db.execSQL(
                "UPDATE message_fts SET title = ?, update_at = ? WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)",
                arrayOf(title, updateAt.toString(), conversationId),
            )
        }
    }

    internal fun deleteAllInTransaction() {
        db.execSQL("DELETE FROM message_fts")
        db.execSQL("DELETE FROM message_fts_rowid")
    }

    suspend fun isProjectionCurrent(): Boolean = withContext(Dispatchers.IO) {
        db.query("SELECT value FROM system_meta WHERE `key` = ?", arrayOf(PROJECTION_KEY)).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == PROJECTION_VERSION
        }
    }

    internal fun markProjectionCurrentInTransaction() {
        db.execSQL(
            "INSERT OR REPLACE INTO system_meta(`key`, value) VALUES (?, ?)",
            arrayOf(PROJECTION_KEY, PROJECTION_VERSION),
        )
    }

    suspend fun search(
        scope: ConfigurationScope,
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val assistantClause = if (assistantId == null) "" else "AND assistant_id = ?"
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            AND conversation_id IN (
                SELECT id FROM conversationentity WHERE scope = ? AND parent_conversation_id IS NULL $assistantClause
            )
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            if (assistantId == null) arrayOf(keyword, scope.storageKey()) else arrayOf(keyword, scope.storageKey(), assistantId)
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private const val PROJECTION_KEY = "message_fts_projection"
private const val PROJECTION_VERSION = "rowid-transactional-v2"

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
