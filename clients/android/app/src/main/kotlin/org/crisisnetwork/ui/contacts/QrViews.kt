package org.crisisnetwork.android.ui.contacts

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import org.crisisnetwork.android.messaging.QrCodes
import org.crisisnetwork.android.messaging.QrDecoder

/**
 * Draws [text] as a QR code: black modules on white with a quiet zone, whatever the theme is, because
 * readers need the contrast. [description] is what a screen reader says instead of the picture.
 */
@Composable
fun QrCodeView(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrCodes.encode(text) }
    Canvas(
        modifier =
            modifier
                .aspectRatio(1f)
                .background(Color.White)
                .semantics { contentDescription = description },
    ) {
        val quiet = 4
        val cell = size.minDimension / (matrix.size + 2 * quiet)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        // A little more than a cell, so that no hairline gaps appear between modules.
                        size = DrawSize(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/**
 * The camera preview that reads QR codes: CameraX delivers frames, ZXing looks for a code in each.
 * [onText] is called on the main thread with the text of every code found (the same code again and
 * again while it stays in view; the caller decides what to do with it). Nothing is recorded or saved.
 */
@Composable
fun QrCameraPreview(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { QrDecoder() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentOnText by rememberUpdatedState(onText)
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    if (owner == null) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val analysis =
                        ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                    .build(),
                            ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            // The first plane of a camera frame is the brightness: all a QR reader needs.
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            val text = decoder.decode(data, plane.rowStride, image.width, image.height)
                            if (text != null) mainHandler.post { currentOnText(text) }
                        } finally {
                            image.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                },
                ContextCompat.getMainExecutor(ctx),
            )
            view
        },
    )
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return null
}
