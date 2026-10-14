package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair

class TestSecurityRepository : SecurityRepository {
    private var audioPlaybackEnabled: Boolean = false
    private val keyPair = CryptoUtils.generateSoftwareEcKeyPair()

    override fun getDeviceId(): String = "test-device-id"
    override fun getDeviceName(): String = "Test-Device"
    override fun setDeviceName(name: String) {}
    override fun getDevicePublicKeyBase64(): String = CryptoUtils.publicKeyToBase64(keyPair.public)
    override fun getKeyPair(): KeyPair = keyPair

    override fun generatePairingOtp(): String = "123456"
    override fun setActivePairingOtp(otp: String) {}
    override fun getActivePairingOtp(): String? = null
    override fun clearPairingOtp() {}

    override fun getPairedDevices(): List<PairedDevice> = emptyList()
    override fun addPairedDevice(device: PairedDevice, maxLimit: Int): Boolean = true
    override fun removePairedDevice(deviceId: String) {}
    override fun isDeviceWhitelisted(deviceId: String): Boolean = true
    override fun getPairedDevice(deviceId: String): PairedDevice? = null

    override fun createEnvelope(recipientId: String, plaintextJson: String, secretOrDerivedKey: ByteArray): SignalingEnvelope {
        throw UnsupportedOperationException()
    }
    override fun verifyAndDecryptEnvelope(envelope: SignalingEnvelope, secretOrDerivedKey: ByteArray, isOtpKey: Boolean): String? {
        throw UnsupportedOperationException()
    }

    override fun isViewerAudioPlaybackEnabled(): Boolean = audioPlaybackEnabled

    override fun setViewerAudioPlaybackEnabled(enabled: Boolean) {
        audioPlaybackEnabled = enabled
    }
}

class ViewerAudioSettingsTest {

    private lateinit var repository: SecurityRepository

    @Before
    fun setup() {
        repository = TestSecurityRepository()
    }

    @Test
    fun defaultViewerAudioPlayback_isOff() {
        // Viewer audio playback should default to OFF (muted) to prevent acoustic howling
        assertFalse("Viewer audio playback must be disabled by default", repository.isViewerAudioPlaybackEnabled())
    }

    @Test
    fun toggleViewerAudioPlayback_persistsState() {
        assertFalse(repository.isViewerAudioPlaybackEnabled())

        // Turn on audio
        repository.setViewerAudioPlaybackEnabled(true)
        assertTrue("Viewer audio playback should be enabled after toggling", repository.isViewerAudioPlaybackEnabled())

        // Turn off audio
        repository.setViewerAudioPlaybackEnabled(false)
        assertFalse("Viewer audio playback should be disabled after toggling off", repository.isViewerAudioPlaybackEnabled())
    }
}
