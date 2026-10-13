package com.cammonitor.data.signaling

import com.cammonitor.data.model.SignalingEnvelope
import com.cammonitor.data.model.SignalingMessage
import com.cammonitor.data.security.SecurityRepository
import com.cammonitor.log.AppLogger
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume

interface SignalingClient {
    val incomingMessages: SharedFlow<Pair<String, SignalingMessage>>
    val isConnectedFlow: StateFlow<Boolean>
    suspend fun startListening(): Boolean
    suspend fun stopListening()
    suspend fun sendEnvelope(envelope: SignalingEnvelope): Boolean
    suspend fun sendSignalingMessage(
        recipientId: String,
        message: SignalingMessage,
        secretOrDerivedKey: ByteArray
    ): Boolean
}

class MqttSignalingClient(
    private val securityRepository: SecurityRepository
) : SignalingClient {

    private val tag = "MqttSignalingClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = MutableSharedFlow<Pair<String, SignalingMessage>>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<Pair<String, SignalingMessage>> = _incomingMessages.asSharedFlow()

    private val _isConnectedFlow = MutableStateFlow(false)
    override val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private var client: Mqtt3AsyncClient? = null
    private val connectMutex = kotlinx.coroutines.sync.Mutex()

    data class BrokerEndpoint(val host: String, val port: Int, val useSsl: Boolean)

    private val brokerEndpoints = listOf(
        BrokerEndpoint("broker.hivemq.com", 1883, false),
        BrokerEndpoint("broker.emqx.io", 1883, false),
        BrokerEndpoint("broker.hivemq.com", 8883, true)
    )

    override suspend fun startListening(): Boolean {
        if (_isConnectedFlow.value) {
            AppLogger.d(tag, "Already connected and listening")
            return true
        }

        val deviceId = securityRepository.getDeviceId()
        val topic = "cammonitor/$deviceId/rx"

        AppLogger.i(tag, "Connecting to MQTT signaling for device: $deviceId, topic: $topic")
        return connectWithRetry(topic)
    }

    private suspend fun connectWithRetry(topic: String): Boolean = connectMutex.withLock {
        if (_isConnectedFlow.value) return@withLock true

        for (endpoint in brokerEndpoints) {
            AppLogger.i(tag, "Attempting connection to ${endpoint.host}:${endpoint.port} (ssl=${endpoint.useSsl})...")
            val success = try {
                withTimeoutOrNull(4_000L) {
                    attemptConnect(endpoint, topic)
                } ?: false
            } catch (e: Exception) {
                AppLogger.w(tag, "Connection error for ${endpoint.host}:${endpoint.port}: ${e.message}")
                false
            }

            if (success) {
                AppLogger.i(tag, "Successfully connected to ${endpoint.host}:${endpoint.port}")
                return@withLock true
            } else {
                AppLogger.w(tag, "Failed or timed out on ${endpoint.host}:${endpoint.port}, trying fallback...")
            }
        }

        _isConnectedFlow.value = false
        return@withLock false
    }

    private suspend fun attemptConnect(endpoint: BrokerEndpoint, topic: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val clientId = "cammonitor-" + UUID.randomUUID().toString().take(8)

            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(endpoint.host)
                .serverPort(endpoint.port)

            val newClient = if (endpoint.useSsl) {
                builder.sslWithDefaultConfig().buildAsync()
            } else {
                builder.buildAsync()
            }

            continuation.invokeOnCancellation {
                try {
                    newClient.disconnect()
                } catch (_: Exception) {}
            }

            try {
                newClient.connectWith()
                    .send()
                    .whenComplete { _, connectError ->
                        if (connectError != null) {
                            AppLogger.w(tag, "Failed connecting to ${endpoint.host}:${endpoint.port}: ${connectError.message}")
                            try { newClient.disconnect() } catch (_: Exception) {}
                            if (continuation.isActive) continuation.resume(false)
                        } else {
                            AppLogger.i(tag, "Connected to ${endpoint.host}:${endpoint.port}. Subscribing to $topic...")
                            newClient.subscribeWith()
                                .topicFilter(topic)
                                .qos(MqttQos.AT_LEAST_ONCE)
                                .callback { publish ->
                                    val payloadStr = String(publish.payloadAsBytes, Charsets.UTF_8)
                                    handleIncomingRawPayload(payloadStr)
                                }
                                .send()
                                .whenComplete { _, subError ->
                                    if (subError != null) {
                                        AppLogger.e(tag, "Subscription failed on $topic", subError)
                                        try { newClient.disconnect() } catch (_: Exception) {}
                                        if (continuation.isActive) continuation.resume(false)
                                    } else {
                                        AppLogger.i(tag, "Subscribed successfully to $topic")
                                        client = newClient
                                        _isConnectedFlow.value = true
                                        if (continuation.isActive) continuation.resume(true)
                                    }
                                }
                        }
                    }
            } catch (e: Exception) {
                AppLogger.e(tag, "Exception during connection attempt to ${endpoint.host}", e)
                try { newClient.disconnect() } catch (_: Exception) {}
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun handleIncomingRawPayload(rawJson: String) {
        scope.launch {
            try {
                val envelope = SignalingEnvelope.fromJson(rawJson)
                if (envelope == null) {
                    AppLogger.w(tag, "Dropped malformed envelope")
                    return@launch
                }

                // Determine decryption key:
                // 1. If sender is in whitelist, derive ECDH key
                // 2. If active OTP is set, derive OTP key
                val senderDevice = securityRepository.getPairedDevice(envelope.senderId)
                val otp = securityRepository.getActivePairingOtp()

                val otpKey = if (otp != null) {
                    com.cammonitor.data.security.CryptoUtils.hkdfSha256(
                        otp.toByteArray(Charsets.UTF_8),
                        ByteArray(0),
                        "CamMonitor-OTP".toByteArray(),
                        32
                    )
                } else null

                val ecdhKey = if (senderDevice != null) {
                    val remotePubKey = com.cammonitor.data.security.CryptoUtils.publicKeyFromBase64(senderDevice.publicKeyBase64)
                    com.cammonitor.data.security.CryptoUtils.deriveEcdhKey(
                        securityRepository.getKeyPair().private,
                        remotePubKey
                    )
                } else null

                val decryptedJson = when {
                    ecdhKey != null -> {
                        val result = securityRepository.verifyAndDecryptEnvelope(envelope, ecdhKey, isOtpKey = false)
                        if (result == null && otpKey != null) {
                            securityRepository.verifyAndDecryptEnvelope(envelope, otpKey, isOtpKey = true)
                        } else {
                            result
                        }
                    }
                    otpKey != null -> {
                        securityRepository.verifyAndDecryptEnvelope(envelope, otpKey, isOtpKey = true)
                    }
                    else -> {
                        AppLogger.w(tag, "Dropped envelope from unauthorized sender: ${envelope.senderId}")
                        return@launch
                    }
                }

                if (decryptedJson != null) {
                    val message = SignalingMessage.fromJson(decryptedJson)
                    if (message != null) {
                        AppLogger.i(tag, "Received verified message [${message.javaClass.simpleName}] from ${envelope.senderId}")
                        _incomingMessages.emit(Pair(envelope.senderId, message))
                    } else {
                        AppLogger.w(tag, "Failed parsing decrypted signaling message")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error handling incoming payload", e)
            }
        }
    }

    private suspend fun awaitConnection(timeoutMs: Long = 10_000L): Boolean {
        if (_isConnectedFlow.value) return true
        startListening()
        return withTimeoutOrNull(timeoutMs) {
            _isConnectedFlow.first { it }
        } ?: false
    }

    override suspend fun sendEnvelope(envelope: SignalingEnvelope): Boolean {
        val connected = awaitConnection()
        if (!connected) {
            AppLogger.e(tag, "Cannot send envelope: Signaling client is not connected")
            return false
        }

        val currentClient = client
        if (currentClient == null) {
            AppLogger.e(tag, "Cannot send envelope: Client instance is null")
            return false
        }

        val targetTopic = "cammonitor/${envelope.recipientId}/rx"
        val payload = envelope.toJson().toByteArray(Charsets.UTF_8)

        return suspendCancellableCoroutine { continuation ->
            try {
                currentClient.publishWith()
                    .topic(targetTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(payload)
                    .send()
                    .whenComplete { _, throwable ->
                        if (throwable != null) {
                            AppLogger.e(tag, "Failed publishing to $targetTopic", throwable)
                            if (continuation.isActive) continuation.resume(false)
                        } else {
                            AppLogger.i(tag, "Published envelope to $targetTopic")
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }
            } catch (e: Exception) {
                AppLogger.e(tag, "Exception during publish to $targetTopic", e)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    override suspend fun sendSignalingMessage(
        recipientId: String,
        message: SignalingMessage,
        secretOrDerivedKey: ByteArray
    ): Boolean {
        val plaintextJson = message.toJson()
        val envelope = securityRepository.createEnvelope(recipientId, plaintextJson, secretOrDerivedKey)
        return sendEnvelope(envelope)
    }

    override suspend fun stopListening() {
        connectMutex.withLock {
            try {
                client?.disconnect()
                client = null
                _isConnectedFlow.value = false
                AppLogger.i(tag, "Disconnected from MQTT signaling")
            } catch (e: Exception) {
                AppLogger.w(tag, "Error during MQTT disconnect: ${e.message}")
            }
        }
    }
}
