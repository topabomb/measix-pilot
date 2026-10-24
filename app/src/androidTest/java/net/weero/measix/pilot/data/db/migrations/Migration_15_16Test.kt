package net.weero.measix.pilot.data.db.migrations

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.APP_DATABASE_VERSION
import net.weero.measix.pilot.data.db.dao.LightConversationEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_15_16Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun indexesPreserveAllRowsAndMatchFreshSchemaWithoutTemporarySorts() {
        val name = "batch17-sort-migration"
        context.deleteDatabase(name)
        val before = helper.createDatabase(name, 15).use { db -> seed(db); rows(db, "SELECT * FROM ConversationEntity ORDER BY id") }
        val indexes = helper.runMigrationsAndValidate(name, 16, true, Migration_15_16).use { db ->
            assertEquals(before, rows(db, "SELECT * FROM ConversationEntity ORDER BY id"))
            val predicates = listOf(
                "scope='personal' AND parent_conversation_id IS NULL",
                "scope='personal' AND assistant_id='assistant' AND parent_conversation_id IS NULL",
                "scope='personal' AND assistant_id='assistant' AND parent_conversation_id IS NULL AND folder_id=''",
                "scope='personal' AND folder_id='folder' AND parent_conversation_id IS NULL",
            )
            for (predicate in predicates) for (timestamp in listOf("update_at", "create_at")) {
                val plan = rows(db, "EXPLAIN QUERY PLAN SELECT * FROM ConversationEntity WHERE $predicate ORDER BY is_pinned DESC,$timestamp DESC,id DESC LIMIT 20")
                    .flatten().joinToString(" ")
                assertFalse(plan, plan.contains("TEMP B-TREE", ignoreCase = true))
                assertTrue(plan, plan.contains("USING INDEX", ignoreCase = true))
            }
            indexStructure(db)
        }
        helper.createDatabase("batch17-sort-fresh", 16).use { db ->
            assertEquals(indexes, indexStructure(db))
        }
    }

    @Test fun actualRoomPagingOrdersBeforePageLimitsAndKeepsPinsTiesFoldersAndScope() = runBlocking {
        val name = "batch17-sort-paging"
        context.deleteDatabase(name)
        helper.createDatabase(name, APP_DATABASE_VERSION).use(::seed)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = database.conversationDao()
            for (created in listOf(false, true)) for (folder in listOf(false, true)) {
                val source = if (folder) {
                    if (created) dao.getConversationsOfFolderPagingByCreated(ConfigurationScope.Personal, "folder")
                    else dao.getConversationsOfFolderPaging(ConfigurationScope.Personal, "folder")
                } else {
                    if (created) dao.getUnfiledConversationsOfAssistantPagingByCreated(ConfigurationScope.Personal, "assistant")
                    else dao.getUnfiledConversationsOfAssistantPaging(ConfigurationScope.Personal, "assistant")
                }
                val actual = loadAll(source)
                val candidates = (0 until 140).filter { (it % 2 == 1) == folder }
                val expected = candidates.sortedWith(compareByDescending<Int> { it % 17 == 0 }
                    .thenByDescending { if (created) it / 3 else (140 - it) / 3 }.thenByDescending { id(it) })
                assertEquals(expected.map(::id), actual.map { it.id })
                assertEquals(70, actual.size)
                source.invalidate()
            }
        } finally { database.close() }
    }

    private suspend fun loadAll(source: PagingSource<Int, LightConversationEntity>): List<LightConversationEntity> {
        val result = mutableListOf<LightConversationEntity>()
        var key: Int? = null
        do {
            val params: PagingSource.LoadParams<Int> = if (key == null) PagingSource.LoadParams.Refresh(null, 20, false)
                else PagingSource.LoadParams.Append(key, 20, false)
            val page = source.load(params) as PagingSource.LoadResult.Page<Int, LightConversationEntity>
            result += page.data
            key = page.nextKey
        } while (key != null)
        return result
    }

    private fun indexStructure(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        rows(db, "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='ConversationEntity' ORDER BY name")
            .associate { row -> val name = requireNotNull(row.single()); name to rows(db, "PRAGMA index_info(`$name`)") }

    private fun id(index: Int) = "row-${index.toString().padStart(3, '0')}"

    private fun seed(db: SupportSQLiteDatabase) {
        repeat(140) { index ->
            db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope,folder_id) VALUES(?,'assistant','retained',?,?,'[]',?,'personal',?)",
                arrayOf<Any>(id(index), index / 3, (140 - index) / 3, if (index % 17 == 0) 1 else 0, if (index % 2 == 1) "folder" else ""))
        }
        db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope,parent_conversation_id) VALUES('child','assistant','child',999,999,'[]',1,'personal','row-000')")
        db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope) VALUES('other','assistant','other',999,999,'[]',1,'other-scope')")
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }
}
