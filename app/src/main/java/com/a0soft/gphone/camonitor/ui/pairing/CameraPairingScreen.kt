package com.a0soft.gphone.camonitor.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.PairingPayload
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.QrUtils
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPairingScreen(
    onPairingSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient

    var otp by remember { mutableStateOf(securityRepo.generatePairingOtp()) }
    var secondsLeft by remember { mutableStateOf(300) } // 5 minutes

    val cameraName = remember { securityRepo.getDeviceName() }
    val deviceId = remember { securityRepo.getDeviceId() }

    val qrBitmap = remember(otp) {
        val payload = PairingPayload(
            deviceId = deviceId,
            name = cameraName,
            secret = otp
        )
        QrUtils.generateQrBitmap(payload.toJson(), 512)
    }

    DisposableEffect(Unit) {
        onDispose {
            securityRepo.clearPairingOtp()
        }
    }

    // OTP 5-minute countdown timer
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        // Regenerate OTP if expired
        otp = securityRepo.generatePairingOtp()
        secondsLeft = 300
    }

    // Listen for incoming PAIR_REQUEST over MQTT
    LaunchedEffect(Unit) {
        signalingClient.startListening()
        signalingClient.incomingMessages.collect { (senderId, msg) ->
            if (msg is SignalingMessage.PairRequest) {
                AppLogger.i("CameraPairing", "Received PAIR_REQUEST from ${msg.pbName} ($senderId)")
                val newViewer = PairedDevice(
                    deviceId = msg.pbDeviceId,
                    name = msg.pbName,
                    publicKeyBase64 = msg.pbPublicKeyBase64,
                    pairedTimestamp = System.currentTimeMillis(),
                    role = "VIEWER"
                )
                val added = securityRepo.addPairedDevice(newViewer, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS)
                if (added) {
                    // Send PAIR_CONFIRM
                    val confirm = SignalingMessage.PairConfirm(
                        paDeviceId = deviceId,
                        paName = cameraName,
                        paPublicKeyBase64 = securityRepo.getDevicePublicKeyBase64()
                    )
                    val otpKey = CryptoUtils.hkdfSha256(
                        otp.toByteArray(Charsets.UTF_8),
                        ByteArray(0),
                        "CamMonitor-OTP".toByteArray(),
                        32
                    )
                    val sent = signalingClient.sendSignalingMessage(msg.pbDeviceId, confirm, otpKey)
                    if (sent) {
                        AppLogger.i("CameraPairing", "PAIR_CONFIRM transmitted successfully to ${msg.pbDeviceId}")
                    }
                    delay(500L)
                    onPairingSuccess()
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Pair New Viewer") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Broadcasting as: $cameraName",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // High-contrast QR card
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.size(280.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Pairing QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Timer Pill
            val minutes = secondsLeft / 60
            val seconds = secondsLeft % 60
            SuggestionChip(
                onClick = {},
                label = { Text("OTP expires in %02d:%02d".format(minutes, seconds)) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    labelColor = if (secondsLeft < 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            val currentCount = securityRepo.getPairedViewers().size
            Text(
                text = "Slot ${currentCount + 1} of 2 Available",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Open CamMonitor on Device B, tap 'Start Viewer Mode' and scan this code.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    securityRepo.clearPairingOtp()
                    onCancel()
                }
            ) {
                Text("Cancel Pairing")
            }
        }
    }
}
