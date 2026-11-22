package net.weero.measix.pilot.ui.components.ui.permission

import androidx.compose.runtime.Composable

/**
 * 权限管理器组件
 * 自动处理权限请求对话框的显示和隐藏
 *
 * 使用方式：
 * ```
 * val permissionState = rememberPermissionState(permissions)
 *
 * PermissionManager(permissionState = permissionState) {
 *     // 你的UI内容
 *     YourContent()
 * }
 * ```
 */
@Composable
fun PermissionManager(
    permissionState: PermissionState,
    content: @Composable () -> Unit = {},
) {
    val permissions = permissionState.currentRationalePermissions
    if (permissions.isNotEmpty()) {
        PermissionRationaleDialog(
            permissions = permissions,
            permanentlyDeniedPermissions = permissionState.permanentlyDeniedPermissions,
            onProceed = {
                permissionState.proceedFromRationale()
            },
            onCancel = {
                permissionState.cancelPermissionRequest()
            }
        )
    }

    // 主要内容
    content()
}
