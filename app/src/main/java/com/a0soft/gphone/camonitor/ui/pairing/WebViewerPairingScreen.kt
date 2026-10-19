package com.a0soft.gphone.camonitor.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.webrtc.TurnSettings
import com.a0soft.gphone.camonitor.service.SmartRecordingService
import com.a0soft.gphone.camonitor.ui.common.QrUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val WEB_VIEWER_URL = "https://cocoaswifty.github.io/CamMonitor/web-viewer/"

/**
 * Share a 5-minute, one-use browser pairing invitation. The key material is
 * carried in the URL fragment, never in the HTTP request to GitHub Pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewerPairingScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { CamMonitorApp.instance }
    val repo = app.securityRepository
    var otp by remember { mutableStateOf(repo.generatePairingOtp()) }
    var seconds by remember { mutableIntStateOf(300) }
    var settings by remember {
        mutableStateOf(runCatching { TurnSettings.load(context) }.getOrDefault(TurnSettings()))
    }
    var turnDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val invite = remember(otp, settings) {
        val payload = JSONObject().apply {
            put("deviceId", repo.getDeviceId())
            put("name", repo.getDeviceName())
            put("secret", otp)
            if (settings.isConfigured()) {
                put("turn", JSONObject().apply {
                    put("urls", settings.uri)
                    put("username", settings.username)
                    put("credential", settings.credential)
                })
            }
        }
        val encoded = Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        WEB_VIEWER_URL + "#pair=" + encoded
    }
    val qr = remember(invite) { QrUtils.generateQrBitmap(invite, 640) }

    DisposableEffect(Unit) {
        onDispose { repo.clearPairingOtp() }
    }

    LaunchedEffect(otp) {
        seconds = 300
        while (seconds > 0) {
            delay(1000L)
            seconds--
        }
        otp = repo.generatePairingOtp()
    }

    LaunchedEffect(Unit) {
        // A previously stopped CameraMonitorService may race with opening this
        // screen and disconnect MQTT. Keep pairing listener available.
        launch {
            while (isActive) {
                if (!app.signalingClient.isConnectedFlow.value) {
                    app.signalingClient.startListening()
                }
                delay(4000L)
            }
        }
        app.signalingClient.startListening()
        app.signalingClient.incomingMessages.collect { (sender, message) ->
            if (message !is SignalingMessage.PairRequest) return@collect
            if (repo.getActivePairingOtp() == null) return@collect
            if (sender != message.pbDeviceId) return@collect
            if (repo.getPairedViewers().size >= SecurityRepository.MAX_PAIRED_VIEWERS &&
                repo.getPairedDevice(sender) == null) {
                error = context.getString(R.string.web_pairing_full)
                return@collect
            }
            if (message.pbPublicKeyBase64.length > 4096 ||
                message.pbName.length > 120 ||
                runCatching { CryptoUtils.publicKeyFromBase64(message.pbPublicKeyBase64) }.isFailure
            ) return@collect

            val key = CryptoUtils.hkdfSha256(
                otp.toByteArray(Charsets.UTF_8), byteArrayOf(),
                "CamMonitor-OTP".toByteArray(Charsets.UTF_8), 32
            )
            // Send confirmation before navigating away. Browser verifies the
            // reply using the same pairing OTP and signed camera public key.
            val added = repo.addPairedDevice(
                PairedDevice(
                    deviceId = sender,
                    name = message.pbName,
                    publicKeyBase64 = message.pbPublicKeyBase64,
                    pairedTimestamp = System.currentTimeMillis(),
                    role = "VIEWER"
                ),
                maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS
            )
            if (added) {
                val reply = SignalingMessage.PairConfirm(
                    paDeviceId = repo.getDeviceId(),
                    paName = repo.getDeviceName(),
                    paPublicKeyBase64 = repo.getDevicePublicKeyBase64()
                )
                val sent = app.signalingClient.sendSignalingMessage(sender, reply, key)
                if (sent) {
                    delay(600)
                    onPaired()
                } else {
                    repo.removePairedDevice(sender)
                    error = context.getString(R.string.web_pairing_send_failed)
                }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.web_pairing_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.web_pairing_hint))
            Text(stringResource(R.string.web_pairing_expiry, seconds / 60, seconds % 60))
            Surface(color = Color.White, modifier = Modifier.size(290.dp)) {
                if (qr != null) {
                    Image(bitmap = qr, contentDescription = stringResource(R.string.web_pairing_qr))
                } else {
                    CircularProgressIndicator()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("CamMonitor", invite))
                }) { Text(stringResource(R.string.web_pairing_copy)) }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, invite)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.web_pairing_share)) }
            }
            Text(stringResource(R.string.web_pairing_private_notice),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { turnDialog = true }) {
                Text(stringResource(R.string.web_pairing_turn_settings))
            }
            if (settings.isConfigured()) {
                Text(stringResource(R.string.web_pairing_turn_enabled),
                    color = MaterialTheme.colorScheme.primary)
            } else {
                Text(stringResource(R.string.web_pairing_turn_optional),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.web_pairing_pages_setup),
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (turnDialog) {
        var url by remember { mutableStateOf(settings.uri) }
        var user by remember { mutableStateOf(settings.username) }
        var pass by remember { mutableStateOf(settings.credential) }
        AlertDialog(
            onDismissRequest = { turnDialog = false },
            title = { Text(stringResource(R.string.web_pairing_turn_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text("turn:host:3478") })
                    OutlinedTextField(user, { user = it }, label = { Text("TURN username") })
                    OutlinedTextField(pass, { pass = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("TURN credential") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val proposed = TurnSettings(url.trim(), user.trim(), pass)
                    runCatching {
                        TurnSettings.save(context, proposed)
                        settings = proposed
                        turnDialog = false
                    }.onFailure { error = it.message }
                }) { Text(stringResource(R.string.web_pairing_save)) }
            },
            dismissButton = {
                TextButton(onClick = { turnDialog = false }) {
                    Text(stringResource(R.string.action_cancel_pairing))
                }
            }
        )
    }
}
