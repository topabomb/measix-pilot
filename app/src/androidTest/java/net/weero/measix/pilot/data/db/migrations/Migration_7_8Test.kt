package net.weero.measix.pilot.data.db.migrations

import android.database.Cursor
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

/** Verifies the payload-token migration; historical full-chain coverage lives in Migration_10_11Test. */
@RunWith(AndroidJUnit4::class)
class Migration_7_8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v7ToV8PreservesArtifactAndAddsNullablePayloadToken() {
        val db = helper.createDatabase("migration-v7-v8", 7)
        seedArtifact(db, "upload/v7.png", "GENERATED")
        db.close()

        val migrated = helper.runMigrationsAndValidate("migration-v7-v8", 8, true, Migration_7_8)
        assertArtifact(migrated, "upload/v7.png", "GENERATED")
        assertTrue(columns(migrated, "artifact").contains("payload_token"))
        assertNoForeignKeyViolations(migrated)
        migrated.close()
    }

    private fun seedArtifact(db: SupportSQLiteDatabase, path: String, origin: String) {
        db.execSQL(
            "INSERT INTO artifact " +
                "(folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, state, origin) " +
                "VALUES ('upload', '$path', 'asset.png', 'image/png', 17, 100, 200, 'ACTIVE', '$origin')"
        )
    }

    private fun assertArtifact(db: SupportSQLiteDatabase, path: String, origin: String) {
        query(
            db,
            "SELECT folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, " +
                "state, origin, payload_token FROM artifact WHERE relative_path = '$path'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("upload", cursor.getString(0))
            assertEquals(path, cursor.getString(1))
            assertEquals("asset.png", cursor.getString(2))
            assertEquals("image/png", cursor.getString(3))
            assertEquals(17L, cursor.getLong(4))
            assertEquals(100L, cursor.getLong(5))
            assertEquals(200L, cursor.getLong(6))
            assertEquals("ACTIVE", cursor.getString(7))
            assertEquals(origin, cursor.getString(8))
            assertTrue(cursor.isNull(9))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        query(db, "PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        tableInfo(db, table).mapTo(mutableSetOf()) { it.substringBefore(':') }

    private fun tableInfo(db: SupportSQLiteDatabase, table: String): List<String> {
        val result = mutableListOf<String>()
        query(db, "PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                result += listOf("name", "type", "notnull", "dflt_value", "pk")
                    .joinToString(":") { key ->
                        val index = cursor.getColumnIndexOrThrow(key)
                        if (cursor.isNull(index)) "NULL" else cursor.getString(index)
                    }
            }
        }
        return result.sorted()
    }

    private fun query(db: SupportSQLiteDatabase, sql: String): Cursor = db.query(sql, emptyArray<Any?>())
}
