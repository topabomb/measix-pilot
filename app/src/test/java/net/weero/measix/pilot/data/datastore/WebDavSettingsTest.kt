package net.weero.measix.pilot.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.configuration.WebDavConnectionField
import net.weero.measix.pilot.data.configuration.WebDavConnectionValidationException
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class WebDavSettingsTest {
    private val first = WebDavConnection(name = "First", rootUrl = "https://dav.example/root/", password = " secret ")
    private val second = WebDavConnection(name = "Second", rootUrl = "https://other.example/")

    @Test
    fun `first save selects atomically and later saves preserve explicit selection`() {
        val empty = UserSettingsDocument.empty()
        val document = empty.withWebDavConnectionChange(WebDavConnectionChange.Save(first, null))
        assertEquals(empty.configuration, document.configuration.copy(webDavConnections = emptyList()))
        assertEquals(empty.preferences.scopes.single().selections, document.preferences.scopes.single().selections)
        assertEquals(first.id, document.personalSettings().currentWebDavConnectionId)
        val added = document.withWebDavConnectionChange(WebDavConnectionChange.Save(second, null))
        assertEquals(first.id, added.personalSettings().currentWebDavConnectionId)
        val savedFirst = document.configuration.webDavConnections.single()
        val savedSecond = added.configuration.webDavConnections.last()
        val renamed = savedFirst.copy(name = "Renamed")
        val edited = added.withWebDavConnectionChange(WebDavConnectionChange.Save(renamed, savedFirst))
        assertEquals(listOf(renamed, savedSecond), edited.configuration.webDavConnections)
        assertEquals(first.id, edited.personalSettings().currentWebDavConnectionId)
        val deleted = edited.withWebDavConnectionChange(WebDavConnectionChange.Delete(first.id))
        assertNull(deleted.personalSettings().currentWebDavConnectionId)
        assertEquals(listOf(savedSecond), deleted.configuration.webDavConnections)
        assertThrows(IllegalStateException::class.java) {
            deleted.withWebDavConnectionChange(WebDavConnectionChange.Save(first, renamed))
        }
        assertThrows(IllegalArgumentException::class.java) {
            deleted.withWebDavConnectionChange(WebDavConnectionChange.Select(first.id))
        }
    }

    @Test
    fun `access identity cannot return after password round trip or delete and recreate`() {
        val original = UserSettingsDocument.empty().withWebDavConnectionChange(WebDavConnectionChange.Save(first, null))
        val initial = original.configuration.webDavConnections.single()
        val changed = original.withWebDavConnectionChange(WebDavConnectionChange.Save(initial.copy(password = "new"), initial))
        val middle = changed.configuration.webDavConnections.single()
        val restored = changed.withWebDavConnectionChange(WebDavConnectionChange.Save(initial, middle))
        assertNotEquals(initial.accessRevision, restored.configuration.webDavConnections.single().accessRevision)
        val recreated = original.withWebDavConnectionChange(WebDavConnectionChange.Delete(initial.id))
            .withWebDavConnectionChange(WebDavConnectionChange.Save(initial, null))
        assertNotEquals(initial.accessRevision, recreated.configuration.webDavConnections.single().accessRevision)
    }

    @Test
    fun `backup round trip preserves credentials selections and unrelated enterprise preferences`() {
        val enterprise = ScopedUserPreferences(ConfigurationScope.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("deployment"), "user"))
        val original = UserSettingsDocument.empty(UserPreferences(scopes = listOf(
            ScopedUserPreferences(ConfigurationScope.Personal), enterprise,
        ))).withPersonalSettings(Settings()).withWebDavConnectionChange(WebDavConnectionChange.Save(first, null))
        val decoded = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(original.personalSettings()))
        val restored = original.withPersonalSettings(decoded)
        assertEquals(JsonInstant.encodeToString(original), JsonInstant.encodeToString(restored))
        assertEquals(" secret ", restored.configuration.webDavConnections.single().password)
        assertEquals(enterprise, restored.preferences.scopes.single { it.scope == enterprise.scope })
        assertEquals(original.personalSettings().webDavBackup, restored.personalSettings().webDavBackup)
        val reloaded = JsonInstant.decodeFromString<UserSettingsDocument>(JsonInstant.encodeToString(original))
        assertEquals(JsonInstant.encodeToString(original), JsonInstant.encodeToString(reloaded))
    }

    @Test
    fun `legacy settings default empty and invalid reference is preserved rather than selecting first`() {
        val encoded = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject
        val legacy = JsonObject(encoded - setOf("webDavConnections", "currentWebDavConnectionId"))
        val decoded = JsonInstant.decodeFromString<Settings>(legacy.toString())
        assertTrue(decoded.webDavConnections.isEmpty())
        assertNull(decoded.currentWebDavConnectionId)
        val missing = Uuid.random()
        val document = UserSettingsDocument.empty().withPersonalSettings(decoded.copy(
            webDavConnections = listOf(first), currentWebDavConnectionId = missing,
        ))
        assertEquals(missing, document.personalSettings().currentWebDavConnectionId)
    }

    @Test
    fun `URL validation rejects embedded credentials and preserves password whitespace`() {
        for (url in listOf("/", "dav.example/root", "https:///", "https://bad host/", "file:///tmp", "https://user:secret@dav.example/", "https://dav.example/?secret=x",
            "https://dav.example/#fragment", "https://dav.example/a/../", "https://dav.example:0/",
            "https://dav.example/a/%2e%2e/", "https://dav.example/a%2fb/", "https://dav.example/%00/")) {
            val failure = assertThrows(WebDavConnectionValidationException::class.java) { first.copy(rootUrl = url).validated() }
            assertEquals(WebDavConnectionField.ADDRESS, failure.field)
            assertEquals("webdav_root_url_invalid", failure.code)
            assertEquals(failure.code, failure.message)
        }
        assertEquals(" secret ", first.validated().password)
        assertEquals("https://dav.example/root/", first.copy(rootUrl = "https://dav.example/root").validated().rootUrl)
        assertFalse(first.toString().contains("secret"))
    }

    @Test
    fun `HTTP schemes are case insensitive without inferring a missing scheme`() {
        for (scheme in listOf("http", "https", "HTTP", "HtTpS")) {
            val connection = first.copy(rootUrl = "$scheme://dav.example/root").validated()
            assertEquals("dav.example", java.net.URI(connection.rootUrl).host)
            assertEquals("/root/", java.net.URI(connection.rootUrl).path)
            assertTrue(java.net.URI(connection.rootUrl).scheme.equals(scheme, ignoreCase = true))
        }
        assertThrows(WebDavConnectionValidationException::class.java) {
            first.copy(rootUrl = "dav.example/root").validated()
        }
    }

    @Test
    fun `anonymous connection defaults only its display name and username failures identify the field`() {
        val unnamed = WebDavConnection(name = "  ", rootUrl = " https://dav.example/files ")
        val saved = UserSettingsDocument.empty().withWebDavConnectionChange(WebDavConnectionChange.Save(unnamed, null))
        val connection = saved.configuration.webDavConnections.single()
        assertEquals("dav.example", connection.name)
        assertEquals("https://dav.example/files/", connection.rootUrl)
        assertEquals("", connection.username)
        assertEquals("", connection.password)
        assertEquals(connection.id, saved.personalSettings().currentWebDavConnectionId)
        assertEquals("My files", connection.copy(name = " My files ").validated().name)
        for (username in listOf("user:name", "user\nname", "user\rname")) {
            val failure = assertThrows(WebDavConnectionValidationException::class.java) {
                connection.copy(username = username).validated()
            }
            assertEquals(WebDavConnectionField.USERNAME, failure.field)
            assertEquals("webdav_username_invalid", failure.code)
        }
    }
}
