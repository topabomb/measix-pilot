package net.weero.measix.pilot.ui.components.ui.permission

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import net.weero.measix.pilot.R

/** Optional startup permission; saved state prevents another request during Activity recreation. */
@Composable
fun StartupLocalNetworkPermission() {
    if (Build.VERSION.SDK_INT < 37) return

    val context = LocalContext.current
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, R.string.permission_local_network_denied, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!requested) {
            requested = true
            if (ContextCompat.checkSelfPermission(context, PermissionLocalNetwork.permission) !=
                PackageManager.PERMISSION_GRANTED) {
                launcher.launch(PermissionLocalNetwork.permission)
            }
        }
    }
}
