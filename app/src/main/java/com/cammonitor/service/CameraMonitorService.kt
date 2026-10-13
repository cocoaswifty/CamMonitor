package com.cammonitor.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cammonitor.CamMonitorApp
import com.cammonitor.R
import com.cammonitor.data.model.ControlCommand
import com.cammonitor.data.model.SignalingMessage
import com.cammonitor.log.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

enum class CameraStreamingState {
    IDLE,
    WAITING_FOR_VIEWERS,
    STREAMING,
    PAUSED_INACTIVITY // Paused after 60s of 0 viewers
}

class CameraMonitorService : Service() {
    private val tag = "CameraMonitorService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    private val _streamingState = MutableStateFlow(CameraStreamingState.IDLE)
    val streamingState: StateFlow<CameraStreamingState> = _streamingState.asStateFlow()

    private var inactivityJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): CameraMonitorService = this@CameraMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(tag, "CameraMonitorService onStartCommand")
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "CameraMonitorService created")
        acquireWakeLock()
        startForegroundServiceNotification()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamMonitor:StreamingWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours max safety
        }
        AppLogger.d(tag, "Partial WakeLock acquired")
    }

    private fun startForegroundServiceNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CamMonitorApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(1001, notification, type)
        } else {
            startForeground(1001, notification)
        }
    }

    fun startStreaming() {
        val app = CamMonitorApp.instance
        _streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
        app.webRtcManager.startCameraCapture()

        // Start listening to signaling
        serviceScope.launch {
            app.signalingClient.startListening()
            observeSignaling(app)
        }

        // Listen to DataChannel commands from viewers
        serviceScope.launch {
            app.webRtcManager.dataChannelCommands.collect { (peerId, cmd) ->
                handleViewerCommand(cmd)
            }
        }

        // Track active viewers count for 60s timeout
        serviceScope.launch {
            app.webRtcManager.activeViewersCount.collect { count ->
                if (count > 0) {
                    _streamingState.value = CameraStreamingState.STREAMING
                    cancelInactivityTimer()
                } else if (_streamingState.value == CameraStreamingState.STREAMING ||
                    _streamingState.value == CameraStreamingState.WAITING_FOR_VIEWERS) {
                    _streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
                    startInactivityTimer()
                }
            }
        }

        startInactivityTimer()
    }

    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = serviceScope.launch {
            AppLogger.i(tag, "Inactivity timer started: 60s countdown until camera pause")
            delay(60_000L) // 1 minute
            AppLogger.i(tag, "0 viewers for 60s: Stopping camera and pausing")
            CamMonitorApp.instance.webRtcManager.stopCameraCapture()
            _streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
        }
    }

    private fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    fun resumeStreamingFromPause() {
        AppLogger.i(tag, "Resuming camera streaming from pause state")
        CamMonitorApp.instance.webRtcManager.startCameraCapture()
        _streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
        startInactivityTimer()
    }

    private suspend fun observeSignaling(app: CamMonitorApp) {
        app.signalingClient.incomingMessages.collect { (senderId, message) ->
            if (!app.securityRepository.isDeviceWhitelisted(senderId)) {
                AppLogger.w(tag, "Ignoring signaling from non-whitelisted sender: $senderId")
                return@collect
            }

            when (message) {
                is SignalingMessage.Offer -> handleRemoteOffer(senderId, message.sdp)
                is SignalingMessage.IceCandidate -> {
                    val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.sdp)
                    app.webRtcManager.addRemoteIceCandidate(senderId, candidate)
                }
                else -> {}
            }
        }
    }

    private fun handleRemoteOffer(senderId: String, sdpStr: String) {
        val app = CamMonitorApp.instance
        // Cap max 2 viewers
        if (app.webRtcManager.activeViewersCount.value >= 2) {
            AppLogger.w(tag, "Rejecting connection from $senderId: Max 2 viewers reached")
            sendSignalingMessageToViewer(senderId, SignalingMessage.Reject("MAX_CAPACITY_REACHED"))
            return
        }

        val pc = app.webRtcManager.createPeerConnection(senderId) { candidate ->
            val iceMsg = SignalingMessage.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            sendSignalingMessageToViewer(senderId, iceMsg)
        } ?: return

        app.webRtcManager.createDataChannelForSender(senderId)

        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                AppLogger.i(tag, "Remote offer set successfully for $senderId. Creating answer...")
                val constraints = MediaConstraints()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                AppLogger.i(tag, "Local answer set and sending to $senderId")
                                val answerMsg = SignalingMessage.Answer(answerDesc.description)
                                sendSignalingMessageToViewer(senderId, answerMsg)
                            }
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onSetFailure(err: String?) { AppLogger.e(tag, "Failed setting local answer: $err") }
                            override fun onCreateFailure(err: String?) {}
                        }, answerDesc)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) { AppLogger.e(tag, "Failed creating answer: $err") }
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetFailure(err: String?) { AppLogger.e(tag, "Failed setting remote offer: $err") }
            override fun onCreateFailure(err: String?) {}
        }, offerDesc)
    }

    private fun sendSignalingMessageToViewer(viewerId: String, msg: SignalingMessage) {
        val app = CamMonitorApp.instance
        val viewer = app.securityRepository.getPairedDevice(viewerId) ?: return
        val remotePubKey = com.cammonitor.data.security.CryptoUtils.publicKeyFromBase64(viewer.publicKeyBase64)
        val key = com.cammonitor.data.security.CryptoUtils.deriveEcdhKey(app.securityRepository.getKeyPair().private, remotePubKey)

        serviceScope.launch {
            app.signalingClient.sendSignalingMessage(viewerId, msg, key)
        }
    }

    private fun handleViewerCommand(cmd: ControlCommand) {
        val app = CamMonitorApp.instance
        when (cmd) {
            is ControlCommand.Torch -> {
                app.torchManager.setTorch(cmd.enabled)
            }
            is ControlCommand.PttStart -> {
                AppLogger.i(tag, "Viewer started Push-to-Talk")
            }
            is ControlCommand.PttStop -> {
                AppLogger.i(tag, "Viewer stopped Push-to-Talk")
            }
            is ControlCommand.Rename -> {
                // Not handled by sender
            }
        }
    }

    fun broadcastRename(newName: String) {
        CamMonitorApp.instance.webRtcManager.broadcastDataChannelCommand(ControlCommand.Rename(newName))
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(tag, "CameraMonitorService destroyed")
        cancelInactivityTimer()
        serviceScope.cancel()
        CamMonitorApp.instance.webRtcManager.closeAll()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}
