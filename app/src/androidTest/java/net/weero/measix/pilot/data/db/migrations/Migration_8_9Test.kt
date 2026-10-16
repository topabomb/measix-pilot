package net.weero.measix.pilot.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_8_9Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun indexPreservesDuplicatePathsAndAllExistingValues() {
        val name = "migration-v8-v9-data"
        val old = helper.createDatabase(name, 8)
        val historicalPath = "images/809278de-6677-4bc1-9249-d94c85b0930c.png"
        listOf(historicalPath, historicalPath, "images/OldFile.PNG").forEachIndexed { index, path ->
            old.execSQL(
                "INSERT INTO GenMediaEntity(id, path, model_id, prompt, create_at, type, source_paths) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(index + 1, path, "model-$index", "prompt-$index", 100L + index,
                    "image_generation", "[\"upload/old-$index.png\"]"),
            )
        }
        val beforeRows = rows(old, "SELECT * FROM GenMediaEntity ORDER BY id")
        val beforeTables = tableNames(old)
        val beforeColumns = beforeTables.associateWith { rows(old, "PRAGMA table_info(`$it`)") }
        val beforeForeignKeys = beforeTables.associateWith { rows(old, "PRAGMA foreign_key_list(`$it`)") }
        val beforeUniqueIndexes = beforeTables.associateWith { indexInfo(old, it, uniqueOnly = true) }
        old.close()

        val migrated = helper.runMigrationsAndValidate(name, 9, true, Migration_8_9)
        assertEquals(beforeRows, rows(migrated, "SELECT * FROM GenMediaEntity ORDER BY id"))
        assertEquals(beforeTables, tableNames(migrated))
        beforeTables.forEach { table ->
            assertEquals(beforeColumns[table], rows(migrated, "PRAGMA table_info(`$table`)"))
            assertEquals(beforeForeignKeys[table], rows(migrated, "PRAGMA foreign_key_list(`$table`)"))
            assertEquals(beforeUniqueIndexes[table], indexInfo(migrated, table, uniqueOnly = true))
        }
        migrated.query("PRAGMA index_list(GenMediaEntity)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_GenMediaEntity_path") {
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                    found = true
                }
            }
            assertTrue(found)
        }
        migrated.query(
            "EXPLAIN QUERY PLAN SELECT EXISTS(SELECT 1 FROM GenMediaEntity WHERE path = ?)",
            arrayOf<Any?>(historicalPath),
        ).use { cursor ->
            var usesIndex = false
            while (cursor.moveToNext()) {
                val detail = cursor.getString(cursor.getColumnIndexOrThrow("detail"))
                usesIndex = usesIndex || ("SEARCH" in detail && "index_GenMediaEntity_path" in detail)
            }
            assertTrue(usesIndex)
        }
        migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        migrated.close()
    }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        rows(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name != 'room_master_table' ORDER BY name").map { requireNotNull(it.single()) }

    private fun indexInfo(
        db: SupportSQLiteDatabase,
        table: String,
        uniqueOnly: Boolean = false,
    ): Map<String, List<List<String?>>> = buildMap {
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                if (uniqueOnly && unique == 0) continue
                val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                put("$indexName:$unique", rows(db, "PRAGMA index_info(`$indexName`)").map { column ->
                    listOf(column[0], column[2])
                })
            }
        }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = buildList {
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                })
            }
        }
    }
}
