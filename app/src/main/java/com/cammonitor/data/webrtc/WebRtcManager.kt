package com.cammonitor.data.webrtc

import android.content.Context
import com.cammonitor.data.model.ControlCommand
import com.cammonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcManager(private val context: Context) {
    private val tag = "WebRtcManager"

    val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null

    // Sender state (PA)
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localRenderer: SurfaceViewRenderer? = null

    // Active PeerConnections mapped by peerId (max 2 for PA)
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val dataChannels = mutableMapOf<String, DataChannel>()

    // Flows
    private val _incomingIceCandidates = MutableSharedFlow<Pair<String, IceCandidate>>(extraBufferCapacity = 32)
    val incomingIceCandidates: SharedFlow<Pair<String, IceCandidate>> = _incomingIceCandidates.asSharedFlow()

    private val _dataChannelCommands = MutableSharedFlow<Pair<String, ControlCommand>>(extraBufferCapacity = 32)
    val dataChannelCommands: SharedFlow<Pair<String, ControlCommand>> = _dataChannelCommands.asSharedFlow()

    private val _connectionState = MutableStateFlow<PeerConnection.PeerConnectionState>(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    private val _activeViewersCount = MutableStateFlow(0)
    val activeViewersCount: StateFlow<Int> = _activeViewersCount.asStateFlow()

    // Receiver state (PB)
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var isRemoteAudioEnabled = false
    private var remoteRenderer: SurfaceViewRenderer? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        AppLogger.i(tag, "WebRTC PeerConnectionFactory initialized")
    }

    // ----------------------------------------------------
    // SENDER (PA) SETUP
    // ----------------------------------------------------

    fun startCameraCapture(width: Int = 1280, height: Int = 720, fps: Int = 20) {
        val factory = peerConnectionFactory ?: return
        if (localVideoTrack != null) return // Already running

        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val backCameraName = deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: deviceNames.firstOrNull()

        if (backCameraName == null) {
            AppLogger.e(tag, "No camera found on device")
            return
        }

        val capturer = enumerator.createCapturer(backCameraName, null)
        videoCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val vSource = factory.createVideoSource(capturer.isScreencast)
        videoSource = vSource

        capturer.initialize(surfaceTextureHelper, context, vSource.capturerObserver)
        capturer.startCapture(width, height, fps)

        val track = factory.createVideoTrack("CAM_VIDEO_TRACK", vSource).apply {
            setEnabled(true)
        }
        localVideoTrack = track
        localRenderer?.let { track.addSink(it) }

        // Setup audio
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints)
        audioSource = aSource
        localAudioTrack = factory.createAudioTrack("CAM_AUDIO_TRACK", aSource).apply {
            setEnabled(true)
        }

        AppLogger.i(tag, "Camera capture started: ${width}x${height}@${fps}fps (back camera)")
    }

    fun stopCameraCapture() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localRenderer?.let { localVideoTrack?.removeSink(it) }
            localVideoTrack?.dispose()
            localVideoTrack = null
            videoSource?.dispose()
            videoSource = null

            localAudioTrack?.dispose()
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null

            AppLogger.i(tag, "Camera capture stopped and hardware released")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error stopping camera capture", e)
        }
    }

    // ----------------------------------------------------
    // PEER CONNECTION MANAGEMENT
    // ----------------------------------------------------

    fun createPeerConnection(peerId: String, onIceCandidateGenerated: (IceCandidate) -> Unit): PeerConnection? {
        val factory = peerConnectionFactory ?: run {
            AppLogger.e(tag, "Cannot create PeerConnection: PeerConnectionFactory is null")
            return null
        }
        closePeer(peerId)
        AppLogger.i(tag, "Creating new PeerConnection for $peerId with ${iceServers.size} ICE servers")

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
                AppLogger.i(tag, "Local ICE Candidate generated for $peerId: type=$typeMatch, mid=${candidate.sdpMid}, index=${candidate.sdpMLineIndex}")
                onIceCandidateGenerated(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                AppLogger.i(tag, "IceCandidates removed for $peerId: count=${candidates?.size ?: 0}")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                AppLogger.i(tag, "SignalingState for $peerId: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLogger.i(tag, "IceConnectionState for $peerId: $state")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                AppLogger.i(tag, "ConnectionChange for $peerId: $newState")
                _connectionState.value = newState
                updateActiveViewersCount()
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                AppLogger.i(tag, "IceGatheringState for $peerId: $state")
            }

            override fun onAddStream(stream: MediaStream?) {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                    AppLogger.i(tag, "Remote VideoTrack received and attached to renderer")
                } else if (track is AudioTrack) {
                    remoteAudioTrack = track
                    track.setEnabled(isRemoteAudioEnabled)
                    AppLogger.i(tag, "Remote AudioTrack received for viewer mode (enabled=$isRemoteAudioEnabled)")
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel) {
                AppLogger.i(tag, "DataChannel received from $peerId: label=${dataChannel.label()}, id=${dataChannel.id()}")
                setupDataChannel(peerId, dataChannel)
            }

            override fun onRenegotiationNeeded() {
                AppLogger.d(tag, "Renegotiation needed for $peerId")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                AppLogger.i(tag, "IceConnection receiving for $peerId: receiving=$receiving")
            }
        })

        if (pc != null) {
            // Attach local tracks if streaming as sender (PA)
            localVideoTrack?.let { pc.addTrack(it, listOf("CAM_STREAM")) }
            localAudioTrack?.let { pc.addTrack(it, listOf("CAM_STREAM")) }

            peerConnections[peerId] = pc
            updateActiveViewersCount()
        }

        return pc
    }

    private fun setupDataChannel(peerId: String, dc: DataChannel) {
        dataChannels[peerId] = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                AppLogger.i(tag, "DataChannel state for $peerId: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val jsonStr = String(data, Charsets.UTF_8)
                val cmd = ControlCommand.fromJson(jsonStr)
                if (cmd != null) {
                    AppLogger.i(tag, "Received DataChannel command from $peerId: $jsonStr")
                    _dataChannelCommands.tryEmit(Pair(peerId, cmd))
                }
            }
        })
    }

    fun createDataChannelForSender(peerId: String): DataChannel? {
        val pc = peerConnections[peerId] ?: return null
        val init = DataChannel.Init().apply {
            ordered = true
        }
        val dc = pc.createDataChannel("control", init)
        if (dc != null) {
            setupDataChannel(peerId, dc)
        }
        return dc
    }

    fun sendDataChannelCommand(peerId: String, cmd: ControlCommand): Boolean {
        val dc = dataChannels[peerId] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        return dc.send(buffer)
    }

    fun broadcastDataChannelCommand(cmd: ControlCommand) {
        dataChannels.keys.forEach { peerId ->
            sendDataChannelCommand(peerId, cmd)
        }
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        if (localRenderer == renderer) {
            localRenderer = null
        }
        localVideoTrack?.removeSink(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (remoteRenderer == renderer) {
            remoteRenderer = null
        }
        remoteVideoTrack?.removeSink(renderer)
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        isRemoteAudioEnabled = enabled
        remoteAudioTrack?.setEnabled(enabled)
        AppLogger.i(tag, "Remote audio track enabled: $enabled")
    }

    private val pendingRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableList<IceCandidate>>()

    fun addRemoteIceCandidate(peerId: String, candidate: IceCandidate) {
        val pc = peerConnections[peerId]
        val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
        if (pc != null && pc.remoteDescription != null) {
            val added = pc.addIceCandidate(candidate)
            AppLogger.i(tag, "Added remote ICE candidate directly for $peerId: type=$typeMatch, mid=${candidate.sdpMid}, added=$added")
        } else {
            val list = pendingRemoteIceCandidates.getOrPut(peerId) { mutableListOf() }
            list.add(candidate)
            AppLogger.i(tag, "Buffered remote ICE candidate for $peerId: type=$typeMatch, mid=${candidate.sdpMid} (pending count=${list.size}, pc=${if (pc != null) "exists(waiting remoteDesc)" else "null"})")
        }
    }

    fun drainRemoteIceCandidates(peerId: String) {
        val pc = peerConnections[peerId] ?: run {
            AppLogger.w(tag, "Cannot drain ICE candidates: PeerConnection not found for $peerId")
            return
        }
        val pending = pendingRemoteIceCandidates.remove(peerId)
        if (pending.isNullOrEmpty()) {
            AppLogger.i(tag, "No buffered ICE candidates to drain for $peerId")
            return
        }
        AppLogger.i(tag, "Draining ${pending.size} buffered ICE candidates for $peerId into PeerConnection")
        pending.forEach { cand ->
            val added = pc.addIceCandidate(cand)
            AppLogger.d(tag, "Drained candidate mid=${cand.sdpMid} (added=$added)")
        }
    }

    private fun updateActiveViewersCount() {
        val count = peerConnections.values.count {
            it.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
        }
        _activeViewersCount.value = count
    }

    fun closePeer(peerId: String) {
        pendingRemoteIceCandidates.remove(peerId)
        dataChannels.remove(peerId)?.dispose()
        peerConnections.remove(peerId)?.dispose()
        updateActiveViewersCount()
        AppLogger.i(tag, "Closed WebRTC connection with $peerId")
    }

    fun closeAll() {
        stopCameraCapture()
        dataChannels.values.forEach { it.dispose() }
        dataChannels.clear()
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        _activeViewersCount.value = 0
        localRenderer = null
        remoteRenderer = null
        remoteVideoTrack = null
        remoteAudioTrack = null
        isRemoteAudioEnabled = false
        AppLogger.i(tag, "All WebRTC connections closed")
    }
}
