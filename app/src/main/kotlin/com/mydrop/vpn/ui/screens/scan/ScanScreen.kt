package com.mydrop.vpn.ui.screens.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a server or subscription straight off someone else's screen.
 *
 * The app already understood the text inside a QR code — every share link scheme routes through
 * the same importer — but getting that text in needed a second app and a round trip through the
 * clipboard, which is most of the friction in passing a server to a friend.
 */
@Composable
fun ScanScreen(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraViewfinder(onResult = onResult, onBack = onBack)
        } else {
            PermissionPrompt(
                onGrant = { request.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(contentPadding).padding(8.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
        }
    }
}

@Composable
private fun CameraViewfinder(onResult: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onResult)
    val dismiss by rememberUpdatedState(onBack)

    // One frame decodes into one import. Without the latch a code held in view for half a second
    // is read a dozen times, and the importer runs a dozen times with it.
    val consumed = remember { AtomicBoolean(false) }
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    LaunchedEffect(Unit) {
        // The provider future resolves on a camera thread and blocks until the process-wide
        // CameraX singleton has initialised, which is not something to wait for on the main one.
        val provider = withContext(Dispatchers.IO) {
            runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
        } ?: return@LaunchedEffect

        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            // Decoding is slower than the camera produces frames; queueing them would only make
            // the viewfinder lag behind what the phone is pointed at.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { image ->
                    val text = image.decodeQr()
                    if (text != null && consumed.compareAndSet(false, true)) {
                        previewView.post {
                            deliver(text)
                            dismiss()
                        }
                    }
                }
            }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A frame rather than a scrim: it says where to aim without dimming the very thing the
        // camera is trying to resolve.
        Box(
            modifier = Modifier
                .size(248.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.PhotoCamera,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Чтобы прочитать QR-код, нужен доступ к камере. " +
                "Кадры не сохраняются и никуда не отправляются — они разбираются на устройстве.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Разрешить") }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Decodes the frame's luminance plane, closing the frame either way — an unclosed [ImageProxy]
 * stalls the whole analysis pipeline after a couple of frames.
 */
private fun ImageProxy.decodeQr(): String? = try {
    val plane = planes[0]
    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    val source = PlanarYUVLuminanceSource(
        bytes,
        plane.rowStride,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    val reader = qrReader.get()
    val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
        .getOrNull()
    // A code shown as light-on-dark — which is what a phone in dark theme puts on screen — is
    // the inverse of what a decoder expects, so the frame is worth a second look.
        ?: runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert())))
        }.getOrNull()
    reader.reset()
    result?.text
} catch (_: Exception) {
    null
} finally {
    close()
}

/** The reader keeps decode state, so each analysis thread gets its own. */
private val qrReader = ThreadLocal.withInitial {
    MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
}
