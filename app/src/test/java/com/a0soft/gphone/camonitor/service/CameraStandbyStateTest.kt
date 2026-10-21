package com.a0soft.gphone.camonitor.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraStandbyStateTest {
    private class Camera(scope: TestScope, smart: Boolean = true, val delayedClose: Boolean = false) {
        var opens = 0
        var closes = 0
        var mode: CameraDutyCycle.Mode? = null
        var finishClose: (() -> Unit)? = null
        val expired = mutableListOf<String>()
        val duty = CameraDutyCycle(scope.backgroundScope, smart,
            openCamera = { opens++ },
            closeCamera = { done ->
                closes++
                if (delayedClose) finishClose = done else done()
            },
            viewerExpired = { expired.add(it) },
            modeChanged = { mode = it }
        )
        init { duty.start() }
    }

    @Test fun noTargetClosesHardwareAndWaitsTenSecondsBeforeReopening() = runTest {
        val c = Camera(this)
        assertEquals(1, c.opens)
        c.duty.noTarget()
        runCurrent()
        assertEquals(1, c.closes)
        assertEquals(CameraDutyCycle.Mode.SLEEPING, c.mode)
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, c.opens)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, c.opens)
        c.duty.noTarget()
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, c.opens)
    }

    @Test fun sleepIntervalStartsAfterHardwareReportsClosed() = runTest {
        val c = Camera(this, delayedClose = true)
        c.duty.noTarget()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(1, c.opens)
        c.finishClose!!()
        runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, c.opens)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, c.opens)
    }

    @Test fun targetKeepsRecordingUntilFinalizeEvenWhenNoTargetArrives() = runTest {
        val c = Camera(this)
        c.duty.recordingChanged(true)
        c.duty.noTarget()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, c.closes)
        assertEquals(CameraDutyCycle.Mode.RECORDING, c.mode)
        c.duty.recordingChanged(false) // recorder emits only after Finalize
        assertEquals(1, c.closes)
    }

    @Test fun offerDuringSleepWakesWithoutAndroidInteraction() = runTest {
        val c = Camera(this)
        c.duty.noTarget()
        runCurrent()
        advanceTimeBy(2_000)
        assertTrue(c.duty.requestViewer("browser"))
        c.duty.noTarget()
        c.duty.viewerConnected("browser")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, c.opens)
        assertEquals(1, c.closes)
        assertEquals(CameraDutyCycle.Mode.VIEWING, c.mode)
    }

    @Test fun offerDuringHardwareCloseInvalidatesOldSleepCallback() = runTest {
        val c = Camera(this, delayedClose = true)
        c.duty.noTarget()
        val oldClose = c.finishClose!!
        assertTrue(c.duty.requestViewer("browser"))
        c.duty.viewerConnected("browser")
        oldClose()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, c.opens)
        assertEquals(1, c.closes)
    }

    @Test fun pendingOfferProtectsHandshakeButExpiresWithoutAnswer() = runTest {
        val c = Camera(this)
        c.duty.requestViewer("browser")
        c.duty.noTarget()
        runCurrent()
        advanceTimeBy(29_999); runCurrent()
        assertEquals(0, c.closes)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("browser"), c.expired)
        assertEquals(1, c.closes)
    }

    @Test fun duplicateOfferDoesNotExtendLeaseOrCreateMoreDemand() = runTest {
        val c = Camera(this)
        c.duty.requestViewer("one")
        runCurrent()
        advanceTimeBy(20_000)
        c.duty.requestViewer("one")
        assertTrue(c.duty.requestViewer("two"))
        assertFalse(c.duty.requestViewer("three"))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("one"), c.expired)
        assertEquals(0, c.closes)
    }

    @Test fun recordingFinalizeDoesNotInterruptConnectedViewer() = runTest {
        val c = Camera(this)
        c.duty.recordingChanged(true)
        c.duty.requestViewer("browser")
        c.duty.viewerConnected("browser")
        c.duty.recordingChanged(false)
        c.duty.noTarget()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, c.closes)
        c.duty.viewerClosed("browser")
        assertEquals(1, c.closes)
    }

    @Test fun replacementOfferMustReconnectOrReleaseCamera() = runTest {
        val c = Camera(this)
        c.duty.requestViewer("browser")
        c.duty.viewerConnected("browser")
        c.duty.requestViewer("browser")
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("browser"), c.expired)
        assertEquals(1, c.closes)
    }

    @Test fun lastViewerDisconnectDoesNotTruncateRecording() = runTest {
        val c = Camera(this)
        c.duty.requestViewer("browser")
        c.duty.viewerConnected("browser")
        c.duty.recordingChanged(true)
        c.duty.viewerClosed("browser")
        assertEquals(0, c.closes)
        c.duty.recordingChanged(false)
        assertEquals(1, c.closes)
    }

    @Test fun transientDisconnectCanRecoverAndPermanentDisconnectExpires() = runTest {
        val c = Camera(this)
        c.duty.requestViewer("browser")
        c.duty.viewerConnected("browser")
        c.duty.viewerDisconnected("browser")
        runCurrent()
        advanceTimeBy(5_000)
        c.duty.viewerConnected("browser")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, c.closes)
        c.duty.viewerDisconnected("browser")
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, c.closes)
    }

    @Test fun repeatedStartDoesNotCreateAnotherProbeAndStopCancelsTimers() = runTest {
        val c = Camera(this)
        c.duty.start()
        assertEquals(1, c.opens)
        c.duty.noTarget()
        c.duty.stop()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, c.opens)
        assertFalse(c.duty.requestViewer("late"))
    }

    @Test fun disabledSmartModeKeepsCameraOffUntilViewerRequests() = runTest {
        val c = Camera(this, smart = false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, c.opens)
        assertTrue(c.duty.requestViewer("browser"))
        assertEquals(1, c.opens)
        c.duty.viewerClosed("browser")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, c.opens)
        assertEquals(1, c.closes)
    }

    @Test fun stalledProbeTimesOutAndRetriesAfterSleep() = runTest {
        val c = Camera(this)
        runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertEquals(1, c.closes)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, c.opens)
    }
}
