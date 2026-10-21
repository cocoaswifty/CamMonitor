package com.a0soft.gphone.camonitor.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread owner of camera demand. Signaling stays alive while hardware sleeps. */
class CameraDutyCycle(
    private val scope: CoroutineScope,
    private val smartEnabled: Boolean,
    private val openCamera: () -> Unit,
    private val closeCamera: (onClosed: () -> Unit) -> Unit,
    private val viewerExpired: (String) -> Unit,
    private val modeChanged: (Mode) -> Unit
) {
    enum class Mode { SLEEPING, PROBING, RECORDING, VIEWING }

    private var started = false
    private var cameraOpen = false
    private var recording = false
    private var sleepGeneration = 0
    private var timer: Job? = null
    private val viewers = mutableSetOf<String>()
    private val pending = mutableMapOf<String, Job>()

    fun start() {
        if (started) return
        started = true
        if (smartEnabled) probe() else sleep()
    }

    fun requestViewer(id: String): Boolean {
        if (!started) return false
        if (id !in viewers && id !in pending && viewers.size + pending.size >= 2) return false
        // A new SDP from an existing viewer replaces its old connection.
        // It must prove CONNECTED again rather than retaining an infinite lease.
        viewers.remove(id)
        if (id !in viewers && id !in pending) lease(id, 30_000L)
        timer?.cancel()
        sleepGeneration++
        ensureOpen()
        reportMode()
        return true
    }

    fun viewerConnected(id: String) {
        if (!started || (id !in pending && id !in viewers)) return
        pending.remove(id)?.cancel()
        viewers.add(id)
        reportMode()
    }

    fun viewerDisconnected(id: String) {
        if (!started || id !in viewers) return
        viewers.remove(id)
        // Allow a transient network interruption to recover before releasing demand.
        lease(id, 10_000L)
    }

    fun viewerClosed(id: String) {
        pending.remove(id)?.cancel()
        viewers.remove(id)
        settle()
    }

    private fun lease(id: String, timeout: Long) {
        pending[id] = scope.launch {
            delay(timeout)
            pending.remove(id)
            viewerExpired(id)
            settle()
        }
    }

    fun recordingChanged(value: Boolean) {
        if (!started || !cameraOpen) return
        recording = value
        if (value) timer?.cancel()
        if (value || hasViewers()) reportMode() else sleep()
    }

    fun noTarget() {
        if (started && cameraOpen && !recording && !hasViewers()) sleep()
    }

    fun cameraFailed() {
        if (!started) return
        // Retry an unavailable camera; do not mistake a failure for a negative detection.
        timer?.cancel()
        if (!hasViewers() && !recording) sleep()
        else timer = scope.launch {
            delay(1_000L)
            if (started && (hasViewers() || recording)) openCamera()
        }
    }

    private fun hasViewers() = viewers.isNotEmpty() || pending.isNotEmpty()

    private fun settle() {
        if (!started) return
        if (recording || hasViewers()) reportMode() else sleep()
    }

    private fun ensureOpen() {
        if (cameraOpen) return
        cameraOpen = true
        openCamera()
    }

    private fun probe() {
        timer?.cancel()
        ensureOpen()
        reportMode()
        timer = scope.launch {
            delay(SmartRecordingPolicy.PROBE_MS)
            // A stalled camera/model must not keep the sensor open indefinitely.
            if (!recording && !hasViewers()) sleep()
        }
    }

    private fun sleep() {
        timer?.cancel()
        val generation = ++sleepGeneration
        modeChanged(Mode.SLEEPING)
        val closed = {
            if (started && smartEnabled && generation == sleepGeneration) {
                timer = scope.launch {
                    delay(SmartRecordingPolicy.SLEEP_MS)
                    if (started && !recording && !hasViewers()) probe()
                }
            }
        }
        if (cameraOpen) {
            cameraOpen = false
            closeCamera(closed)
        } else closed()
    }

    private fun reportMode() = modeChanged(when {
        hasViewers() -> Mode.VIEWING
        recording -> Mode.RECORDING
        else -> Mode.PROBING
    })

    fun stop() {
        started = false
        sleepGeneration++
        timer?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        viewers.clear()
    }
}
