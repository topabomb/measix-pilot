package net.weero.measix.pilot.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase

/** Both a new database and an explicit upgrade use the same native FTS5 definition. */
internal fun createMessageFtsTable(db: SupportSQLiteDatabase) {
    db.execSQL("""
        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
            text,
            node_id UNINDEXED,
            message_id UNINDEXED,
            conversation_id UNINDEXED,
            title UNINDEXED,
            update_at UNINDEXED,
            tokenize = 'simple'
        )
    """.trimIndent())
}
