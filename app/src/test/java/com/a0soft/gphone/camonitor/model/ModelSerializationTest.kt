package com.a0soft.gphone.camonitor.model

import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.data.model.PairingPayload
import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import org.junit.Assert.*
import org.junit.Test

class ModelSerializationTest {

    @Test
    fun testPairingPayloadSerializationAndDeserialization() {
        val original = PairingPayload(
            ver = 1,
            deviceId = "device-12345",
            name = "Living Room Cam",
            secret = "random-base64-otp-secret"
        )

        val jsonStr = original.toJson()
        val parsed = PairingPayload.fromJson(jsonStr)

        assertNotNull(parsed)
        assertEquals(original.ver, parsed?.ver)
        assertEquals(original.deviceId, parsed?.deviceId)
        assertEquals(original.name, parsed?.name)
        assertEquals(original.secret, parsed?.secret)
    }

    @Test
    fun testSignalingEnvelopeSerializationAndDeserialization() {
        val original = SignalingEnvelope(
            senderId = "sender-1",
            recipientId = "recipient-2",
            timestamp = 1728723600000L,
            nonce = "a1b2c3d4e5f67890",
            ciphertext = "cipher123",
            iv = "iv123",
            tag = "tag123",
            signature = "sig123"
        )

        val jsonStr = original.toJson()
        val parsed = SignalingEnvelope.fromJson(jsonStr)

        assertNotNull(parsed)
        assertEquals(original.senderId, parsed?.senderId)
        assertEquals(original.recipientId, parsed?.recipientId)
        assertEquals(original.timestamp, parsed?.timestamp)
        assertEquals(original.nonce, parsed?.nonce)
        assertEquals(original.ciphertext, parsed?.ciphertext)
        assertEquals(original.signature, parsed?.signature)
    }

    @Test
    fun testSignalingMessageJson() {
        val offer = SignalingMessage.Offer(sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1")
        val json = offer.toJson()
        val parsed = SignalingMessage.fromJson(json)

        assertTrue(parsed is SignalingMessage.Offer)
        assertEquals(offer.sdp, (parsed as SignalingMessage.Offer).sdp)

        val reject = SignalingMessage.Reject(reason = "MAX_CAPACITY")
        val parsedReject = SignalingMessage.fromJson(reject.toJson())
        assertTrue(parsedReject is SignalingMessage.Reject)
        assertEquals("MAX_CAPACITY", (parsedReject as SignalingMessage.Reject).reason)

        val answerWithoutName = SignalingMessage.Answer(sdp = "v=0\r\nsdp-content")
        val parsedAnswerWithoutName = SignalingMessage.fromJson(answerWithoutName.toJson()) as? SignalingMessage.Answer
        assertNotNull(parsedAnswerWithoutName)
        assertEquals(answerWithoutName.sdp, parsedAnswerWithoutName?.sdp)
        assertNull(parsedAnswerWithoutName?.cameraName)

        val answerWithName = SignalingMessage.Answer(sdp = "v=0\r\nsdp-content", cameraName = "Kitchen Cam")
        val parsedAnswerWithName = SignalingMessage.fromJson(answerWithName.toJson()) as? SignalingMessage.Answer
        assertNotNull(parsedAnswerWithName)
        assertEquals(answerWithName.sdp, parsedAnswerWithName?.sdp)
        assertEquals("Kitchen Cam", parsedAnswerWithName?.cameraName)
    }

    @Test
    fun testControlCommandParsing() {
        val torchOn = ControlCommand.Torch(enabled = true)
        val parsedTorch = ControlCommand.fromJson(torchOn.toJson())
        assertTrue(parsedTorch is ControlCommand.Torch)
        assertTrue((parsedTorch as ControlCommand.Torch).enabled)

        val pttStart = ControlCommand.PttStart
        val parsedPttStart = ControlCommand.fromJson(pttStart.toJson())
        assertEquals(ControlCommand.PttStart, parsedPttStart)

        val rename = ControlCommand.Rename(name = "Nursery Cam")
        val parsedRename = ControlCommand.fromJson(rename.toJson())
        assertTrue(parsedRename is ControlCommand.Rename)
        assertEquals("Nursery Cam", (parsedRename as ControlCommand.Rename).name)

        val batteryCharging = ControlCommand.Battery(percentage = 85, isCharging = true)
        val parsedBatteryCharging = ControlCommand.fromJson(batteryCharging.toJson())
        assertTrue(parsedBatteryCharging is ControlCommand.Battery)
        assertEquals(85, (parsedBatteryCharging as ControlCommand.Battery).percentage)
        assertTrue(parsedBatteryCharging.isCharging)

        val batteryDischarging = ControlCommand.Battery(percentage = 42, isCharging = false)
        val parsedBatteryDischarging = ControlCommand.fromJson(batteryDischarging.toJson())
        assertTrue(parsedBatteryDischarging is ControlCommand.Battery)
        assertEquals(42, (parsedBatteryDischarging as ControlCommand.Battery).percentage)
        assertFalse(parsedBatteryDischarging.isCharging)

        val queryBattery = ControlCommand.QueryBattery
        val parsedQueryBattery = ControlCommand.fromJson(queryBattery.toJson())
        assertEquals(ControlCommand.QueryBattery, parsedQueryBattery)
    }

    @Test
    fun testBatteryHelperFormatPercentage() {
        assertEquals("85%⚡", com.a0soft.gphone.camonitor.ui.common.BatteryHelper.formatPercentage(com.a0soft.gphone.camonitor.ui.common.BatteryInfo(85, true)))
        assertEquals("42%", com.a0soft.gphone.camonitor.ui.common.BatteryHelper.formatPercentage(com.a0soft.gphone.camonitor.ui.common.BatteryInfo(42, false)))
        assertEquals("--%", com.a0soft.gphone.camonitor.ui.common.BatteryHelper.formatPercentage(null))
        assertEquals("--%", com.a0soft.gphone.camonitor.ui.common.BatteryHelper.formatPercentage(com.a0soft.gphone.camonitor.ui.common.BatteryInfo(-1, false)))
    }

    @Test
    fun testRotationMath() {
        var rotation = 0f
        rotation = (rotation + 90f) % 360f
        assertEquals(90f, rotation, 0.01f)

        rotation = (rotation + 90f) % 360f
        assertEquals(180f, rotation, 0.01f)

        rotation = (rotation + 90f) % 360f
        assertEquals(270f, rotation, 0.01f)

        rotation = (rotation + 90f) % 360f
        assertEquals(0f, rotation, 0.01f)
    }
}
