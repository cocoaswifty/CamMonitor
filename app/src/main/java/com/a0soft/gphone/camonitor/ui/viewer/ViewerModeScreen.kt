package com.a0soft.gphone.camonitor.ui.viewer

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.theme.StatusLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*

@Composable
fun ViewerModeScreen(
    onExitViewerMode: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient
    val webRtcManager = app.webRtcManager

    val pairedDevices = remember { securityRepo.getPairedDevices() }
    val targetCamera = pairedDevices.firstOrNull()

    var cameraName by remember { mutableStateOf(targetCamera?.name ?: "Camera") }
    var connectionStatus by remember { mutableStateOf("Connecting to camera...") }
    var rotationDegrees by remember { mutableStateOf(0f) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isAudioEnabled by remember { mutableStateOf(securityRepo.isViewerAudioPlaybackEnabled()) }
    var isTorchActive by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    var hasSymmetricNatError by remember { mutableStateOf(false) }

    var surfaceRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Sync audio playback enabled state to WebRTC manager
    LaunchedEffect(isAudioEnabled) {
        webRtcManager.setRemoteAudioEnabled(isAudioEnabled)
    }

    // Auto-hide controls timer
    LaunchedEffect(isControlsVisible) {
        if (isControlsVisible) {
            delay(5000L)
            isControlsVisible = false
        }
    }

    // Connect to camera on launch
    LaunchedEffect(targetCamera) {
        if (targetCamera == null) {
            connectionStatus = "No paired camera found"
            AppLogger.w("ViewerMode", "No paired camera found in repository")
            return@LaunchedEffect
        }

        val startTime = System.currentTimeMillis()
        AppLogger.i("ViewerMode", "Starting WebRTC connection to camera: ${targetCamera.name} (${targetCamera.deviceId})")
        val isMqttListening = signalingClient.startListening()
        AppLogger.i("ViewerMode", "Signaling client startListening completed (connected=$isMqttListening)")

        val remotePubKey = CryptoUtils.publicKeyFromBase64(targetCamera.publicKeyBase64)
        val sharedKey = CryptoUtils.deriveEcdhKey(securityRepo.getKeyPair().private, remotePubKey)

        val pc = webRtcManager.createPeerConnection(targetCamera.deviceId) { candidate ->
            val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
            AppLogger.d("ViewerMode", "Forwarding local ICE candidate to camera: mid=${candidate.sdpMid}, type=$typeMatch")
            val iceMsg = SignalingMessage.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            coroutineScope.launch {
                val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                AppLogger.d("ViewerMode", "Sent ICE candidate to camera (success=$sent, mid=${candidate.sdpMid})")
            }
        } ?: run {
            AppLogger.e("ViewerMode", "Failed creating PeerConnection for ${targetCamera.deviceId}")
            connectionStatus = "Initialization Failed"
            return@LaunchedEffect
        }

        // Create WebRTC Offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        var localOfferDesc: SessionDescription? = null

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                val elapsed = System.currentTimeMillis() - startTime
                AppLogger.i("ViewerMode", "Local Offer created (+${elapsed}ms, SDP size=${desc.description.length} chars). Setting local description...")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val setElapsed = System.currentTimeMillis() - startTime
                        AppLogger.i("ViewerMode", "Local Offer set successfully (+${setElapsed}ms), transmitting to camera: ${targetCamera.deviceId}")
                        localOfferDesc = desc
                        val offerMsg = SignalingMessage.Offer(desc.description)
                        coroutineScope.launch {
                            val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                            AppLogger.i("ViewerMode", "Transmitted initial Offer to camera (success=$sent)")
                        }
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetFailure(err: String?) { AppLogger.e("ViewerMode", "Failed setting local offer: $err") }
                    override fun onCreateFailure(err: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { AppLogger.e("ViewerMode", "Failed creating offer: $err") }
            override fun onSetFailure(err: String?) {}
        }, constraints)

        // Periodic Offer retry while waiting for camera to come online / answer
        val retryJob = coroutineScope.launch {
            var attempt = 1
            while (isActive && pc.remoteDescription == null) {
                delay(3000L)
                val offer = localOfferDesc
                if (pc.remoteDescription == null && offer != null) {
                    attempt++
                    val elapsed = System.currentTimeMillis() - startTime
                    AppLogger.i("ViewerMode", "Waiting for camera answer (+${elapsed}ms); re-transmitting Offer (attempt #$attempt)...")
                    val offerMsg = SignalingMessage.Offer(offer.description)
                    val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                    AppLogger.i("ViewerMode", "Re-transmitted Offer (attempt #$attempt, sent=$sent)")
                    if (attempt >= 15 && pc.connectionState() == PeerConnection.PeerConnectionState.NEW) {
                        connectionStatus = "Waiting for camera to respond..."
                    }
                }
            }
        }

        // Observe incoming messages
        signalingClient.incomingMessages.collect { (senderId, msg) ->
            if (senderId == targetCamera.deviceId) {
                when (msg) {
                    is SignalingMessage.Answer -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.i("ViewerMode", "Received remote Answer from camera (+${elapsed}ms, SDP size=${msg.sdp.length} chars). Applying to PeerConnection...")
                        retryJob.cancel()
                        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                        pc.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val setElapsed = System.currentTimeMillis() - startTime
                                AppLogger.i("ViewerMode", "Remote Answer applied successfully (+${setElapsed}ms). Draining buffered candidates...")
                                webRtcManager.drainRemoteIceCandidates(targetCamera.deviceId)
                                connectionStatus = "Live (720p 20fps)"
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetFailure(err: String?) { AppLogger.e("ViewerMode", "Failed setting remote answer: $err") }
                            override fun onCreateFailure(err: String?) {}
                        }, answerDesc)
                    }
                    is SignalingMessage.IceCandidate -> {
                        val typeMatch = Regex("typ\\s+(\\w+)").find(msg.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
                        AppLogger.d("ViewerMode", "Received remote ICE candidate from camera: mid=${msg.sdpMid}, index=${msg.sdpMLineIndex}, type=$typeMatch")
                        val candidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.sdp)
                        webRtcManager.addRemoteIceCandidate(targetCamera.deviceId, candidate)
                    }
                    is SignalingMessage.Reject -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.w("ViewerMode", "Camera rejected connection (+${elapsed}ms): ${msg.reason}")
                        retryJob.cancel()
                        connectionStatus = "Camera Rejected: ${msg.reason}"
                    }
                    else -> {}
                }
            }
        }
    }

    // Observe DataChannel commands from PA (e.g. RENAME)
    LaunchedEffect(Unit) {
        webRtcManager.dataChannelCommands.collect { (peerId, cmd) ->
            if (cmd is ControlCommand.Rename) {
                cameraName = cmd.name
                // Update local storage
                if (targetCamera != null) {
                    securityRepo.addPairedDevice(targetCamera.copy(name = cmd.name))
                }
            }
        }
    }

    // Observe WebRTC connection state
    LaunchedEffect(Unit) {
        webRtcManager.connectionState.collect { state ->
            AppLogger.i("ViewerMode", "WebRTC PeerConnectionState changed to: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> connectionStatus = "Live (720p 20fps)"
                PeerConnection.PeerConnectionState.CONNECTING -> connectionStatus = "Connecting to camera..."
                PeerConnection.PeerConnectionState.FAILED -> {
                    connectionStatus = "Connection Failed"
                    hasSymmetricNatError = true
                    AppLogger.e("ViewerMode", "WebRTC connection failed (potential symmetric NAT traversal failure)")
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    connectionStatus = "Disconnected"
                    AppLogger.w("ViewerMode", "WebRTC connection disconnected")
                }
                else -> {}
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible }
                )
            }
    ) {
        // Video Viewport with rotation support
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    init(webRtcManager.eglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    surfaceRenderer = this
                    webRtcManager.attachRemoteRenderer(this)
                }
            },
            onRelease = { renderer ->
                webRtcManager.detachRemoteRenderer(renderer)
                renderer.release()
                surfaceRenderer = null
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotationDegrees }
        )

        // Overlay Controls
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Overlay
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = {
                            webRtcManager.closeAll()
                            onExitViewerMode()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit Viewer")
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = cameraName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (connectionStatus.startsWith("Live")) StatusLive else MaterialTheme.colorScheme.error, CircleShape)
                                )
                                Text(
                                    text = connectionStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (connectionStatus.startsWith("Live")) StatusLive else MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        // Torch Button
                        IconButton(onClick = {
                            if (targetCamera != null) {
                                isTorchActive = !isTorchActive
                                webRtcManager.sendDataChannelCommand(
                                    targetCamera.deviceId,
                                    ControlCommand.Torch(isTorchActive)
                                )
                            }
                        }) {
                            Icon(
                                imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Torch",
                                tint = if (isTorchActive) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
                }

                // Bottom Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Rotate 90 Degrees Button
                    FilledTonalIconButton(
                        onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f }
                    ) {
                        Icon(Icons.Default.Rotate90DegreesCw, contentDescription = "Rotate")
                    }

                    // Push-to-Talk Button
                    Surface(
                        shape = CircleShape,
                        color = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .height(56.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.any { it.pressed }) {
                                            if (!isPttPressed) {
                                                isPttPressed = true
                                                targetCamera?.let {
                                                    webRtcManager.sendDataChannelCommand(it.deviceId, ControlCommand.PttStart)
                                                }
                                            }
                                        } else {
                                            if (isPttPressed) {
                                                isPttPressed = false
                                                targetCamera?.let {
                                                    webRtcManager.sendDataChannelCommand(it.deviceId, ControlCommand.PttStop)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Text(
                                text = if (isPttPressed) "Transmitting..." else "Hold to Talk",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Audio Playback Button (Mute/Unmute)
                    FilledTonalIconToggleButton(
                        checked = isAudioEnabled,
                        onCheckedChange = { enabled ->
                            isAudioEnabled = enabled
                            securityRepo.setViewerAudioPlaybackEnabled(enabled)
                            webRtcManager.setRemoteAudioEnabled(enabled)
                        }
                    ) {
                        Icon(
                            imageVector = if (isAudioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (isAudioEnabled) "Audio Playback On" else "Audio Playback Off"
                        )
                    }
                }
            }
        }

        // Symmetric NAT Error Dialog
        if (hasSymmetricNatError) {
            AlertDialog(
                onDismissRequest = { hasSymmetricNatError = false },
                title = { Text("Connection Failed") },
                text = {
                    Text("Direct P2P traversal failed due to Carrier Symmetric NAT. Please connect devices to Wi-Fi or the same local network.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        hasSymmetricNatError = false
                        onExitViewerMode()
                    }) {
                        Text("Exit")
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            surfaceRenderer?.let { webRtcManager.detachRemoteRenderer(it) }
            surfaceRenderer?.release()
        }
    }
}
