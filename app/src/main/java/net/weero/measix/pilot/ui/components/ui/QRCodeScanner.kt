package net.weero.measix.pilot.ui.components.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scanner owns only camera resources; decoded content is handed to the original import command. */
@Composable
internal fun QRCodeScannerDialog(
    selection: RealmSelection,
    queries: ConfigurationQueryService,
    onResult: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentResult by rememberUpdatedState(onResult)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val open = remember(selection, lifecycleOwner) { AtomicBoolean(true) }
    var permissionGranted by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val dismiss = { open.set(false); currentDismiss() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (open.get()) permissionGranted = granted
    }
    DisposableEffect(lifecycleOwner, selection) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            open.set(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(selection, lifecycleOwner, attempt) {
        permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        try {
            queries.observeSelectedRealmSelection().collect { current ->
                if (current != selection && open.getAndSet(false)) currentDismiss()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logDiagnosticFailure("QRCodeScanner", "Scanner selection observation failed", error)
            failure = error
        }
    }
    LaunchedEffect(lifecycleOwner, selection, permissionGranted, attempt, failure) {
        if (!permissionGranted) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!open.get() || failure != null) return@repeatOnLifecycle
            val gate = QRCodeDeliveryGate()
            try {
                queries.requireSelection(selection)
                coroutineScope {
                    val cameraProvider = ProcessCameraProvider.awaitInstance(context)
                    ensureActive()
                    if (!open.get()) return@coroutineScope
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider { request ->
                            if (open.get() && !gate.isClosed()) surfaceRequest = request else request.willNotProvideSurface()
                        }
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "qr-frame-decoder") }
                    try {
                        analysis.setAnalyzer(executor, QRCodeAnalyzer(
                            onDecoded = { text ->
                                if (gate.queue()) launch(Dispatchers.Main.immediate) {
                                    queries.requireSelection(selection)
                                    ensureActive()
                                    if (open.get() && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && gate.deliver()) {
                                        open.set(false)
                                        currentResult(text)
                                    }
                                }
                            },
                            onFailure = { error ->
                                if (gate.queue()) launch(Dispatchers.Main.immediate) { throw error }
                            },
                        ))
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        awaitCancellation()
                    } finally {
                        gate.close()
                        try {
                            analysis.clearAnalyzer()
                        } finally {
                            try {
                                cameraProvider.unbind(preview, analysis)
                            } finally {
                                surfaceRequest = null
                                executor.shutdownNow()
                                // This scope owns the executor; disposal includes its current frame exiting.
                                withContext(NonCancellable + Dispatchers.IO) {
                                    while (!executor.awaitTermination(1, TimeUnit.SECONDS)) Unit
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (revoked: EnterpriseConfigurationException) {
                // Permission remains with the original selection; never retarget a queued result.
                dismiss()
            } catch (error: Exception) {
                logDiagnosticFailure("QRCodeScanner", "Camera scan failed", error)
                if (open.get()) failure = error
            } finally {
                gate.close()
            }
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
            if (permissionGranted && failure == null) {
                Box(Modifier.align(Alignment.Center).size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(28.dp)))
                Text(stringResource(R.string.setting_provider_page_scan_qr_code),
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(32.dp),
                    color = Color.White, textAlign = TextAlign.Center)
            } else {
                Surface(Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (permissionGranted) R.string.qr_scan_failed else R.string.qr_camera_permission))
                        failure?.let { DiagnosticDisclosure(it.userVisibleDiagnostic()) }
                        Button(onClick = {
                            if (permissionGranted) { failure = null; attempt++ }
                            else permissionLauncher.launch(Manifest.permission.CAMERA)
                        }) { Text(stringResource(R.string.application_recovery_retry)) }
                        if (!permissionGranted) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text(stringResource(R.string.settings)) }
                    }
                }
            }
            IconButton(onClick = dismiss, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
                Icon(HugeIcons.Cancel01, stringResource(R.string.cancel), tint = Color.White)
            }
        }
    }
}

/** ImageProxy belongs to the analyzer until this call exits, including decode and callback failures. */
internal class QRCodeAnalyzer(
    private val onDecoded: (String) -> Unit,
    private val onFailure: (Exception) -> Unit,
) : ImageAnalysis.Analyzer {
    private val decoder = QRCodeDecoder()
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.first()
            val buffer = plane.buffer.duplicate()
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            decoder.decode(bytes, plane.rowStride, image.width, image.height, plane.pixelStride)?.let(onDecoded)
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            image.close()
        }
    }
}
