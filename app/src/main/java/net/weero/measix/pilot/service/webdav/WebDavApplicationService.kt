package net.weero.measix.pilot.service.webdav

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.service.PlatformEnterpriseService
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceService
import net.weero.measix.pilot.service.remoteworkspace.WorkspaceDavSnapshot
import net.weero.measix.pilot.data.webdav.*
import net.weero.measix.pilot.service.ConfigurationQueryService
import okhttp3.Credentials

/** A captured target; display-name edits do not change the authority to access its root. */
internal class WebDavHandle internal constructor(
    val access: RealmAccess,
    val selection: RealmSelection?,
    val target: WebDavTarget,
) {
    internal val closed = AtomicBoolean(false)
    val writable get() = target is WebDavTarget.Personal || (target as WebDavTarget.Enterprise).accessMode == PlatformDAVAccessMode.FULL
    override fun toString() = "WebDavHandle(${target.id})"
}

internal class WebDavApplicationService(
    private val queries: ConfigurationQueryService,
    private val client: WebDavClient,
    private val scope: CoroutineScope,
    private val sessions: EnterpriseSessionController,
    private val platform: PlatformEnterpriseService,
    private val remoteWorkspace: RemoteWorkspaceService,
) {
    private val enterpriseClient = WebDavClient(httpFailure = ::enterpriseDavHttpFailure)
    private val monitor = Any()
    private data class Active(val handle: WebDavHandle, val job: Job)
    private val active = mutableSetOf<Active>()
    private val handles = mutableSetOf<WebDavHandle>()
    private val changingConnections = mutableSetOf<RealmAccess>()
    private data class Target(val scope: ConfigurationScope, val id: String, val root: String, val username: String) {
        override fun toString() = "WebDavTarget($id)"
    }
    private fun WebDavHandle.identity() = Target(access.scope, target.id, target.root,
        (target as? WebDavTarget.Personal)?.connection?.username.orEmpty())
    private val uncertain = mutableMapOf<Target, MutableSet<String>>()
    private val verified = mutableMapOf<WebDavHandle, MutableSet<String>>()

    init {
        scope.launch {
            queries.observe(ConfigurationScope.Personal).collectLatest { configuration ->
                val obsolete = synchronized(monitor) {
                    handles.filter { handle -> handle.access == RealmAccess.Personal &&
                        configuration.personalWebDavConnections.none { sameTarget((handle.target as WebDavTarget.Personal).connection, it) } }
                }
                obsolete.forEach { close(it) }
            }
        }
        scope.launch {
            queries.observeSelectedRealmSelection().collectLatest { selection ->
                val obsolete = synchronized(monitor) { handles.filter { it.selection != null && it.selection != selection } }
                obsolete.forEach { close(it) }
            }
        }
    }

    suspend fun testConnection(selection: RealmSelection, draft: WebDavConnection) {
        require(selection.access == RealmAccess.Personal) { "webdav_enterprise_configuration_read_only" }
        queries.requireSelection(selection)
        val connection = draft.validated()
        check(client.list(connection.rootUrl, Credentials.basic(connection.username, connection.password), depth = 0).self?.directory == true) {
            "webdav_root_not_directory"
        }
        queries.requireSelection(selection)
    }

    suspend fun refresh(selection: RealmSelection) {
        queries.requireSelection(selection)
        if (selection.access is RealmAccess.Enterprise) remoteWorkspace.refresh(selection)
    }

    suspend fun open(selection: RealmSelection, id: String? = null): WebDavHandle {
        queries.requireSelection(selection)
        return capture(selection.access, id, selection)
    }

    private data class ObservedTarget(val target: WebDavTarget, val snapshot: WorkspaceDavSnapshot? = null)

    suspend fun currentTarget(access: RealmAccess, configuration: ResolvedConfiguration): WebDavTarget? =
        observeTarget(access, configuration)?.target

    private suspend fun observeTarget(access: RealmAccess, configuration: ResolvedConfiguration,
        selection: RealmSelection? = null): ObservedTarget? {
        if (access == RealmAccess.Personal) return configuration.personalWebDavConnections.singleOrNull {
            it.id == configuration.currentPersonalWebDavConnectionId
        }?.let { ObservedTarget(WebDavTarget.Personal(it)) }
        val definitions = configuration.enterpriseConfiguration?.webdav.orEmpty().filter { it.enabled }
        if (definitions.isEmpty()) return null
        val enterprise = access as RealmAccess.Enterprise
        val original = if (selection != null) remoteWorkspace.refreshDavProjection(selection)
            else remoteWorkspace.readDavProjection(enterprise)
        val projection = original.projection ?: return null
        val definition = definitions.singleOrNull { it.id == projection.fileExchangeWebdavResourceId } ?: return null
        val binding = projection.webdav?.takeIf { it.resourceId == definition.id }
        return ObservedTarget(WebDavTarget.Enterprise(enterprise, original.connection, definition, projection.agentSpaceId,
            binding?.bindingRevision, binding?.accessMode), original)
    }

    /** Only optional tool assembly may omit a target after a failed status query. */
    suspend fun captureToolTarget(access: RealmAccess, configuration: ResolvedConfiguration): WebDavTarget? =
        try { currentTarget(access, configuration) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Protocol validation and lost Session access share an exception type.
            queries.requireAccess(access)
            android.util.Log.e("WebDav", "Current enterprise DAV could not be resolved", failure)
            null
        }

    suspend fun capture(access: RealmAccess, id: String? = null, selection: RealmSelection? = null): WebDavHandle {
        val configuration = queries.read(access)
        val observed = if (access == RealmAccess.Personal && id != null) {
            configuration.personalWebDavConnections.singleOrNull { it.id.toString() == id }
                ?.let { ObservedTarget(WebDavTarget.Personal(it)) }
        } else observeTarget(access, configuration, selection)?.takeIf { id == null || it.target.id == id }
        val original = observed ?: error("webdav_resource_unavailable")
        return captureTarget(access, original.target, selection, original.snapshot)
    }

    /** The caller supplies the START target; execution never reselects today's current resource. */
    suspend fun captureExpected(access: RealmAccess, target: WebDavTarget, selection: RealmSelection? = null): WebDavHandle =
        captureTarget(access, target, selection, null)

    private suspend fun captureTarget(access: RealmAccess, target: WebDavTarget, selection: RealmSelection?,
        observed: WorkspaceDavSnapshot?): WebDavHandle {
        when (target) {
            is WebDavTarget.Personal -> { require(access == RealmAccess.Personal); target.connection.validated() }
            is WebDavTarget.Enterprise -> { require(access == target.access); target.definition.validate() }
        }
        val handle = WebDavHandle(access, selection, target)
        synchronized(monitor) { handles += handle }
        try {
            // A target resolved in this call already carries its live status response. Reuse that
            // observation once; previously captured Turn targets still fetch and compare fresh status.
            validateAccess(handle, writing = false, local = false, observed = observed)
            if (observed != null) validateLocal(handle)
        } catch (failure: Throwable) { close(handle); throw failure }
        return handle
    }

    /** Called under Session admission. Await the receipt outside that lock. */
    fun revoke(access: RealmAccess): WebDavRevocation = synchronized(monitor) {
        handles.filter { it.access == access }.forEach { it.closed.set(true); verified -= it }
        handles.removeAll { it.access == access }
        val jobs = active.filter { it.handle.access == access }.map { it.job }
        jobs.forEach { it.cancel() }
        WebDavRevocation(jobs)
    }

    suspend fun cancelAndAwait(access: RealmAccess) = revoke(access).awaitClosed()

    suspend fun <T> changeConnection(access: RealmAccess, change: suspend () -> T): T {
        synchronized(monitor) { check(changingConnections.add(access)) { "webdav_connection_change_in_progress" } }
        return try { cancelAndAwait(access); change() }
        finally { synchronized(monitor) { changingConnections -= access } }
    }

    suspend fun close(handle: WebDavHandle) {
        val jobs = synchronized(monitor) {
            handle.closed.set(true)
            handles -= handle
            verified -= handle
            active.filter { it.handle === handle }.map { it.job }.also { jobs -> jobs.forEach { it.cancel() } }
        }
        jobs.joinAll()
    }

    fun closeLater(handle: WebDavHandle) {
        handle.closed.set(true)
        scope.launch { close(handle) }
    }

    suspend fun list(handle: WebDavHandle, path: String = "", depth: Int = 1): DavDirectory = operation(handle) {
        request(handle) { transport, auth -> transport.list(handle.target.root, auth, path, depth) }
    }

    suspend fun stat(handle: WebDavHandle, path: String): DavFile? = operation(handle) {
        request(handle) { transport, auth -> transport.stat(handle.target.root, auth, path) }
    }

    suspend fun download(handle: WebDavHandle, path: String, output: OutputStream, etag: String? = null,
        progress: (Long, Long?) -> Unit = { _, _ -> }, maxBytes: Long = Long.MAX_VALUE,
        contentType: (String?) -> Unit = {}): Long = operation(handle) {
        request(handle) { transport, auth -> transport.download(handle.target.root, auth, path, output, etag, progress, maxBytes, contentType) }
    }

    suspend fun mediaMetadata(handle: WebDavHandle, path: String) = operation(handle) {
        request(handle) { transport, auth -> transport.mediaMetadata(handle.target.root, auth, path) }
    }

    suspend fun mediaRange(handle: WebDavHandle, path: String, metadata: DavMediaMetadata, position: Long, count: Int) = operation(handle) {
        request(handle) { transport, auth -> transport.mediaRange(handle.target.root, auth, path, metadata, position, count) }
    }

    suspend fun isValid(handle: WebDavHandle): Boolean = validity(handle) { validate(handle) }
    suspend fun isValidLocal(handle: WebDavHandle): Boolean = validity(handle) { validateLocal(handle) }

    private suspend fun validity(handle: WebDavHandle, verify: suspend () -> Unit): Boolean = try { verify(); true }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: EnterpriseConfigurationException) {
        val authorized = try {
            handle.selection?.let { queries.requireSelection(it) }
            queries.requireAccess(handle.access)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: EnterpriseConfigurationException) { false }
        if (authorized) throw failure else false
    }
    catch (failure: IllegalStateException) {
        if (failure.message in setOf("webdav_access_closed", "webdav_connection_changed", "webdav_binding_changed",
                "webdav_identity_changed", "webdav_connection_change_in_progress")) false else throw failure
    }

    /** A settled write or publication owns its final access check; keep the surrounding job tracked. */
    internal suspend fun <T> withResource(handle: WebDavHandle, verifyAfter: Boolean = true, block: suspend () -> T): T =
        operation(handle, verifyAfter = verifyAfter, block = block)

    suspend fun <T> publishDownload(handle: WebDavHandle, publish: suspend () -> T): T {
        val guarded: suspend () -> T = {
            currentCoroutineContext().ensureActive()
            check(!handle.closed.get()) { "webdav_access_closed" }
            publish()
        }
        validate(handle)
        return when (val target = handle.target) {
            is WebDavTarget.Personal -> if (handle.selection != null) {
                queries.withPersonalWebDavConnection(handle.selection, target.connection, guarded)
            } else queries.withPersonalWebDavAccess(handle.access, target.connection, guarded)
            is WebDavTarget.Enterprise -> queries.withEnterpriseWebDavAccess(target.access, handle.selection,
                target.definition, target.connection, guarded)
        }
    }

    suspend fun upload(handle: WebDavHandle, path: String, length: Long?, input: () -> InputStream,
        existingEtag: String? = null, progress: (Long, Long?) -> Unit = { _, _ -> }) = write(handle, setOf(path), replay = false) { transport, auth, sent ->
        transport.upload(handle.target.root, auth, path, length, input, existingEtag, sent, progress)
    }

    suspend fun mkdir(handle: WebDavHandle, path: String) = write(handle, setOf(path)) { transport, auth, sent ->
        transport.mkdir(handle.target.root, auth, path, sent)
    }

    suspend fun mutate(handle: WebDavHandle, path: String, method: String, destination: String? = null,
        sourceEtag: String? = null, destinationEtag: String? = null, directory: Boolean = false) = write(handle, setOfNotNull(path, destination)) { transport, auth, sent ->
        transport.mutate(handle.target.root, auth, path, method, destination, sourceEtag, destinationEtag, sent, directory)
    }

    fun unknownPaths(handle: WebDavHandle): Set<String> = synchronized(monitor) { uncertain[handle.identity()]?.toSet().orEmpty() }

    suspend fun verifyUnknown(handle: WebDavHandle, path: String): DavFile? = operation(handle) {
        val result = request(handle) { transport, auth -> transport.stat(handle.target.root, auth, path) }
        validate(handle)
        synchronized(monitor) { verified.getOrPut(handle) { mutableSetOf() }.add(path) }
        result
    }

    /** A fresh read and explicit acknowledgement are both required before another write. */
    suspend fun acknowledgeUnknown(handle: WebDavHandle, paths: Set<String>) {
        validate(handle)
        synchronized(monitor) {
            check(verified[handle].orEmpty().containsAll(paths)) { "webdav_read_target_before_confirmation" }
            verified[handle]?.removeAll(paths)
            uncertain[handle.identity()]?.removeAll(paths)
        }
    }

    private suspend fun write(handle: WebDavHandle, paths: Set<String>, replay: Boolean = true,
        block: suspend (WebDavClient, String, sent: () -> Unit) -> Unit) {
        var sent = false
        operation(handle, onFailure = { failure ->
            val definiteRejection = failure is DavHttpException && failure.status in setOf(400, 401, 403, 404, 405, 409, 412, 415, 423, 501) ||
                failure is PlatformHttpException && failure.problem?.forwarded == false
            if (sent && !definiteRejection) synchronized(monitor) { uncertain.getOrPut(handle.identity()) { mutableSetOf() }.addAll(paths) }
        }) {
            validateLocal(handle, writing = true)
            synchronized(monitor) {
                check(uncertain[handle.identity()].orEmpty().none { unknown -> paths.any {
                    it == unknown || it.startsWith("$unknown/") || unknown.startsWith("$it/")
                } }) { "webdav_result_requires_verification" }
                verified.filterKeys { it.identity() == handle.identity() }.values.forEach { checked ->
                    checked.removeAll { prior -> paths.any { it == prior || it.startsWith("$prior/") || prior.startsWith("$it/") } }
                }
            }
            request(handle, replay, onRejected = { sent = false }, writing = true) { transport, auth -> block(transport, auth) { sent = true } }
        }
    }

    private suspend fun <T> operation(handle: WebDavHandle, verifyAfter: Boolean = true,
        onFailure: (Throwable) -> Unit = {}, block: suspend () -> T): T = coroutineScope {
        val registration = Active(handle, currentCoroutineContext().job)
        synchronized(monitor) { check(!handle.closed.get()) { "webdav_access_closed" }; active += registration }
        try { validateLocal(handle); block().also { if (verifyAfter) validateLocal(handle) } }
        catch (failure: Throwable) { onFailure(failure); throw failure }
        finally { synchronized(monitor) { active -= registration } }
    }

    internal suspend fun validate(handle: WebDavHandle, writing: Boolean = false) = validateAccess(handle, writing, local = false)

    /** Background work checks its own Session and definition, never another page's projection. */
    internal suspend fun validateLocal(handle: WebDavHandle, writing: Boolean = false) = validateAccess(handle, writing, local = true)

    private suspend fun validateAccess(handle: WebDavHandle, writing: Boolean, local: Boolean,
        observed: WorkspaceDavSnapshot? = null) {
        synchronized(monitor) { check(handle.access !in changingConnections) { "webdav_connection_change_in_progress" } }
        check(!handle.closed.get()) { "webdav_access_closed" }
        if (writing) check(handle.writable) { "webdav_read_only" }
        handle.selection?.let { queries.requireSelection(it) }
        val configuration = queries.read(handle.access)
        when (val target = handle.target) {
            is WebDavTarget.Personal -> check(configuration.personalWebDavConnections.any { sameTarget(target.connection, it) }) {
                "webdav_connection_changed"
            }
            is WebDavTarget.Enterprise -> {
                check(configuration.enterpriseConfiguration?.webdav?.any { it.enabled && it.copy(name = target.definition.name) == target.definition } == true) {
                    "webdav_connection_changed"
                }
                check(sessions.platformContext(target.access.sessionId).platform.connection == target.connection) { "webdav_identity_changed" }
                val current = observed ?: if (local) handle.selection?.let { remoteWorkspace.readPublishedDavProjection(it) }
                    else remoteWorkspace.readDavProjection(target.access)
                if (current != null) {
                    check(current.access == target.access) { "webdav_identity_changed" }
                    current.diagnostic?.let { throw java.io.IOException(it) }
                    val projection = current.projection
                    val binding = projection?.webdav
                    check(current.connection == target.connection && projection?.fileExchangeWebdavResourceId == target.id &&
                        projection.agentSpaceId == target.space && target.space != null && binding?.resourceId == target.id &&
                        binding.bindingRevision == target.bindingRevision && target.bindingRevision != null) { "webdav_binding_changed" }
                    if (!binding.available || binding.accessMode == null) throw java.io.IOException("webdav_unavailable: ${binding.reason}")
                    if (writing) check(binding.accessMode == PlatformDAVAccessMode.FULL) { "webdav_read_only" }
                }
            }
        }
        check(!handle.closed.get()) { "webdav_access_closed" }
    }

    private suspend fun <T> request(handle: WebDavHandle, replay: Boolean = true,
        onRejected: () -> Unit = {}, writing: Boolean = false, action: suspend (WebDavClient, String) -> T): T {
        val target = handle.target
        if (target is WebDavTarget.Personal) return action(client, Credentials.basic(target.connection.username, target.connection.password))
        target as WebDavTarget.Enterprise
        val lease = sessions.capturePlatformOperation(target.access.sessionId)
        try {
            check(lease.access == target.access && lease.context.platform.connection == target.connection) { "webdav_identity_changed" }
            val token = platform.accessToken(target.access.sessionId, target.connection)
            // Token acquisition can suspend. Recheck the original identity and write permission
            // before sending; a selected browser does not need another workspace status request.
            validateLocal(handle, writing)
            // One fresh check per background request or mutation, after any token wait. Core
            // owns in-flight authorization; final publication retains its separate fresh check.
            if (writing || handle.selection == null) {
                validate(handle, writing)
                validateLocal(handle, writing)
            }
            return try { action(enterpriseClient, "Bearer ${token.value}") }
            catch (failure: PlatformHttpException) {
                if (failure.problem?.forwarded == false) onRejected()
                if (failure.status != 401 || failure.problem?.forwarded != false ||
                    failure.problem.code !in setOf("invalid_session", "session_refresh_required")) throw failure
                val replacement = try { platform.recoverWorkspaceAccessToken(target.access, target.connection, token) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (refreshFailure: Exception) { failure.addSuppressed(refreshFailure); throw failure }
                validate(handle, writing)
                validateLocal(handle, writing)
                // PUT input may already have been consumed; refresh credentials without replaying its body.
                if (!replay) throw failure
                action(enterpriseClient, "Bearer ${replacement.value}")
            }
        } catch (failure: PlatformHttpException) {
            platform.workspaceFailure(target.access, failure)
            throw failure
        } finally { lease.release() }
    }

    private fun sameTarget(first: WebDavConnection, second: WebDavConnection) = first.id == second.id &&
        first.accessRevision == second.accessRevision && first.rootUrl == second.rootUrl &&
        first.username == second.username && first.password == second.password
}

internal class WebDavRevocation internal constructor(private val jobs: List<Job>) {
    suspend fun awaitClosed() { jobs.joinAll() }
}
