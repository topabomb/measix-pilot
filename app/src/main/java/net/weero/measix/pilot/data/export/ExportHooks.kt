package net.weero.measix.pilot.data.export

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
class ImporterState<T>(
    private val serializer: ExportSerializer<T>,
    private val context: Context,
    private val scope: CoroutineScope,
    private val openDocumentLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    private val onResult: (Result<T>) -> Unit,
) {
    fun importFromFile() {
        openDocumentLauncher.launch(arrayOf("*/*"))
    }

    internal fun handleUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val fileName = serializer.getUriFileName(context, uri)
                if (fileName != null && !fileName.endsWith(".json", ignoreCase = true)) {
                    return@withContext Result.failure<T>(IllegalArgumentException("Not a JSON file"))
                }
                serializer.import(context, uri)
            }
            onResult(result)
        }
    }
}

@Composable
fun <T> rememberImporter(
    serializer: ExportSerializer<T>,
    onResult: (Result<T>) -> Unit,
): ImporterState<T> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingState by remember { mutableStateOf<ImporterState<T>?>(null) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pendingState?.handleUri(it) }
    }

    val state = remember(serializer) {
        ImporterState(
            serializer = serializer,
            context = context,
            scope = scope,
            openDocumentLauncher = openDocumentLauncher,
            onResult = onResult,
        ).also { pendingState = it }
    }

    return state
}
