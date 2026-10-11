package net.weero.measix.pilot.service.webdav

import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.EnterpriseWebDavResource
import net.weero.measix.pilot.data.enterprise.PlatformConnection
import net.weero.measix.pilot.data.enterprise.PlatformDAVAccessMode
import net.weero.measix.pilot.data.enterprise.RealmAccess

/** Immutable input captured for a browser, backup or Turn. It contains no platform Bearer. */
internal sealed interface WebDavTarget {
    val id: String
    val name: String
    val root: String
    val cacheIdentity: String

    data class Personal(val connection: WebDavConnection) : WebDavTarget {
        override val id get() = connection.id.toString()
        override val name get() = connection.name
        override val root get() = connection.rootUrl
        override val cacheIdentity get() = "personal:$id:${connection.accessRevision}"
    }

    data class Enterprise(
        val access: RealmAccess.Enterprise,
        val connection: PlatformConnection,
        val definition: EnterpriseWebDavResource,
        val space: String?,
        val bindingRevision: Long?,
        val accessMode: PlatformDAVAccessMode?,
    ) : WebDavTarget {
        override val id get() = definition.id
        override val name get() = definition.name
        override val root: String get() {
            definition.validate()
            val originalSpace = requireNotNull(space) { "webdav_space_unavailable" }
            require(originalSpace.matches(Regex("spc_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) {
                "webdav_space_invalid"
            }
            return connection.origin + definition.rootPathTemplate.replace("{agentSpaceId}", originalSpace)
        }
        override val cacheIdentity get() = "${access.scope}:${connection.origin}:$id:$space:$bindingRevision"
    }
}
