package net.weero.measix.pilot.data.db.fts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.storageKey
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.Conversation
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Uses the actual native simple tokenizer; no Framework SQLite/FTS4 substitution. */
@RunWith(AndroidJUnit4::class)
class MessageFtsMigrationAndroidTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), RequerySQLiteOpenHelperFactory(listOf(
            RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                options.customExtensions.add(SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null))
                options
            }
        )))

    @Test
    fun version16UpgradePreservesRecordsAndRebuildsTheInvalidatedProjection() = runBlocking {
        val name = "fts-rowid-upgrade"
        val freshName = "fts-rowid-fresh"
        context.deleteDatabase(name)
        context.deleteDatabase(freshName)
        val id = Uuid.random().toString()
        val node = node("searchable original", "searchable alternate")
        val payload = JsonInstant.encodeToString(node.messages)
        helper.createDatabase(name, 16).use { db ->
            // Reproduce the released v16 virtual table, independent of the new schema helper.
            db.execSQL("""CREATE VIRTUAL TABLE message_fts USING fts5(
                text,node_id UNINDEXED,message_id UNINDEXED,conversation_id UNINDEXED,
                title UNINDEXED,update_at UNINDEXED,tokenize='simple')""")
            insertConversation(db, id, ConfigurationScope.Personal)
            db.execSQL("INSERT INTO turn_execution(turn_id,conversation_id,assistant_message_id,status,reason,created_at,updated_at) VALUES('legacy-turn',?,'legacy-message','COMPLETED',NULL,1,2)", arrayOf(id))
            db.execSQL("INSERT INTO tool_execution(execution_id,turn_id,step_id,local_call_id,status,reason,created_at,updated_at) VALUES('legacy-tool','legacy-turn','legacy-step','legacy-call','COMPLETED',NULL,1,2)")
            db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index,transcript_schema) VALUES(?,?,0,?,0,3)",
                arrayOf(node.id.toString(), id, payload))
            db.execSQL("INSERT INTO message_fts(text,node_id,message_id,conversation_id,title,update_at) VALUES(?,?,?,?,?,?)",
                arrayOf("stale legacy", node.id.toString(), node.messages.first().id.toString(), id, "legacy", "10"))
            db.execSQL("INSERT INTO system_meta(`key`,`value`) VALUES('message_fts_projection','transactional-v1')")
        }
        val upgraded = createAppDatabase(context, name)
        val fresh = createAppDatabase(context, freshName)
        try {
            val db = upgraded.openHelper.writableDatabase
            val manager = MessageFtsManager(upgraded)
            val legacy = upgraded.toolExecutionDao().getById("legacy-tool")!!
            assertEquals(net.weero.measix.pilot.data.db.entity.ToolExecutionStatus.COMPLETED, legacy.status)
            assertNull(legacy.durationMillis)
            assertEquals(rows(fresh.openHelper.writableDatabase, "PRAGMA table_info(tool_execution)"),
                rows(db, "PRAGMA table_info(tool_execution)"))
            assertEquals(listOf(listOf(id, "retained")), rows(db, "SELECT id,title FROM ConversationEntity"))
            assertEquals(listOf(listOf(node.id.toString(), payload, "0", "3")),
                rows(db, "SELECT id,messages,select_index,transcript_schema FROM message_node"))
            assertFalse(manager.isProjectionCurrent())
            assertEquals(emptyList<List<String>>(), rows(db, "SELECT * FROM message_fts_rowid"))
            assertEquals(schema(fresh.openHelper.writableDatabase), schema(db))
            upgraded.withTransaction {
                manager.deleteAllInTransaction()
                manager.reindexNodesInTransaction(id, "retained", 10, listOf(node))
                manager.markProjectionCurrentInTransaction()
            }
            assertTrue(manager.isProjectionCurrent())
            assertPaired(db, 2)
            assertEquals(node.messages.map { it.id.toString() }.toSet(),
                manager.search(ConfigurationScope.Personal, "searchable").map { it.messageId }.toSet())
            assertTrue(manager.search(ConfigurationScope.Personal, "stale").isEmpty())
        } finally {
            upgraded.close()
            fresh.close()
            context.deleteDatabase(name)
            context.deleteDatabase(freshName)
        }
    }

    @Test
    fun freshNativeSearchFiltersScopeAssistantAndChildrenBeforeLimit() = runBlocking {
        val name = "fts-rowid-search"
        context.deleteDatabase(name)
        val room = createAppDatabase(context, name)
        try {
            val manager = MessageFtsManager(room)
            val db = room.openHelper.writableDatabase
            val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("fts-test"), "employee")
            val personalId = Uuid.random().toString()
            val enterpriseIds = mutableSetOf<String>()
            room.withTransaction {
                insertConversation(db, personalId, ConfigurationScope.Personal)
                manager.reindexNodesInTransaction(personalId, "personal title", 1,
                    listOf(node("needle 人工智能", "needle alternative")))
                // More than LIMIT 50 foreign results must not crowd personal results out.
                repeat(55) {
                    val id = Uuid.random().toString()
                    enterpriseIds.add(id)
                    insertConversation(db, id, enterprise)
                    manager.reindexNodesInTransaction(id, "enterprise title", 100, listOf(node("needle")))
                }
                val child = Uuid.random().toString()
                insertConversation(db, child, ConfigurationScope.Personal, parent = personalId)
                manager.reindexNodesInTransaction(child, "hidden child", 100, listOf(node("needle")))
                val other = Uuid.random().toString()
                insertConversation(db, other, ConfigurationScope.Personal, assistant = "other")
                manager.reindexNodesInTransaction(other, "other assistant", 100, listOf(node("needle")))
            }
            assertPaired(db, 59)
            val personal = manager.search(ConfigurationScope.Personal, "needle", assistantId = "assistant")
            assertEquals(2, personal.size)
            assertEquals(setOf(personalId), personal.map { it.conversationId }.toSet())
            assertTrue(personal.all { it.title == "personal title" && it.snippet.contains("[needle]") })
            assertEquals(1, manager.search(ConfigurationScope.Personal, "人工智能").size)
            val managed = manager.search(enterprise, "needle")
            assertEquals(50, managed.size)
            assertTrue(managed.all { it.conversationId in enterpriseIds })
            assertEquals(3, manager.search(ConfigurationScope.Personal, "needle").size)
            room.withTransaction { manager.deleteConversationInTransaction(personalId) }
            assertPaired(db, 57)
            assertTrue(manager.search(ConfigurationScope.Personal, "needle", assistantId = "assistant").isEmpty())
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun longConversationIncrementalIndexTransactionCost() = runBlocking<Unit> {
        val runId = Uuid.random().toString()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "fts-index-cost-$runId").apply { check(mkdirs()) }
        val results = JSONArray()
        val report = JSONObject()
            .put("measurement", "Room FTS index transaction through commit; not complete Turn latency")
            .put("clock", "elapsedRealtimeNanos")
            .put("warmupRounds", 3).put("measuredRounds", 10)
            .put("variantsPerNode", 2)
            .put("preparationExcluded", "database creation, source text, baseline indexing, source-row update, validation")
            .put("samples", results)
        val reportFile = File(output, "index-transactions.json")
        Log.i("MessageFtsCost", "FTS transaction report: ${reportFile.absolutePath}")
        try {
            for (nodeCount in listOf(100, 1000)) {
                val databaseName = "fts-cost-$runId-$nodeCount"
                val room = createAppDatabase(context, databaseName)
                try {
                    val db = room.openHelper.writableDatabase
                    val manager = MessageFtsManager(room)
                    val id = Uuid.random()
                    val assistant = ConfigurationReference.random()
                    val repeatedText = "Mixed language indexing 中文会话检索 保留历史正文. ".repeat(20)
                    fun text(token: String, variant: Int): String {
                        val prefix = "$token variant$variant "
                        val body = StringBuilder(prefix)
                        var bytes = prefix.toByteArray(Charsets.UTF_8).size
                        for (character in repeatedText) {
                            val width = when {
                                character.code <= 0x7f -> 1
                                character.code <= 0x7ff -> 2
                                else -> 3 // The fixed fixture contains only BMP characters.
                            }
                            if (bytes + width > 1024) break
                            body.append(character)
                            bytes += width
                        }
                        return body.toString()
                    }
                    val nodes = List(nodeCount) { index ->
                        val token = if (index == nodeCount - 1) "ftsoldtail" else "stableentry$index"
                        node(text(token, 0), text(token, 1))
                    }
                    val original = Conversation(id = id, assistantId = assistant, title = "FTS cost",
                        createAt = Instant.ofEpochMilli(1), updateAt = Instant.ofEpochMilli(1), messageNodes = nodes)
                    val last = nodes.last()
                    val updatedLast = last.copy(messages = last.messages.map { message ->
                        message.copy(parts = message.parts.map { part ->
                            if (part is UIMessagePart.Text) part.copy(text = part.text.replace("ftsoldtail", "ftsnewtail")) else part
                        })
                    })
                    val updated = original.copy(messageNodes = nodes.dropLast(1) + updatedLast)
                    val originalPayload = JsonInstant.encodeToString(last.messages)
                    val updatedPayload = JsonInstant.encodeToString(updatedLast.messages)
                    val lastId = last.id.toString()
                    val semanticSql = "SELECT text,node_id,message_id,conversation_id,title,update_at FROM message_fts ORDER BY message_id"
                    val untouchedSql = "SELECT rowid,text,node_id,message_id,conversation_id,title,update_at FROM message_fts WHERE node_id!='$lastId' ORDER BY message_id"
                    val sourceSql = "SELECT id,messages,select_index,transcript_schema FROM message_node WHERE id!='$lastId' ORDER BY id"
                    room.withTransaction {
                        insertConversation(db, id.toString(), ConfigurationScope.Personal, assistant.toString())
                        nodes.forEachIndexed { index, source ->
                            db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index,transcript_schema) VALUES(?,?,?,?,0,3)",
                                arrayOf<Any>(source.id.toString(), id.toString(), index, JsonInstant.encodeToString(source.messages)))
                        }
                    }
                    val untouchedSource = rows(db, sourceSql)
                    val byteSizes = nodes.flatMap { it.messages }.map { message ->
                        message.parts.filterIsInstance<UIMessagePart.Text>().single().text.toByteArray(Charsets.UTF_8).size
                    }
                    assertTrue(byteSizes.all { it in 1000..1024 })
                    repeat(13) { round ->
                        val order = if (round % 2 == 0) listOf("incremental", "full") else listOf("full", "incremental")
                        var equivalentRows: List<List<String>>? = null
                        order.forEach { mode ->
                            // Both paths begin with the same indexed source. Their setup is not timed.
                            room.withTransaction {
                                db.execSQL("UPDATE message_node SET messages=? WHERE id=?", arrayOf(originalPayload, lastId))
                                manager.indexConversationInTransaction(original)
                            }
                            assertPaired(db, nodeCount * 2)
                            assertEquals(2, manager.search(ConfigurationScope.Personal, "ftsoldtail").size)
                            assertTrue(manager.search(ConfigurationScope.Personal, "ftsnewtail").isEmpty())
                            val untouchedIndex = rows(db, untouchedSql)
                            room.withTransaction {
                                db.execSQL("UPDATE message_node SET messages=? WHERE id=?", arrayOf(updatedPayload, lastId))
                            }
                            val start = SystemClock.elapsedRealtimeNanos()
                            room.withTransaction {
                                if (mode == "incremental") {
                                    manager.reindexNodesInTransaction(id.toString(), original.title,
                                        original.updateAt.toEpochMilli(), listOf(updatedLast))
                                } else {
                                    manager.indexConversationInTransaction(updated)
                                }
                            }
                            val elapsed = SystemClock.elapsedRealtimeNanos() - start
                            // Validate outside the clock, including identity preservation of untouched rows.
                            assertPaired(db, nodeCount * 2)
                            assertEquals(untouchedSource, rows(db, sourceSql))
                            if (mode == "incremental") assertEquals(untouchedIndex, rows(db, untouchedSql))
                            assertTrue(manager.search(ConfigurationScope.Personal, "ftsoldtail").isEmpty())
                            assertEquals(updatedLast.messages.map { it.id.toString() }.toSet(),
                                manager.search(ConfigurationScope.Personal, "ftsnewtail").map { it.messageId }.toSet())
                            val actualRows = rows(db, semanticSql)
                            if (equivalentRows == null) equivalentRows = actualRows else assertEquals(equivalentRows, actualRows)
                            if (round >= 3) {
                                results.put(JSONObject().put("nodes", nodeCount).put("rows", nodeCount * 2)
                                    .put("utf8BytesPerVariantMin", byteSizes.min()).put("utf8BytesPerVariantMax", byteSizes.max())
                                    .put("iteration", round - 3).put("order", JSONArray(order))
                                    .put("mode", mode).put("elapsedNanos", elapsed))
                            }
                        }
                    }
                } finally {
                    room.close()
                    context.deleteDatabase(databaseName)
                }
            }
            report.put("completed", true)
        } catch (failure: Throwable) {
            report.put("completed", false).put("failure", failure.stackTraceToString())
            throw failure
        } finally {
            reportFile.writeText(report.toString(2))
            Log.i("MessageFtsCost", "FTS transaction report saved: ${reportFile.absolutePath}; samples=${results.length()}")
        }
    }

    private fun node(vararg texts: String) = MessageNode(id = Uuid.random(), selectIndex = 0,
        messages = texts.map { UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(it))) })

    private fun insertConversation(db: SupportSQLiteDatabase, id: String, scope: ConfigurationScope,
        assistant: String = "assistant", parent: String? = null) {
        db.execSQL("""INSERT INTO ConversationEntity
            (id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope,parent_conversation_id)
            VALUES(?,?,'retained',1,1,'[]',0,?,?)""", arrayOf(id, assistant, scope.storageKey(), parent))
    }

    private fun schema(db: SupportSQLiteDatabase): List<List<String>> =
        rows(db, "PRAGMA table_info(message_fts_rowid)") +
            rows(db, "SELECT name,sql FROM sqlite_master WHERE tbl_name='message_fts_rowid' AND type='index' ORDER BY name") +
            rows(db, "SELECT sql FROM sqlite_master WHERE name='message_fts'").map { row ->
                row.map { it.replace(Regex("\\s+"), "").lowercase() }
            }

    private fun assertPaired(db: SupportSQLiteDatabase, count: Int) {
        assertEquals(count.toString(), rows(db, "SELECT COUNT(*) FROM message_fts").single().single())
        assertEquals(count.toString(), rows(db, "SELECT COUNT(*) FROM message_fts_rowid").single().single())
        assertEquals(count.toString(), rows(db, """SELECT COUNT(*) FROM message_fts f
            JOIN message_fts_rowid r ON r.fts_rowid=f.rowid AND r.conversation_id=f.conversation_id
            AND r.node_id=f.node_id AND r.message_id=f.message_id""").single().single())
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String>> = db.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) "<null>" else c.getString(it) }) }
    }
}
