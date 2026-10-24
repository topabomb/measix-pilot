package net.weero.measix.pilot.data.db.fts

import me.rerere.common.configuration.ConfigurationReference

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FTS 增量索引权威测试。
 * 验证 node 级增量（reindexNodes / deleteNodesIndex）的投影一致性：
 * 单 node 变更只影响该 node、删除清空、增量终态 == 全量 rebuild。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MessageFtsManagerIncrementalTest {

    private lateinit var database: AppDatabase
    private lateinit var fts: MessageFtsManager
    private val seq = AtomicLong(0)

    private fun node(vararg texts: String): MessageNode {
        val messages = texts.map { t ->
            UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(t)))
        }
        return MessageNode(id = Uuid.random(), messages = messages, selectIndex = 0)
    }

    private fun text(n: MessageNode): String = n.messages.joinToString(" ") { m ->
        (m.parts.firstOrNull() as? UIMessagePart.Text)?.text ?: ""
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // This fixture covers transactional projection writes only. Real FTS5/simple search and
        // production factory/migration behavior are covered by MessageFtsMigrationAndroidTest.
        database.openHelper.writableDatabase.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(" +
                "text, node_id, message_id, conversation_id, title, update_at)"
        )
        fts = MessageFtsManager(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    /** 查询某 conversation 下某 node 的 FTS 文本行。 */
    private fun ftsRows(conversationId: String, nodeId: String): List<String> {
        val db = database.openHelper.writableDatabase
        val out = mutableListOf<String>()
        db.query(
            "SELECT text FROM message_fts WHERE conversation_id = '$conversationId' AND node_id = '$nodeId'"
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    private fun ftsCount(): Int {
        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM message_fts").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    @Test
    fun `F1 single node modification changes only that node rows`() = runTest {
        val conversationId = Uuid.random().toString()
        val nodeA = node("alpha original")
        val nodeB = node("bravo unchanged")
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA, nodeB))

        // 全量两 node 已索引
        assertEquals(listOf("alpha original"), ftsRows(conversationId, nodeA.id.toString()))
        assertEquals(listOf("bravo unchanged"), ftsRows(conversationId, nodeB.id.toString()))

        // 只改 nodeA 的文本 → 增量 reindex
        val nodeA2 = nodeA.copy(
            messages = listOf(UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("alpha changed")))),
        )
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA2))

        // nodeA 行更新为增量后的值；nodeB 行完全不受影响
        assertEquals(listOf("alpha changed"), ftsRows(conversationId, nodeA.id.toString()))
        assertEquals(listOf("bravo unchanged"), ftsRows(conversationId, nodeB.id.toString()))
        assertPaired(2)
    }

    @Test
    fun `F2 node deletion clears its index rows`() = runTest {
        val conversationId = Uuid.random().toString()
        val nodeA = node("alpha to delete")
        val nodeB = node("bravo keep")
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA, nodeB))
        assertEquals(2, ftsCount())

        fts.deleteNodesIndexInTransaction(conversationId, listOf(nodeA.id))
        assertEquals(0, ftsRows(conversationId, nodeA.id.toString()).size)
        assertEquals(listOf("bravo keep"), ftsRows(conversationId, nodeB.id.toString()))
        assertEquals(1, ftsCount())
        assertPaired(1)
    }

    @Test
    fun `F3 incremental result equals full rebuild`() = runTest {
        val conversation = Conversation.ofId(Uuid.random(), assistantId = ConfigurationReference.random())
            .copy(messageNodes = listOf(node("alpha one"), node("bravo two")), title = "t")
        val conversationId = conversation.id.toString()
        val nodeA = conversation.messageNodes[0]
        val nodeB = conversation.messageNodes[1]
        // 先全量，再增量更新 nodeA
        fts.indexConversationInTransaction(conversation)
        val nodeA2 = nodeA.copy(
            messages = listOf(UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("alpha updated")))),
        )
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA2))
        val incremental = ftsRows(conversationId, nodeA.id.toString()) + ftsRows(conversationId, nodeB.id.toString())

        // 全量重建同一终态
        fts.indexConversationInTransaction(
            conversation.copy(messageNodes = listOf(nodeA2, nodeB))
        )
        val rebuilt = ftsRows(conversationId, nodeA.id.toString()) + ftsRows(conversationId, nodeB.id.toString())

        assertEquals(rebuilt.sorted(), incremental.sorted())
        assertTrue(rebuilt.contains("alpha updated"))
        assertPaired(2)
    }

    private fun rows(sql: String): List<List<String>> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
            }
        }

    private fun assertPaired(expected: Int) {
        assertEquals(expected, ftsCount())
        assertEquals(expected, rows("SELECT fts_rowid FROM message_fts_rowid").size)
        assertEquals(expected, rows("""
            SELECT f.rowid FROM message_fts f JOIN message_fts_rowid r ON r.fts_rowid=f.rowid
            AND r.conversation_id=f.conversation_id AND r.node_id=f.node_id AND r.message_id=f.message_id
        """.trimIndent()).size)
    }

    @Test
    fun `all variants have distinct rowids and blank transitions preserve other nodes`() = runTest {
        val id = Uuid.random().toString()
        val variants = node("first variant", "second variant", "   ")
        val other = node("unchanged")
        database.withTransaction { fts.reindexNodesInTransaction(id, "title", 10, listOf(variants, other)) }
        assertPaired(3)
        assertEquals(variants.messages.take(2).map { it.id.toString() }.toSet(),
            rows("SELECT message_id FROM message_fts_rowid WHERE node_id='${variants.id}'").flatten().toSet())
        val untouched = rows("SELECT * FROM message_fts_rowid WHERE node_id='${other.id}'")
        val blank = variants.copy(messages = variants.messages.map { it.copy(parts = listOf(UIMessagePart.Text(""))) })
        database.withTransaction { fts.reindexNodesInTransaction(id, "title", 20, listOf(blank)) }
        assertPaired(1)
        assertEquals(untouched, rows("SELECT * FROM message_fts_rowid WHERE node_id='${other.id}'"))
        database.withTransaction { fts.reindexNodesInTransaction(id, "title", 30, listOf(variants)) }
        assertPaired(3)
        assertEquals(untouched, rows("SELECT * FROM message_fts_rowid WHERE node_id='${other.id}'"))
    }

    @Test
    fun `failed transaction rolls back deletion insertion and projection marker`() = runTest {
        val id = Uuid.random().toString()
        val original = node("retained", "retained alternative")
        database.withTransaction { fts.reindexNodesInTransaction(id, "before", 10, listOf(original)) }
        val beforeFts = rows("SELECT rowid,* FROM message_fts ORDER BY rowid")
        val beforeMapping = rows("SELECT * FROM message_fts_rowid ORDER BY fts_rowid")
        val failure = runCatching {
            database.withTransaction {
                fts.deleteConversationInTransaction(id)
                fts.reindexNodesInTransaction(id, "after", 20, listOf(node("replacement")))
                fts.markProjectionCurrentInTransaction()
                error("injected commit failure")
            }
        }.exceptionOrNull()
        assertEquals("injected commit failure", failure?.message)
        assertEquals(beforeFts, rows("SELECT rowid,* FROM message_fts ORDER BY rowid"))
        assertEquals(beforeMapping, rows("SELECT * FROM message_fts_rowid ORDER BY fts_rowid"))
        assertTrue(!fts.isProjectionCurrent())
        assertPaired(2)
    }

    @Test
    fun `mapping constraint failure midway through reindex restores both original tables`() = runTest {
        val id = Uuid.random().toString()
        val original = node("old first", "old second")
        database.withTransaction { fts.reindexNodesInTransaction(id, "before", 10, listOf(original)) }
        val beforeFts = rows("SELECT rowid,* FROM message_fts ORDER BY rowid")
        val beforeMapping = rows("SELECT * FROM message_fts_rowid ORDER BY fts_rowid")
        val duplicate = original.copy(messages = listOf(original.messages.first(), original.messages.first()))
        val failure = runCatching {
            database.withTransaction { fts.reindexNodesInTransaction(id, "after", 20, listOf(duplicate)) }
        }.exceptionOrNull()
        assertTrue("The unique message identity must reject the second insertion", failure != null)
        assertEquals(beforeFts, rows("SELECT rowid,* FROM message_fts ORDER BY rowid"))
        assertEquals(beforeMapping, rows("SELECT * FROM message_fts_rowid ORDER BY fts_rowid"))
        assertPaired(2)
    }

    @Test
    fun `metadata node conversation and global deletion keep both projections consistent`() = runTest {
        val first = Uuid.random().toString()
        val second = Uuid.random().toString()
        val a = node("a", "a alternative")
        val b = node("b")
        database.withTransaction {
            fts.reindexNodesInTransaction(first, "original", 10, listOf(a, b))
            fts.reindexNodesInTransaction(second, "other", 20, listOf(a))
        }
        val mapping = rows("SELECT * FROM message_fts_rowid ORDER BY fts_rowid")
        database.withTransaction { fts.updateConversationMetadataInTransaction(first, "renamed", null) }
        assertEquals(listOf(listOf("renamed", "10")), rows("SELECT DISTINCT title,update_at FROM message_fts WHERE conversation_id='$first'"))
        database.withTransaction { fts.updateConversationMetadataInTransaction(first, "latest", 30) }
        assertEquals(listOf(listOf("latest", "30")), rows("SELECT DISTINCT title,update_at FROM message_fts WHERE conversation_id='$first'"))
        assertEquals(listOf(listOf("other", "20")), rows("SELECT DISTINCT title,update_at FROM message_fts WHERE conversation_id='$second'"))
        assertEquals(mapping, rows("SELECT * FROM message_fts_rowid ORDER BY fts_rowid"))
        database.withTransaction { fts.deleteNodesIndexInTransaction(first, listOf(a.id)) }
        assertPaired(3)
        assertEquals(2, ftsRows(second, a.id.toString()).size)
        database.withTransaction { fts.deleteConversationInTransaction(first) }
        assertPaired(2)
        database.withTransaction { fts.deleteAllInTransaction() }
        assertPaired(0)
    }
}
