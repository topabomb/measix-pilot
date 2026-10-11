package net.weero.measix.pilot.data.configuration

import kotlinx.serialization.Serializable

/** Stored within one realm's preferences. The reference contains no endpoint or credentials. */
@Serializable
data class WebDavBackupPreferences(
    val resourceId: String? = null,
    val path: String = "measix_pilot_backups",
    val includeDurableAggregate: Boolean = true,
) {
    fun validated(): WebDavBackupPreferences {
        require(resourceId == null || resourceId.isNotBlank()) { "webdav_backup_resource_invalid" }
        require(path.none { it == '\\' || it.code < 32 || it.code == 127 } &&
            (path.isEmpty() || path.split('/').none { it.isEmpty() || it == "." || it == ".." })) { "webdav_backup_path_invalid" }
        return this
    }
}
