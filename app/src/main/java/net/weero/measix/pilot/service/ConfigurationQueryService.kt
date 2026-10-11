package net.weero.measix.pilot.service

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationCatalogItem
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.model.Assistant
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.PlatformDAVAccessMode
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceService
import net.weero.measix.pilot.utils.userVisibleDiagnostic

private sealed interface ConfigurationReadFailure {
    data object SelectionUnavailable : ConfigurationReadFailure
    data class Unexpected(val detail: String) : ConfigurationReadFailure
}

internal class ConfigurationQueryService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
    private val remoteWorkspace: RemoteWorkspaceService,
) {
    fun observeWebDavConfiguration(): Flow<WebDavConfigurationReadState> = observeSelected<WebDavConfigurationReadState>(
        { WebDavConfigurationReadState.Unavailable(it.detail()) },
        { configuration, selection ->
            WebDavConfigurationReadState.Available(
                selection, configuration.personalWebDavConnections, configuration.currentPersonalWebDavConnectionId, configuration.webDavBackup,
                enterpriseResources = configuration.enterpriseConfiguration?.webdav.orEmpty().filter { it.enabled }.map {
                    WebDavResourceSummary(it.id, it.name, false, false, it.copy(name = "").toString(), "webdav_binding_unavailable")
                },
            )
        },
    ).flatMapLatest { state ->
        if (state !is WebDavConfigurationReadState.Available || state.selection.access == RealmAccess.Personal || state.enterpriseResources.isEmpty()) {
            flowOf(state)
        } else remoteWorkspace.observeDavProjection().map { snapshot ->
            val original = snapshot?.takeIf { it.access == state.selection.access }
            val projection = original?.projection
            state.copy(designatedId = projection?.fileExchangeWebdavResourceId, enterpriseResources = state.enterpriseResources.map { resource ->
                val binding = projection?.webdav?.takeIf { it.resourceId == resource.id }
                val available = original?.diagnostic == null && binding?.available == true && binding.accessMode != null && binding.bindingRevision != null && projection.agentSpaceId != null
                resource.copy(available = available, writable = available && binding?.accessMode == PlatformDAVAccessMode.FULL,
                    revision = "${resource.revision}:${original?.connection}:${projection?.agentSpaceId}:${binding?.bindingRevision}:${binding?.accessMode}",
                    detail = original?.diagnostic ?: binding?.reason ?: "webdav_binding_unavailable")
            })
        }
    }

    private suspend fun <T> readEnterpriseResource(target: EnterpriseResourceDetailTarget, project: (ResolvedConfiguration) -> T): T {
        recoveryGate.awaitReady()
        return enterpriseSessions.withSelectedRealmSelection(target.selection) {
            enterpriseSessions.requirePublishedRealmAccess(target.access)
            settings.withResolvedConfiguration(target.access.scope, enterpriseSessions.state.value) { configuration ->
                check(configuration.enterpriseConfiguration?.generation == target.generation &&
                    configuration.enterpriseIdentity?.authority == target.reference.authority) { "enterprise_configuration_changed" }
                enterpriseSessions.requirePublishedSelection(target.selection)
                enterpriseSessions.requirePublishedRealmAccess(target.access)
                project(configuration)
            }
        }
    }

    internal suspend fun readEnterpriseAssistantDefinition(target: EnterpriseResourceDetailTarget): Pair<Assistant, AssistantModelSummary> =
        readEnterpriseResource(target) { configuration ->
            check(target.kind == EnterpriseConfigurationResourceKind.ASSISTANT)
            val definition = requireNotNull(configuration.enterpriseConfiguration).assistants.single { it.id == target.reference.id }
            val assistant = net.weero.measix.pilot.data.configuration.resolveEnterpriseAssistantUsage(
                requireNotNull(configuration.enterpriseIdentity), definition, null)
            assistant to configuration.modelCatalog(target.selection).modelSummaryFor(assistant)
        }

    internal suspend fun readEnterpriseStarterDetails(target: EnterpriseResourceDetailTarget): StarterOpeningDetailUiModel =
        readEnterpriseResource(target) { configuration ->
            check(target.kind == EnterpriseConfigurationResourceKind.STARTER)
            val starter = configuration.enterpriseConfiguration?.starters?.singleOrNull { it.id == target.reference.id }
                ?: throw StarterOpeningException(StarterOpeningIssue.UNAVAILABLE)
            starter.details(isDraft = false)
        }

    internal suspend fun readEnterpriseResourcePrompt(target: EnterpriseResourceDetailTarget): String? =
        readEnterpriseResource(target) { configuration ->
            val definitions = requireNotNull(configuration.enterpriseConfiguration)
            when (target.kind) {
                EnterpriseConfigurationResourceKind.TTS -> definitions.tts.single { it.id == target.reference.id }.voiceDesignPrompt
                EnterpriseConfigurationResourceKind.ASR -> definitions.asr.single { it.id == target.reference.id }.prompt
                else -> error("enterprise_resource_prompt_not_applicable")
            }
        }
    fun observePersonalModelFavorites() = settings.observeConfiguration(enterpriseSessions.state, ConfigurationScope.Personal)
        .map { it.selections.favoriteModels }

    fun observeCollapsedModelGroups(selection: RealmSelection?): Flow<Result<Set<String>>> = flow {
        recoveryGate.awaitReady()
        if (selection == null) emitAll(settings.observeCollapsedModelGroups(ConfigurationScope.Personal))
        else emitAll(enterpriseSessions.observeSelectedRealmSelection().flatMapLatest { current ->
            if (current == selection) settings.observeCollapsedModelGroups(selection.access.scope)
            else flowOf(emptySet())
        })
    }.map { Result.success(it) }.catch { error ->
        if (error is CancellationException) throw error
        Log.e("ConfigurationQuery", "Model group preferences could not be read", error)
        emit(Result.failure(error))
    }

    fun observeModelCatalog(): Flow<ModelCatalogReadState> = observeSelected(
        { failure -> ModelCatalogReadState.Unavailable(when (failure) {
            ConfigurationReadFailure.SelectionUnavailable -> "configuration_view_unavailable"
            is ConfigurationReadFailure.Unexpected -> failure.detail
        }) },
        { configuration, selection -> ModelCatalogReadState.Available(configuration.modelCatalog(selection)) },
    )

    fun observeAssistantCatalog(): Flow<AssistantCatalogReadState> = observeSelected(
        { failure -> AssistantCatalogReadState.Unavailable(failure.detail()) },
        { configuration, selection ->
            val modelCatalog = configuration.modelCatalog(selection).forSlot(ResourceSelectionSlot.CHAT_MODEL)
            AssistantCatalogReadState.Available(AssistantCatalogUiModel(
                selection,
                configuration.selection(ResourceSelectionSlot.ASSISTANT),
                configuration.assistants,
                configuration.catalog.values.filter { it.key.category == ConfigurationCategory.ASSISTANT },
                configuration.assistants.mapValues { (_, assistant) -> modelCatalog.modelSummaryFor(assistant) },
                configuration.enterpriseIdentity?.enterpriseName,
            ))
        },
    )

    fun observeEnterpriseStarters(selection: RealmSelection): Flow<EnterpriseStarterReadState> = observeSelected(
        { failure -> when (failure) {
            ConfigurationReadFailure.SelectionUnavailable -> EnterpriseStarterReadState.SourceChanged
            is ConfigurationReadFailure.Unexpected -> EnterpriseStarterReadState.Failed(failure.detail)
        } },
        { configuration, current ->
            configuration.takeIf { current == selection }?.enterpriseStarterCatalog(current)
                ?.let { EnterpriseStarterReadState.Available(it) } ?: EnterpriseStarterReadState.SourceChanged
        },
    )

    internal suspend fun readEnterpriseStarterDetails(target: EnterpriseStarterTarget): StarterOpeningDetailUiModel {
        recoveryGate.awaitReady()
        return enterpriseSessions.withSelectedRealmSelection(target.selection) {
            settings.withResolvedConfiguration(target.selection.access.scope, enterpriseSessions.state.value) { configuration ->
                enterpriseSessions.requirePublishedSelection(target.selection)
                check(configuration.enterpriseConfiguration?.generation == target.generation &&
                    configuration.enterpriseIdentity?.authority == target.reference.authority) { "enterprise_configuration_changed" }
                val starter = configuration.availableStarters().singleOrNull { it.id == target.reference.id }
                    ?: throw StarterOpeningException(StarterOpeningIssue.UNAVAILABLE)
                starter.details(isDraft = false)
            }
        }
    }

    fun observeSpeechCatalog(): Flow<SpeechCatalogReadState> = observeSelected(
        { failure -> SpeechCatalogReadState.Unavailable(failure.detail()) },
        { configuration, selection -> SpeechCatalogReadState.Available(SpeechCatalogUiModel(selection,
            configuration.selection(ResourceSelectionSlot.TTS), configuration.selection(ResourceSelectionSlot.ASR),
            configuration.catalog.values.filter { it.key.category in setOf(ConfigurationCategory.TTS, ConfigurationCategory.ASR) },
            configuration.enterpriseConfiguration?.tts.orEmpty(), configuration.enterpriseConfiguration?.asr.orEmpty())) },
    )

    private fun <T> observeSelected(
        unavailable: (ConfigurationReadFailure) -> T,
        project: (ResolvedConfiguration, RealmSelection) -> T,
    ): Flow<T> = flow {
        recoveryGate.awaitReady()
        emitAll(enterpriseSessions.observeSelectedRealmSelection().flatMapLatest { selection ->
            if (selection == null) flowOf(unavailable(ConfigurationReadFailure.SelectionUnavailable))
            else settings.observeConfiguration(enterpriseSessions.state, selection.access.scope)
                .map { enterpriseSessions.withSelectedRealmSelection(selection) {
                    settings.withResolvedConfiguration(selection.access.scope, enterpriseSessions.state.value) { configuration ->
                        enterpriseSessions.requirePublishedSelection(selection)
                        project(configuration, selection)
                    }
                } }.catch { error ->
                    if (error is CancellationException) throw error
                    Log.e("ConfigurationQuery", "Selected configuration projection failed", error)
                    emit(unavailable(ConfigurationReadFailure.Unexpected(error.userVisibleDiagnostic())))
                }
        })
    }.catch { error ->
        if (error is CancellationException) throw error
        Log.e("ConfigurationQuery", "Configuration projection failed", error)
        emit(unavailable(ConfigurationReadFailure.Unexpected(error.userVisibleDiagnostic())))
    }

    suspend fun captureAccess(scope: ConfigurationScope): RealmAccess {
        recoveryGate.awaitReady()
        return enterpriseSessions.captureRealmAccess(scope)
    }

    suspend fun requireAccess(access: RealmAccess) {
        recoveryGate.awaitReady()
        enterpriseSessions.withRealmAccess(access) { }
    }

    suspend fun requireSelection(selection: RealmSelection) {
        recoveryGate.awaitReady()
        enterpriseSessions.withSelectedRealmSelection(selection) { }
    }

    /** System handoff cannot race a committed connection edit or selected-domain change. */
    internal suspend fun <T> withPersonalWebDavConnection(selection: RealmSelection,
        expected: net.weero.measix.pilot.data.configuration.WebDavConnection, action: suspend () -> T): T {
        recoveryGate.awaitReady()
        require(selection.access == RealmAccess.Personal) { "webdav_personal_resource_required" }
        return enterpriseSessions.withSelectedRealmSelection(selection) {
            settings.withResolvedConfiguration(selection.access.scope, enterpriseSessions.state.value) { configuration ->
                check(configuration.personalWebDavConnections.any { it.copy(name = expected.name) == expected }) {
                    "webdav_connection_changed"
                }
                enterpriseSessions.requirePublishedSelection(selection)
                action()
            }
        }
    }

    /** Publish under the original Session and resource definition; remote IO is checked before this boundary. */
    internal suspend fun <T> withEnterpriseWebDavAccess(
        access: RealmAccess.Enterprise,
        selection: RealmSelection?,
        expected: net.weero.measix.pilot.data.enterprise.EnterpriseWebDavResource,
        connection: net.weero.measix.pilot.data.enterprise.PlatformConnection,
        action: suspend () -> T,
    ): T {
        recoveryGate.awaitReady()
        val guarded: suspend () -> T = {
            settings.withResolvedConfiguration(access.scope, enterpriseSessions.state.value) { configuration ->
                enterpriseSessions.requirePublishedRealmAccess(access)
                selection?.let(enterpriseSessions::requirePublishedSelection)
                val manifest = (enterpriseSessions.state.value as? net.weero.measix.pilot.data.enterprise.EnterpriseState.Available)?.manifest
                check(manifest?.session?.platform?.connection == connection) { "webdav_connection_changed" }
                check(configuration.enterpriseConfiguration?.webdav?.any { it.enabled && it.copy(name = expected.name) == expected } == true) {
                    "webdav_connection_changed"
                }
                action()
            }
        }
        return if (selection != null) enterpriseSessions.withSelectedRealmSelection(selection, guarded)
        else enterpriseSessions.withRealmAccess(access, guarded)
    }

    internal suspend fun read(access: RealmAccess): ResolvedConfiguration = readExecution(access).configuration

    /** A Turn keeps its captured resource even when only the user's current selection changes. */
    internal suspend fun <T> withPersonalWebDavAccess(access: RealmAccess,
        expected: net.weero.measix.pilot.data.configuration.WebDavConnection, action: suspend () -> T): T {
        recoveryGate.awaitReady()
        require(access == RealmAccess.Personal) { "webdav_personal_resource_required" }
        return enterpriseSessions.withRealmAccess(access) {
            settings.withResolvedConfiguration(access.scope, enterpriseSessions.state.value) { configuration ->
                check(configuration.personalWebDavConnections.any { it.copy(name = expected.name) == expected }) {
                    "webdav_connection_changed"
                }
                enterpriseSessions.requirePublishedRealmAccess(access)
                action()
            }
        }
    }

    internal suspend fun readExecution(access: RealmAccess): net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot {
        recoveryGate.awaitReady()
        return enterpriseSessions.withRealmAccess(access) {
            settings.withExecutionConfiguration(access.scope, enterpriseSessions.state.value) {
                enterpriseSessions.requirePublishedRealmAccess(access)
                it
            }
        }
    }

    fun observeCurrent(): Flow<ResolvedConfiguration> = settings.observeConfiguration(enterpriseSessions.state)

    fun observeSelectedRealmSelection(): Flow<RealmSelection?> = enterpriseSessions.observeSelectedRealmSelection()

    fun observe(scope: ConfigurationScope): Flow<ResolvedConfiguration> =
        settings.observeConfiguration(enterpriseSessions.state, scope)
}

