package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.weero.measix.pilot.data.db.fts.createMessageFtsTable

/** Unmeasured legacy executions retain null duration; recovery rebuilds both derived search tables. */
val Migration_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tool_execution ADD COLUMN duration_millis INTEGER")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `message_fts_rowid` (
                `fts_rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `node_id` TEXT NOT NULL,
                `message_id` TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_fts_rowid_conversation_id` ON `message_fts_rowid` (`conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_fts_rowid_conversation_id_node_id` ON `message_fts_rowid` (`conversation_id`, `node_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_message_fts_rowid_conversation_id_node_id_message_id` ON `message_fts_rowid` (`conversation_id`, `node_id`, `message_id`)")
        createMessageFtsTable(db)
        db.execSQL("DELETE FROM system_meta WHERE `key` = 'message_fts_projection'")
    }
}
