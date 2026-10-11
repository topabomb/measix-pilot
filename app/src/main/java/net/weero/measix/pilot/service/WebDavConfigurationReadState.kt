package net.weero.measix.pilot.service

import kotlin.uuid.Uuid
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmAccess

internal data class WebDavResourceSummary(
    val id: String,
    val name: String,
    val available: Boolean,
    val writable: Boolean,
    val revision: String,
    val detail: String? = null,
)

internal sealed interface WebDavConfigurationReadState {
    data object Loading : WebDavConfigurationReadState
    data class Unavailable(val detail: String) : WebDavConfigurationReadState
    data class Available(
        val selection: RealmSelection,
        val personalConnections: List<WebDavConnection>,
        val personalCurrentId: Uuid?,
        val backup: net.weero.measix.pilot.data.configuration.WebDavBackupPreferences = net.weero.measix.pilot.data.configuration.WebDavBackupPreferences(),
        val enterpriseResources: List<WebDavResourceSummary> = emptyList(),
        val designatedId: String? = null,
    ) : WebDavConfigurationReadState {
        val resources: List<WebDavResourceSummary> get() = if (selection.access == RealmAccess.Personal) personalConnections.map {
            WebDavResourceSummary(it.id.toString(), it.name, true, true, it.accessRevision.toString())
        } else enterpriseResources
        val currentId: String? get() = if (selection.access == RealmAccess.Personal) personalCurrentId?.toString() else designatedId
        val current: WebDavResourceSummary? get() = resources.singleOrNull { it.id == currentId }
    }
}
