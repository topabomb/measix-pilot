package net.weero.measix.pilot.data.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

@Serializable
private data class LegacyWebDavConfiguration(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "measix_pilot_backups",
    val items: List<LegacyWebDavBackupItem> = LegacyWebDavBackupItem.entries,
)

@Serializable
private enum class LegacyWebDavBackupItem { DATABASE, FILES }

internal data class MigratedWebDavConfiguration(
    val connection: WebDavConnection,
    val backup: WebDavBackupPreferences,
)

/** A stable identity makes repeated validation/import of the same legacy archive idempotent. */
internal fun migrateLegacyWebDavConfiguration(raw: String): MigratedWebDavConfiguration? {
    val legacy = JsonInstant.decodeFromString<LegacyWebDavConfiguration>(raw)
    if (legacy == LegacyWebDavConfiguration()) return null
    val original = WebDavConnection(name = "WebDAV", rootUrl = legacy.url,
        username = legacy.username, password = legacy.password)
    // The old editor persisted each field immediately, including unfinished input.
    // Typed decoding stays strict; Save, selection and execution validate the editable result.
    val converted = try { original.validated() } catch (_: IllegalArgumentException) { original }
    val identity = UUID.nameUUIDFromBytes(
        "measix-pilot:legacy-webdav:${JsonInstant.encodeToString(listOf(converted.rootUrl, converted.username))}".toByteArray(Charsets.UTF_8),
    ).toString().let(Uuid::parse)
    val name = try { URI(converted.rootUrl).host?.takeIf(String::isNotBlank) }
        catch (_: URISyntaxException) { null }
    val connection = converted.copy(id = identity, name = name ?: "WebDAV", accessRevision = identity)
    // The old client stripped surrounding separators and treated a blank path as its root.
    val path = legacy.path.takeIf(String::isNotBlank)?.trim('/') ?: ""
    val normalizedBackup = WebDavBackupPreferences(identity.toString(), path,
        legacy.items.any { it == LegacyWebDavBackupItem.DATABASE || it == LegacyWebDavBackupItem.FILES })
    val backup = try { normalizedBackup.validated() }
        catch (_: IllegalArgumentException) { normalizedBackup.copy(path = legacy.path) }
    return MigratedWebDavConfiguration(connection, backup)
}

/** Only the migration boundary understands the retired field; current fields always win. */
internal fun migrateWebDavSettingsJson(source: String, legacyPreference: String? = null): String {
    val root = JsonInstant.parseToJsonElement(source) as? JsonObject
        ?: throw IllegalArgumentException("settings_json_object_required")
    val configuration = root["configuration"]?.let {
        requireNotNull(it as? JsonObject) { "settings_configuration_object_required" }
    }
    val legacy = buildList {
        configuration?.get("webDavConfig")?.let { add(it.toString()) }
        root["webDavConfig"]?.let { add(it.toString()) }
        legacyPreference?.let { add(it) }
    }
    if (legacy.isEmpty()) return source
    val upgrades = legacy.mapNotNull(::migrateLegacyWebDavConfiguration).distinctBy { it.connection.id }
    val fields = (configuration ?: root).toMutableMap().apply { remove("webDavConfig") }
    if (upgrades.isNotEmpty()) {
        val existing = fields["webDavConnections"]?.let {
            requireNotNull(it as? JsonArray) { "webdav_connections_array_required" }
        } ?: JsonArray(emptyList())
        val existingIds = JsonInstant.decodeFromString<List<WebDavConnection>>(existing.toString()).map { it.id }.toSet()
        fields["webDavConnections"] = JsonArray(existing + upgrades.filter { it.connection.id !in existingIds }.map {
            JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it.connection))
        })
    }
    val patched = root.toMutableMap().apply { remove("webDavConfig") }
    if (configuration == null) {
        patched.putAll(fields)
        if (upgrades.isNotEmpty() && "webDavBackup" !in patched) {
            patched["webDavBackup"] = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(upgrades.first().backup))
        }
    } else {
        patched["configuration"] = JsonObject(fields)
        if (upgrades.isNotEmpty()) {
            val preferences = root["preferences"] as? JsonObject
                ?: throw IllegalArgumentException("settings_preferences_object_required")
            val scopes = preferences["scopes"]?.let {
                requireNotNull(it as? JsonArray) { "settings_scopes_array_required" }
            } ?: JsonArray(emptyList())
            val personalScope = JsonInstant.parseToJsonElement(JsonInstant.encodeToString<ConfigurationScope>(ConfigurationScope.Personal))
            val personalIndices = scopes.indices.filter {
                ((scopes[it] as? JsonObject)?.get("scope") as? JsonObject)?.get("type") == JsonPrimitive("personal")
            }
            require(personalIndices.size <= 1) { "duplicate_preference_scope" }
            val index = personalIndices.singleOrNull()
            val personal = index?.let { scopes[it] as JsonObject } ?: JsonObject(mapOf("scope" to personalScope))
            if ("webDavBackup" !in personal) {
                val migratedPersonal = JsonObject(personal + ("webDavBackup" to
                    JsonInstant.parseToJsonElement(JsonInstant.encodeToString(upgrades.first().backup))))
                val updatedScopes = scopes.toMutableList().apply {
                    if (index == null) add(migratedPersonal) else this[index] = migratedPersonal
                }
                patched["preferences"] = JsonObject(preferences + ("scopes" to JsonArray(updatedScopes)))
            }
        }
    }
    return JsonObject(patched).toString()
}

/** Old standalone keys are adopted by UserSettingsMigration; existing documents migrate here. */
internal class WebDavSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val raw = currentData[SettingsStore.USER_SETTINGS] ?: return false
        if (currentData.contains(SettingsStore.WEBDAV_CONFIG)) return true
        val root = JsonInstant.parseToJsonElement(raw) as? JsonObject ?: return false
        return "webDavConfig" in root || (root["configuration"] as? JsonObject)?.containsKey("webDavConfig") == true
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val raw = requireNotNull(currentData[SettingsStore.USER_SETTINGS]) { "user_settings_migration_incomplete" }
        val migrated = migrateWebDavSettingsJson(raw, currentData[SettingsStore.WEBDAV_CONFIG])
        return currentData.toMutablePreferences().apply {
            this[SettingsStore.USER_SETTINGS] = migrated
            remove(SettingsStore.WEBDAV_CONFIG)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
