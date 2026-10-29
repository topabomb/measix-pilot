package net.weero.measix.pilot.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.dao.ArtifactReferenceDAO
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.ConversationModelContextDAO
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.dao.FolderDAO
import net.weero.measix.pilot.data.db.dao.GenMediaDAO
import net.weero.measix.pilot.data.db.dao.MemoryDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.SystemMetaDAO
import net.weero.measix.pilot.data.db.dao.ToolExecutionDAO
import net.weero.measix.pilot.data.db.dao.TurnExecutionDAO
import net.weero.measix.pilot.data.db.dao.WorkspaceDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.ConversationContextAdmissionEntity
import net.weero.measix.pilot.data.db.entity.ConversationContextUseEntity
import net.weero.measix.pilot.data.db.entity.ConversationOpeningEntity
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.db.entity.FolderEntity
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.entity.SystemMetaEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity

/** Room schema changes require explicit, data-preserving migrations and matching exported schemas. */
const val APP_DATABASE_VERSION = 17

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        net.weero.measix.pilot.data.db.entity.MessageFtsRowEntity::class,
        ArtifactEntity::class,
        ArtifactReferenceEntity::class,
        SystemMetaEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        TurnExecutionEntity::class,
        ToolExecutionEntity::class,
        ConversationModelContextEntity::class,
        ConversationContextAdmissionEntity::class,
        ConversationContextUseEntity::class,
        ConversationOpeningEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    autoMigrations = [],
)
@TypeConverters(ConfigurationScopeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun conversationModelContextDao(): ConversationModelContextDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun artifactDao(): ArtifactDAO

    abstract fun artifactReferenceDao(): ArtifactReferenceDAO

    abstract fun systemMetaDao(): SystemMetaDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun turnExecutionDao(): TurnExecutionDAO

    abstract fun toolExecutionDao(): ToolExecutionDAO
}
