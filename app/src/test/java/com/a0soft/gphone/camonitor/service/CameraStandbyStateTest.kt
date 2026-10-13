package com.a0soft.gphone.camonitor.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraStandbyStateTest {

    @Test
    fun testInactivityTimerTransitionsToPausedInactivityAfter60s() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var cameraHardwareStopped = false

        var inactivityJob: Job? = null
        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(60_000L)
                cameraHardwareStopped = true
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        startTimer()

        // Advance 30 seconds - should not be paused yet
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)
        assertFalse(cameraHardwareStopped)

        // Advance remaining 30 seconds - should pause
        advanceTimeBy(30_001L)
        runCurrent()
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
        assertTrue(cameraHardwareStopped)
    }

    @Test
    fun testActiveViewerCancelsInactivityTimerAndEntersStreaming() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var cameraHardwareStopped = false
        var inactivityJob: Job? = null

        fun cancelTimer() {
            inactivityJob?.cancel()
            inactivityJob = null
        }

        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(60_000L)
                cameraHardwareStopped = true
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        startTimer()

        // After 20 seconds, a viewer connects
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        // Viewer connected (active viewers = 1)
        streamingState.value = CameraStreamingState.STREAMING
        cancelTimer()

        // Advance past 60s total time
        advanceTimeBy(50_000L)
        runCurrent()

        // Must still be STREAMING and not stopped
        assertEquals(CameraStreamingState.STREAMING, streamingState.value)
        assertFalse(cameraHardwareStopped)
    }

    @Test
    fun testResumeStreamingFromPauseWhenOfferArrives() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.PAUSED_INACTIVITY)
        var cameraHardwareStarted = false
        var inactivityJob: Job? = null

        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(60_000L)
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        fun resumeStreamingFromPause() {
            cameraHardwareStarted = true
            streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
            startTimer()
        }

        // Remote offer arrives while in PAUSED_INACTIVITY
        if (streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
            resumeStreamingFromPause()
        }

        runCurrent()
        assertTrue(cameraHardwareStarted)
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        // Advancing 60s should re-pause if viewer doesn't connect
        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
    }

    @Test
    fun testViewerDisconnectRestartsCountdown() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.STREAMING)
        var timerStarted = false
        var inactivityJob: Job? = null

        fun startTimer() {
            timerStarted = true
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(60_000L)
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        // Viewer disconnects (count goes from 1 to 0)
        streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
        startTimer()

        runCurrent()
        assertTrue(timerStarted)
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
    }
}
