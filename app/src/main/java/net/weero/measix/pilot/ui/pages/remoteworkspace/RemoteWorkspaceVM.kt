package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.content.Context
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceBrowserSource
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceService
import net.weero.measix.pilot.ui.components.files.FileBrowserVM

internal class RemoteWorkspaceVM(
    val selection: RealmSelection?,
    service: RemoteWorkspaceService,
    context: Context,
) : FileBrowserVM(RemoteWorkspaceBrowserSource(service, selection), context) {
    val summary = service.summary
}
