package net.weero.measix.pilot.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.webdav.WebDavClient
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.webdav.WebDavApplicationService
import net.weero.measix.pilot.service.webdav.WebDavBackupService
import net.weero.measix.pilot.service.webdav.WebDavTarget
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class WebDavSettingsMigrationTest {
    private val legacy = """{"url":"https://dav.example/root/","username":"legacy-user","password":" secret ","path":"/copies/","items":["FILES"]}"""
    private val unfinishedConnections = listOf(
        """{"url":"http","username":"draft","password":" secret ","path":"../"}""",
        """{"username":"orphaned","password":"kept","items":[]}""",
        """{"url":"file:///tmp"}""",
        """{"url":"https://","path":"../outside/"}""",
        """{"url":"https://dav.example/","username":"unfinished:user"}""",
        """{"url":"http://bad host/"}""",
    )

    @Test fun `standalone preferences migrate through the original settings owner atomically`() = runTest {
        val original = mutablePreferencesOf(SettingsStore.WEBDAV_CONFIG to legacy,
            SettingsStore.THEME_ID to "kept", SettingsStore.PENDING_MCP_CATALOG_MIGRATION to "preserve")
        assertFalse(WebDavSettingsMigration().shouldMigrate(original))
        val migration = UserSettingsMigration()
        val migrated = migration.migrate(original)
        val settings = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!).personalSettings()
        assertLegacyConnection(settings)
        assertEquals("kept", settings.themeId)
        assertEquals("preserve", migrated[SettingsStore.PENDING_MCP_CATALOG_MIGRATION])
        assertFalse(migrated.contains(SettingsStore.WEBDAV_CONFIG))
        assertFalse(migration.shouldMigrate(migrated))
        assertFalse(WebDavSettingsMigration().shouldMigrate(migrated))
        assertEquals(legacy, original[SettingsStore.WEBDAV_CONFIG])
        assertFalse(original.contains(SettingsStore.USER_SETTINGS))
    }

    @Test fun `existing document adopts old backup fields and preserves enterprise scopes`() = runTest {
        val enterprise = ScopedUserPreferences(ConfigurationScope.Enterprise(EnterpriseAuthority("deployment"), "user"),
            webDavBackup = WebDavBackupPreferences("enterprise-id", "enterprise", false))
        val document = UserSettingsDocument.empty(UserPreferences(scopes = listOf(
            ScopedUserPreferences(ConfigurationScope.Personal), enterprise)))
        val raw = oldDocument(document)
        val original = mutablePreferencesOf(SettingsStore.USER_SETTINGS to raw)
        val migration = WebDavSettingsMigration()
        assertTrue(migration.shouldMigrate(original))
        val migrated = migration.migrate(original)
        val restored = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!)
        assertLegacyConnection(restored.personalSettings())
        assertEquals(enterprise, restored.preferences.scopes.single { it.scope == enterprise.scope })
        assertEquals(document.internalState, restored.internalState)
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
        assertEquals(raw, original[SettingsStore.USER_SETTINGS])
    }

    @Test fun `standalone key alongside a current document keeps current settings and scopes`() = runTest {
        val selected = WebDavConnection(name = "Current", rootUrl = "https://current.example/")
        val backup = WebDavBackupPreferences("explicit-backup", "new-path", false)
        val document = UserSettingsDocument.empty().withPersonalSettings(Settings(themeId = "kept",
            webDavConnections = listOf(selected), currentWebDavConnectionId = selected.id, webDavBackup = backup))
        val raw = JsonInstant.encodeToString(document)
        val original = mutablePreferencesOf(SettingsStore.USER_SETTINGS to raw, SettingsStore.WEBDAV_CONFIG to legacy)
        val migrated = WebDavSettingsMigration().migrate(original)
        val restored = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!).personalSettings()
        assertEquals(selected, restored.webDavConnections.first())
        assertEquals(2, restored.webDavConnections.size)
        assertEquals(selected.id, restored.currentWebDavConnectionId)
        assertEquals(backup, restored.webDavBackup)
        assertEquals("kept", restored.themeId)
        assertFalse(migrated.contains(SettingsStore.WEBDAV_CONFIG))
    }

    @Test fun `new connection with the same migrated id wins without duplication`() {
        val migratedConnection = requireNotNull(migrateLegacyWebDavConfiguration(legacy)).connection
        val current = migratedConnection.copy(name = "New definition", password = "new-secret")
        val newBackup = WebDavBackupPreferences(current.id.toString(), "new-copies", false)
        val root = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings(webDavConnections = listOf(current),
            currentWebDavConnectionId = current.id, webDavBackup = newBackup))).jsonObject
        val raw = JsonObject(root + ("webDavConfig" to JsonInstant.parseToJsonElement(legacy))).toString()
        val decoded = JsonInstant.decodeFromString<Settings>(migrateLegacySettingsJson(raw))
        assertEquals(listOf(current), decoded.webDavConnections)
        assertEquals(current.id, decoded.currentWebDavConnectionId)
        assertEquals(newBackup, decoded.webDavBackup)
    }

    @Test fun `flat old backup import preserves connection credentials path and aggregate selection`() {
        val raw = """{"webDavConfig":$legacy,"themeId":"kept","ocrModelId":null}"""
        val migrated = migrateLegacySettingsJson(raw)
        val decoded = JsonInstant.decodeFromString<Settings>(migrated)
        assertLegacyConnection(decoded)
        assertEquals("kept", decoded.themeId)
        assertFalse(JsonInstant.parseToJsonElement(migrated).jsonObject.containsKey("webDavConfig"))
        assertEquals(migrated, migrateLegacySettingsJson(migrated))
        assertEquals(migrated, migrateLegacySettingsJson(raw))
        for (items in listOf("[]", "[\"DATABASE\"]", "[\"FILES\"]", "[\"DATABASE\",\"FILES\"]")) {
            val value = JsonInstant.parseToJsonElement(legacy).jsonObject
            val source = JsonObject(mapOf("webDavConfig" to JsonObject(value + ("items" to JsonInstant.parseToJsonElement(items)))))
            val settings = JsonInstant.decodeFromString<Settings>(migrateLegacySettingsJson(source.toString()))
            assertEquals(items != "[]", settings.webDavBackup.includeDurableAggregate)
        }
    }

    @Test fun `only a genuinely empty legacy default is removed without a connection`() = runTest {
        for (raw in listOf("{}", """{"url":"","username":"","password":"","path":"measix_pilot_backups","items":["DATABASE","FILES"]}""")) {
            assertNull(migrateLegacyWebDavConfiguration(raw))
            val original = mutablePreferencesOf(SettingsStore.WEBDAV_CONFIG to raw)
            val migrated = UserSettingsMigration().migrate(original)
            val settings = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!).personalSettings()
            assertTrue(settings.webDavConnections.isEmpty())
            assertNull(settings.webDavBackup.resourceId)
            assertNull(settings.currentWebDavConnectionId)
            assertFalse(migrated.contains(SettingsStore.WEBDAV_CONFIG))
        }
    }

    @Test fun `unfinished old edits survive every migration boundary without becoming current`() = runTest {
        val values = unfinishedConnections + listOf(
            """{"url":"https://dav.example/","path":"../outside/"}""",
            """{"url":"https://dav.example/","path":"a//b"}""",
            """{"url":"https://dav.example/","path":"unfinished\\path"}""",
        )
        for (raw in values) {
            val original = mutablePreferencesOf(SettingsStore.WEBDAV_CONFIG to raw)
            val migrated = UserSettingsMigration().migrate(original)
            val document = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!)
            val settings = document.personalSettings()
            val connection = settings.webDavConnections.single()
            val fields = JsonInstant.parseToJsonElement(raw).jsonObject
            assertEquals(fields["url"]?.jsonPrimitive?.content.orEmpty(), connection.rootUrl)
            assertEquals(fields["username"]?.jsonPrimitive?.content.orEmpty(), connection.username)
            assertEquals(fields["password"]?.jsonPrimitive?.content.orEmpty(), connection.password)
            assertEquals(fields["path"]?.jsonPrimitive?.content ?: "measix_pilot_backups", settings.webDavBackup.path)
            assertEquals(connection.id.toString(), settings.webDavBackup.resourceId)
            assertEquals(fields["items"]?.jsonArray?.isNotEmpty() ?: true, settings.webDavBackup.includeDurableAggregate)
            assertNull(settings.currentWebDavConnectionId)
            assertEquals(raw, original[SettingsStore.WEBDAV_CONFIG])
            assertFalse(migrated.contains(SettingsStore.WEBDAV_CONFIG))
            assertEquals(migrated, WebDavSettingsMigration().migrate(migrated))

            val existing = mutablePreferencesOf(SettingsStore.USER_SETTINGS to oldDocument(UserSettingsDocument.empty(), raw))
            val converted = WebDavSettingsMigration().migrate(existing)
            val restored = JsonInstant.decodeFromString<UserSettingsDocument>(converted[SettingsStore.USER_SETTINGS]!!).personalSettings()
            val imported = JsonInstant.decodeFromString<Settings>(migrateLegacySettingsJson("""{"webDavConfig":$raw}"""))
            for (result in listOf(restored, imported)) {
                assertEquals(listOf(connection), result.webDavConnections)
                assertEquals(settings.webDavBackup, result.webDavBackup)
                assertNull(result.currentWebDavConnectionId)
            }
        }
    }

    @Test fun `unfinished migrated connections require correction before save selection or execution`() = runTest {
        val queries = mockk<ConfigurationQueryService> {
            every { observe(ConfigurationScope.Personal) } returns emptyFlow()
            every { observeSelectedRealmSelection() } returns emptyFlow()
        }
        val client = mockk<WebDavClient>()
        val service = WebDavApplicationService(queries, client, backgroundScope, mockk(), mockk(), mockk())
        for (raw in unfinishedConnections) {
            val converted = requireNotNull(migrateLegacyWebDavConfiguration(raw))
            val connection = converted.connection
            val document = UserSettingsDocument.empty().withPersonalSettings(Settings(
                webDavConnections = listOf(connection), webDavBackup = converted.backup))
            assertEquals(connection, document.personalSettings().webDavConnections.single())
            assertNull(document.personalSettings().currentWebDavConnectionId)
            assertThrows(IllegalArgumentException::class.java) {
                document.withWebDavConnectionChange(WebDavConnectionChange.Save(connection, connection))
            }
            assertThrows(IllegalArgumentException::class.java) {
                document.withWebDavConnectionChange(WebDavConnectionChange.Select(connection.id))
            }
            try {
                service.captureExpected(RealmAccess.Personal, WebDavTarget.Personal(connection))
                fail("Unfinished connection must not acquire execution access")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message in setOf("webdav_root_url_invalid", "webdav_username_invalid"))
            }
            val corrected = connection.copy(rootUrl = "https://repaired.example/", username = "user")
            val saved = document.withWebDavConnectionChange(WebDavConnectionChange.Save(corrected, connection))
            assertNull(saved.personalSettings().currentWebDavConnectionId)
            val selected = saved.withWebDavConnectionChange(WebDavConnectionChange.Select(connection.id)).personalSettings()
            assertEquals(connection.id, selected.currentWebDavConnectionId)
            assertEquals("https://repaired.example/", selected.webDavConnections.single().rootUrl)
            assertEquals(connection.password, selected.webDavConnections.single().password)
        }
        coVerify(exactly = 0) { queries.read(any()) }
        verify { client wasNot Called }
    }

    @Test fun `unfinished migrated backup path remains editable and cannot open execution access`() = runTest {
        val converted = requireNotNull(migrateLegacyWebDavConfiguration("""{"url":"https://dav.example/","path":"../"}"""))
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { cacheDir } returns File("unused-webdav-migration-cache")
        }
        val dav = mockk<WebDavApplicationService>()
        val queries = mockk<ConfigurationQueryService>()
        val service = WebDavBackupService(context, dav, queries, mockk())
        assertEquals("../", converted.backup.path)
        try {
            service.open(RealmSelection(RealmAccess.Personal, 1), converted.backup)
            fail("Unfinished backup path must not acquire execution access")
        } catch (error: IllegalArgumentException) {
            assertEquals("webdav_backup_path_invalid", error.message)
        }
        verify { dav wasNot Called; queries wasNot Called }
    }

    @Test fun `malformed legacy data fails every boundary without erasing original input`() = runTest {
        val badValues = listOf("not JSON", "null", "false", "42", "[]", "\"invalid\"",
            """{"url":"https://dav.example/","password":[]}""",
            """{"url":"https://dav.example/","path":{}}""",
            """{"url":"https://dav.example/","items":["UNKNOWN"]}""")
        for (bad in badValues) {
            val standalone = mutablePreferencesOf(SettingsStore.WEBDAV_CONFIG to bad, SettingsStore.THEME_ID to "keep")
            assertTrue(runCatching { UserSettingsMigration().migrate(standalone) }.isFailure)
            assertEquals(bad, standalone[SettingsStore.WEBDAV_CONFIG])
            assertFalse(standalone.contains(SettingsStore.USER_SETTINGS))
            val raw = oldDocument(UserSettingsDocument.empty(), bad)
            val document = mutablePreferencesOf(SettingsStore.USER_SETTINGS to raw)
            assertTrue(runCatching { WebDavSettingsMigration().migrate(document) }.isFailure)
            assertEquals(raw, document[SettingsStore.USER_SETTINGS])
            val legacyValue = runCatching { JsonInstant.parseToJsonElement(bad) }.getOrNull()
            if (legacyValue != null) {
                assertThrows(Exception::class.java) { migrateLegacySettingsJson(JsonObject(mapOf("webDavConfig" to legacyValue)).toString()) }
            }
        }
        assertThrows(Exception::class.java) { migrateLegacySettingsJson("not JSON") }
    }

    private fun assertLegacyConnection(settings: Settings) {
        val connection = settings.webDavConnections.single()
        assertEquals("https://dav.example/root/", connection.rootUrl)
        assertEquals("legacy-user", connection.username)
        assertEquals(" secret ", connection.password)
        assertEquals("dav.example", connection.name)
        assertEquals(connection.id.toString(), settings.webDavBackup.resourceId)
        assertEquals("copies", settings.webDavBackup.path)
        assertTrue(settings.webDavBackup.includeDurableAggregate)
        assertNull(settings.currentWebDavConnectionId)
    }

    private fun oldDocument(document: UserSettingsDocument, old: String = legacy): String {
        val root = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(document)).jsonObject
        val configuration = root.getValue("configuration").jsonObject - "webDavConnections"
        val preferences = root.getValue("preferences").jsonObject
        val scopes = preferences.getValue("scopes").jsonArray.map { scoped ->
            val value = scoped.jsonObject
            if (value.getValue("scope").jsonObject["type"] == JsonPrimitive("personal"))
                JsonObject(value - setOf("webDavBackup", "currentWebDavConnectionId")) else value
        }
        val oldValue = runCatching { JsonInstant.parseToJsonElement(old) }.getOrElse { JsonPrimitive(old) }
        return JsonObject(root + mapOf("configuration" to JsonObject(configuration + ("webDavConfig" to oldValue)),
            "preferences" to JsonObject(preferences + ("scopes" to JsonArray(scopes))))).toString()
    }
}
