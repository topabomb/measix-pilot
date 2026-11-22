package net.weero.measix.pilot.ui.components.ui.permission

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 创建并记住权限状态
 *
 * @param permissions 权限信息集合
 * @return PermissionState 权限状态管理对象
 *
 * 使用示例:
 * ```
 * val permissionState = rememberPermissionState(
 *     permissions = setOf(
 *         PermissionInfo(
 *             permission = Manifest.permission.CAMERA,
 *             usage = { Text("需要相机权限来拍照") },
 *             required = true
 *         ),
 *         PermissionInfo(
 *             permission = Manifest.permission.RECORD_AUDIO,
 *             usage = { Text("需要录音权限来录制视频") },
 *             required = false
 *         )
 *     )
 * )
 *
 * // 请求权限
 * Button(onClick = { permissionState.requestPermissions() }) {
 *     Text("请求权限")
 * }
 *
 * // 检查权限状态
 * if (permissionState.allRequiredPermissionsGranted) {
 *     Text("所有必需权限已授权")
 * }
 * ```
 */
@Composable
fun rememberPermissionState(
    permissions: Set<PermissionInfo>
): PermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
        ?: throw IllegalStateException("rememberPermissionState 必须在 ComponentActivity 中使用")

    val permissionState = remember(permissions) {
        PermissionState(permissions, context, activity)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionState.handlePermissionResult(results)
    }
    LaunchedEffect(permissionState, permissionLauncher) {
        permissionState.setPermissionLauncher(permissionLauncher)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permissionState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState.refreshPermissionStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return permissionState
}

/**
 * 创建并记住单个权限状态
 *
 * @param permission 权限字符串
 * @param usage 权限使用说明
 * @param required 是否为必需权限
 * @return PermissionState 权限状态管理对象
 */
@Composable
fun rememberPermissionState(
    permission: String,
    displayName: @Composable () -> Unit,
    usage: @Composable () -> Unit,
    required: Boolean = false
): PermissionState {
    return rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = permission,
                displayName = displayName,
                usage = usage,
                required = required,
            )
        )
    )
}

@Composable
fun rememberPermissionState(
    info: PermissionInfo
): PermissionState {
    return rememberPermissionState(
        permissions = setOf(info)
    )
}
