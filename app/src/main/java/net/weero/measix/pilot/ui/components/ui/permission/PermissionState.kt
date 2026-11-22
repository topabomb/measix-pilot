package net.weero.measix.pilot.ui.components.ui.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

/**
 * 权限状态管理类
 */
@Stable
class PermissionState internal constructor(
    private val permissions: Set<PermissionInfo>,
    private val context: Context,
    private val activity: ComponentActivity
) {
    // 权限状态映射
    private val _permissionStates = mutableStateMapOf<String, PermissionStatus>()
    val permissionStates: Map<String, PermissionStatus> = _permissionStates

    var currentRationalePermissions by mutableStateOf<List<PermissionInfo>>(emptyList())
        private set

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    init {
        refreshPermissionStates()
    }

    internal fun setPermissionLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        permissionLauncher = launcher
    }

    internal fun refreshPermissionStates() {
        for (info in permissions) {
            val permission = info.permission
            val oldStatus = permissionStates[permission]
            val wasDenied = oldStatus == PermissionStatus.Denied || oldStatus == PermissionStatus.DeniedPermanently
            _permissionStates[permission] = readPermissionStatus(permission, wasDenied)
        }
    }

    private fun readPermissionStatus(permission: String, wasDenied: Boolean): PermissionStatus = when {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ->
            PermissionStatus.Granted
        activity.shouldShowRequestPermissionRationale(permission) -> PermissionStatus.Denied
        // 系统不显示说明时，已有未授权结果由系统设置兜底。
        wasDenied -> PermissionStatus.DeniedPermanently
        else -> PermissionStatus.NotRequested
    }

    /**
     * 检查是否所有权限都已授权
     */
    val allPermissionsGranted: Boolean
        get() = permissions.all { permissionStates[it.permission] == PermissionStatus.Granted }

    /**
     * 检查是否所有必需权限都已授权
     */
    val allRequiredPermissionsGranted: Boolean
        get() = permissions.filter { it.required }.all { permissionStates[it.permission] == PermissionStatus.Granted }

    /**
     * 获取永久拒绝的权限
     */
    val permanentlyDeniedPermissions: List<PermissionInfo>
        get() = permissions.filter { permissionStates[it.permission] == PermissionStatus.DeniedPermanently }

    fun requestPermissions() {
        refreshPermissionStates()
        val deniedPermissions = permissions.filter { permissionStates[it.permission] != PermissionStatus.Granted }
        val rationalePermissions = deniedPermissions.filter {
            permissionStates[it.permission] != PermissionStatus.NotRequested
        }
        if (rationalePermissions.isNotEmpty()) {
            currentRationalePermissions = rationalePermissions
        } else {
            launchPermissionRequest(deniedPermissions)
        }
    }

    fun proceedFromRationale() {
        val request = currentRationalePermissions
        currentRationalePermissions = emptyList()
        if (request.any { permissionStates[it.permission] == PermissionStatus.DeniedPermanently }) {
            openAppSettings()
        } else {
            launchPermissionRequest(request)
        }
    }

    fun cancelPermissionRequest() {
        currentRationalePermissions = emptyList()
    }

    private fun launchPermissionRequest(request: List<PermissionInfo>) {
        if (request.isNotEmpty()) {
            permissionLauncher?.launch(request.map { it.permission }.toTypedArray())
        }
    }

    /**
     * 跳转到应用设置页面
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        activity.startActivity(intent)
    }

    internal fun handlePermissionResult(results: Map<String, Boolean>) {
        for ((permission, granted) in results) {
            if (permission !in permissionStates) continue
            _permissionStates[permission] = if (granted) {
                PermissionStatus.Granted
            } else {
                readPermissionStatus(permission, wasDenied = true)
            }
        }
    }
}
