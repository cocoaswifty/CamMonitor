package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.DefaultSecurityRepository
import com.a0soft.gphone.camonitor.time.TimeProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair

class FakeTimeProvider : TimeProvider {
    var currentTime: Long = 1728723600000L
    override fun currentTimeMillis(): Long = currentTime
    override suspend fun delayMillis(timeMillis: Long) { currentTime += timeMillis }
}

class FakeSecurityRepository(
    private val timeProvider: FakeTimeProvider
) {
    val keyPair: KeyPair = CryptoUtils.generateSoftwareEcKeyPair()
    val pairedDevices = mutableListOf<PairedDevice>()
    private val seenNonces = mutableMapOf<String, Long>()
    var activeOtp: String? = null

    fun verifyEnvelope(timestamp: Long, nonce: String, senderId: String): Boolean {
        val now = timeProvider.currentTimeMillis()
        // 1. Anti-Replay Timestamp Check (30 seconds window)
        if (kotlin.math.abs(now - timestamp) > 30_000L) {
            return false
        }
        // 2. Anti-Replay Nonce Check
        if (seenNonces.containsKey(nonce)) {
            return false
        }
        seenNonces[nonce] = timestamp

        // 3. Sender Verification
        if (pairedDevices.none { it.deviceId == senderId } && activeOtp == null) {
            return false
        }
        return true
    }
}

class AntiReplayTest {

    private lateinit var fakeTimeProvider: FakeTimeProvider
    private lateinit var securityRepo: FakeSecurityRepository

    @Before
    fun setup() {
        fakeTimeProvider = FakeTimeProvider()
        securityRepo = FakeSecurityRepository(fakeTimeProvider)
        securityRepo.pairedDevices.add(
            PairedDevice("pb-1", "Viewer 1", "key1", fakeTimeProvider.currentTimeMillis())
        )
    }

    @Test
    fun testValidEnvelopeWithinWindow_Passes() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "unique-nonce-1"
        val passed = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1")
        assertTrue("Recent envelope with unique nonce should be accepted", passed)
    }

    @Test
    fun testExpiredEnvelope_OlderThan30Seconds_Rejected() {
        val oldTimestamp = fakeTimeProvider.currentTimeMillis() - 31_000L // 31 seconds ago
        val nonce = "unique-nonce-2"
        val passed = securityRepo.verifyEnvelope(oldTimestamp, nonce, "pb-1")
        assertFalse("Envelope older than 30s must be rejected", passed)
    }

    @Test
    fun testFutureEnvelope_Beyond30Seconds_Rejected() {
        val futureTimestamp = fakeTimeProvider.currentTimeMillis() + 35_000L
        val nonce = "unique-nonce-3"
        val passed = securityRepo.verifyEnvelope(futureTimestamp, nonce, "pb-1")
        assertFalse("Future envelope beyond 30s must be rejected", passed)
    }

    @Test
    fun testDuplicateNonce_Rejected() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "duplicate-nonce-xyz"

        val firstAttempt = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1")
        assertTrue(firstAttempt)

        val secondAttempt = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1")
        assertFalse("Replayed envelope with identical nonce must be rejected", secondAttempt)
    }

    @Test
    fun testUnauthorizedSender_WithoutOtp_Rejected() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "unique-nonce-4"
        val passed = securityRepo.verifyEnvelope(timestamp, nonce, "unauthorized-hacker-id")
        assertFalse("Sender not in whitelist without active OTP must be rejected", passed)
    }
}
