package com.cammonitor.ui.pairing

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cammonitor.CamMonitorApp
import com.cammonitor.data.model.PairedDevice
import com.cammonitor.data.model.PairingPayload
import com.cammonitor.data.model.SignalingMessage
import com.cammonitor.data.security.CryptoUtils
import com.cammonitor.log.AppLogger
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerPairingScreen(
    onPairingSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient

    var isScanning by remember { mutableStateOf(true) }
    var pairingStatus by remember { mutableStateOf("Align QR code within the frame to pair") }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var isTorchEnabled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            securityRepo.clearPairingOtp()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Scan Camera QR Code") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isTorchEnabled = !isTorchEnabled
                        cameraControl?.enableTorch(isTorchEnabled)
                    }) {
                        Icon(Icons.Default.FlashOn, contentDescription = "Torch")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // CameraX Viewfinder
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val executor = Executors.newSingleThreadExecutor()
                        val reader = MultiFormatReader()

                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            if (!isScanning) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val result = scanQrFromImageProxy(imageProxy, reader)
                            imageProxy.close()

                            if (result != null) {
                                isScanning = false
                                coroutineScope.launch {
                                    handleScannedQr(
                                        result,
                                        app,
                                        securityRepo,
                                        signalingClient,
                                        onSuccess = onPairingSuccess,
                                        onRetry = { isScanning = true }
                                    ) { status ->
                                        pairingStatus = status
                                    }
                                }
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            cameraControl = cam.cameraControl
                        } catch (e: Exception) {
                            AppLogger.e("ViewerPairing", "CameraX binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Scanning Reticle Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = 260.dp.toPx()
                val left = (size.width - boxSize) / 2
                val top = (size.height - boxSize) / 2

                val backgroundPath = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                }
                val cutoutPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(left, top, left + boxSize, top + boxSize),
                            cornerRadius = CornerRadius(24.dp.toPx())
                        )
                    )
                }

                val overlayPath = Path.combine(
                    operation = androidx.compose.ui.graphics.PathOperation.Difference,
                    path1 = backgroundPath,
                    path2 = cutoutPath
                )
                drawPath(overlayPath, Color.Black.copy(alpha = 0.6f))
            }

            // Bottom Prompt
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
            ) {
                Text(
                    text = pairingStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun scanQrFromImageProxy(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes[0]
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val width = image.width
    val height = image.height
    val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

    return try {
        val result = reader.decodeWithState(binaryBitmap)
        result.text
    } catch (e: Exception) {
        null
    }
}

private suspend fun handleScannedQr(
    qrText: String,
    app: CamMonitorApp,
    securityRepo: com.cammonitor.data.security.SecurityRepository,
    signalingClient: com.cammonitor.data.signaling.SignalingClient,
    onSuccess: () -> Unit,
    onRetry: () -> Unit,
    updateStatus: (String) -> Unit
) {
    val payload = PairingPayload.fromJson(qrText)
    if (payload == null) {
        updateStatus("Invalid CamMonitor QR code format")
        delay(2000L)
        onRetry()
        return
    }

    // Set active pairing OTP so SignalingClient can decrypt PAIR_CONFIRM
    securityRepo.setActivePairingOtp(payload.secret)

    updateStatus("Valid QR detected. Connecting to signaling broker...")

    val connected = signalingClient.startListening()
    if (!connected) {
        updateStatus("Broker connection failed. Retrying...")
    }

    val otpKey = CryptoUtils.hkdfSha256(
        payload.secret.toByteArray(Charsets.UTF_8),
        ByteArray(0),
        "CamMonitor-OTP".toByteArray(),
        32
    )

    val request = SignalingMessage.PairRequest(
        pbDeviceId = securityRepo.getDeviceId(),
        pbName = securityRepo.getDeviceName(),
        pbPublicKeyBase64 = securityRepo.getDevicePublicKeyBase64()
    )

    updateStatus("Authenticating with ${payload.name}...")

    var isConfirmed = false

    withTimeoutOrNull(20_000L) {
        val retryScope = CoroutineScope(Dispatchers.IO)
        val retryJob = retryScope.launch {
            var attempts = 0
            while (!isConfirmed && attempts < 15) {
                attempts++
                AppLogger.i("ViewerPairing", "Sending PAIR_REQUEST to ${payload.deviceId} (Attempt #$attempts)")
                signalingClient.sendSignalingMessage(payload.deviceId, request, otpKey)
                delay(1200L)
            }
        }

        try {
            signalingClient.incomingMessages.collect { (senderId, msg) ->
                if (msg is SignalingMessage.PairConfirm && senderId == payload.deviceId) {
                    isConfirmed = true
                    retryJob.cancel()
                    securityRepo.clearPairingOtp()
                    AppLogger.i("ViewerPairing", "Received PAIR_CONFIRM from ${msg.paName}")
                    val pairedCam = PairedDevice(
                        deviceId = msg.paDeviceId,
                        name = msg.paName,
                        publicKeyBase64 = msg.paPublicKeyBase64,
                        pairedTimestamp = System.currentTimeMillis()
                    )
                    securityRepo.addPairedDevice(pairedCam)
                    updateStatus("Pairing successful! Starting viewer...")
                    delay(300L)
                    onSuccess()
                    return@collect
                }
            }
        } finally {
            retryJob.cancel()
        }
    }

    if (!isConfirmed) {
        AppLogger.w("ViewerPairing", "Pairing timed out with ${payload.name}")
        securityRepo.clearPairingOtp()
        updateStatus("Pairing timed out. Align QR code again to retry.")
        delay(2500L)
        onRetry()
    }
}