private fun ConfigurationReadFailure.detail(): String = when (this) {
    ConfigurationReadFailure.SelectionUnavailable -> "configuration_view_unavailable"
    is ConfigurationReadFailure.Unexpected -> detail
}

internal sealed interface AssistantCatalogReadState {
    data object Loading : AssistantCatalogReadState
    data class Available(val catalog: AssistantCatalogUiModel) : AssistantCatalogReadState
    data class Unavailable(val detail: String) : AssistantCatalogReadState
}

internal sealed interface SpeechCatalogReadState {
    data object Loading : SpeechCatalogReadState
    data class Available(val catalog: SpeechCatalogUiModel) : SpeechCatalogReadState
    data class Unavailable(val detail: String) : SpeechCatalogReadState
}

internal data class AssistantCatalogUiModel(
    val selection: RealmSelection,
    val selected: ConfigurationSelection,
    val assistants: Map<ConfigurationReference, Assistant>,
    val resources: List<ConfigurationCatalogItem>,
    val modelSummaries: Map<ConfigurationReference, AssistantModelSummary> = emptyMap(),
    val enterpriseName: String? = null,
)

internal data class SpeechCatalogUiModel(
    val selection: RealmSelection,
    val selectedTts: ConfigurationSelection,
    val selectedAsr: ConfigurationSelection,
    val resources: List<ConfigurationCatalogItem>,
    val managedTts: List<net.weero.measix.pilot.data.enterprise.EnterpriseTtsResource>,
    val managedAsr: List<net.weero.measix.pilot.data.enterprise.EnterpriseAsrResource>,
)
