package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** List sorting changes only indexes; transcript and execution facts stay intact. */
val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val prefixes = listOf(
            listOf("scope", "parent_conversation_id"),
            listOf("scope", "assistant_id", "parent_conversation_id"),
            listOf("scope", "assistant_id", "parent_conversation_id", "folder_id"),
            listOf("scope", "folder_id", "parent_conversation_id"),
        )
        prefixes.forEach { prefix ->
            val oldColumns = prefix + listOf("is_pinned", "update_at")
            db.execSQL("DROP INDEX IF EXISTS `index_ConversationEntity_${oldColumns.joinToString("_")}`")
            listOf("update_at", "create_at").forEach { timestamp ->
                val columns = prefix + listOf("is_pinned", timestamp, "id")
                db.execSQL("CREATE INDEX `index_ConversationEntity_${columns.joinToString("_")}` " +
                    "ON `ConversationEntity` (${columns.joinToString(",") { "`$it`" }})")
            }
        }
    }
}
