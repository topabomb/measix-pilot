package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Derived row locations; MessageFtsManager updates this table and the virtual table together. */
@Entity(tableName = "message_fts_rowid", indices = [
    Index(value = ["conversation_id"]),
    Index(value = ["conversation_id", "node_id"]),
    Index(value = ["conversation_id", "node_id", "message_id"], unique = true),
])
data class MessageFtsRowEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "fts_rowid") val rowId: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
)
