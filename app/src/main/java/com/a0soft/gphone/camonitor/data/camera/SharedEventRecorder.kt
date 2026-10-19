package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.a0soft.gphone.camonitor.service.SmartLabel
import com.a0soft.gphone.camonitor.service.SmartRecordingPolicy
import com.a0soft.gphone.camonitor.log.AppLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adds event detection + MP4 capture to the SAME CameraX binding as the
 * WebRTC Preview stream. Never opens another ProcessCameraProvider or camera.
 *
 * All recording state mutations are posted onto the main thread. ML Kit work
 * is asynchronously scheduled from a single ImageAnalysis executor.
 */
class SharedEventRecorder(private val context: Context) {

    enum class State { OFF, SCANNING, RECORDING, ERROR }

    companion object {
        val state = MutableStateFlow(State.OFF)
        val latestClip = MutableStateFlow<String?>(null)
        val errorMessage = MutableStateFlow<String?>(null)

        fun clipDirectory(context: Context): File {
            val parent = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            return File(parent, "CamMonitorEvents").apply { mkdirs() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(SmartRecordingPolicy.MIN_CONFIDENCE)
            .build()
    )

    private var capture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null
    private var active = false
    private var stopping = false
    private var bindGeneration = 0L
    private var recordingStartedAt = 0L
    private var lastSeenAt = -1L
    private var lastCheckedAt = 0L

    /** Must be called on main thread BEFORE CameraX bindToLifecycle. */
    fun useCases(): Array<androidx.camera.core.UseCase> {
        check(Looper.myLooper() == Looper.getMainLooper())
        prepareForUnbind()
        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analyzer.setAnalyzer(executor) { inspect(it) }
        val recorder = Recorder.Builder().setQualitySelector(
            QualitySelector.from(
                Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
        ).build()
        val video = VideoCapture.withOutput(recorder)
        analysis = analyzer
        capture = video
        active = true
        state.value = State.SCANNING
        errorMessage.value = null
        lastSeenAt = -1L
        lastCheckedAt = 0L
        scheduleStopCheck()
        return arrayOf(analyzer, video)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun inspect(frame: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        // When idle, check approximately every 10 seconds. When recording,
        // inspect frames frequently so the clip can end after absence grace.
        val spacing = if (state.value == State.RECORDING) {
            SmartRecordingPolicy.INFERENCE_SPACING_MS
        } else SmartRecordingPolicy.SLEEP_MS

        if (!active || now - lastCheckedAt < spacing ||
            !busy.compareAndSet(false, true)) {
            frame.close()
            return
        }
        lastCheckedAt = now
        val rawImage = frame.image
        if (rawImage == null) {
            busy.set(false)
            frame.close()
            return
        }
        try {
            labeler.process(
                InputImage.fromMediaImage(rawImage, frame.imageInfo.rotationDegrees)
            ).addOnSuccessListener { labels ->
                if (!active) return@addOnSuccessListener
                val detected = SmartRecordingPolicy.hasTarget(
                    labels.map { SmartLabel(it.text, it.confidence) }
                )
                if (detected) {
                    val at = SystemClock.elapsedRealtime()
                    handler.post {
                        if (!active) return@post
                        lastSeenAt = at
                        if (recording == null && !stopping) startEventRecording()
                    }
                }
            }.addOnFailureListener { ex ->
                AppLogger.w("SharedEventRecorder", "Image label failed: ${ex.message}")
            }.addOnCompleteListener {
                busy.set(false)
                frame.close()
            }
        } catch (ex: Exception) {
            busy.set(false)
            frame.close()
            AppLogger.w("SharedEventRecorder", "Image labeling unavailable: ${ex.message}")
        }
    }

    private fun startEventRecording() {
        val video = capture ?: return
        if (recording != null || stopping) return
        val dir = clipDirectory(context)
        if (dir.usableSpace < 100L * 1024L * 1024L) {
            errorMessage.value = "Not enough free storage for event clip"
            state.value = State.ERROR
            return
        }
        val filename = "event_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mp4"
        val file = File(dir, filename)
        try {
            val recordingGeneration = bindGeneration
            recordingStartedAt = SystemClock.elapsedRealtime()
            recording = video.output.prepareRecording(
                context, FileOutputOptions.Builder(file).build()
            ).start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (recordingGeneration == bindGeneration) {
                        recording = null
                        stopping = false
                    }
                    if (event.hasError()) {
                        file.delete()
                        if (recordingGeneration == bindGeneration) {
                            state.value = State.ERROR
                            errorMessage.value = "Recording finalize error: ${event.error}"
                        }
                        AppLogger.w("SharedEventRecorder", "Video finalize error ${event.error}")
                    } else {
                        latestClip.value = file.absolutePath
                        if (recordingGeneration == bindGeneration && active) {
                            state.value = State.SCANNING
                        }
                        AppLogger.i("SharedEventRecorder", "Saved ${file.name}")
                    }
                }
            }
            state.value = State.RECORDING
        } catch (e: Exception) {
            errorMessage.value = e.message
            state.value = State.ERROR
            AppLogger.e("SharedEventRecorder", "Failed starting clip", e)
        }
    }

    private val checkStop = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (recording != null && !stopping &&
                now - recordingStartedAt >= SmartRecordingPolicy.ABSENCE_GRACE_MS &&
                SmartRecordingPolicy.targetAbsentForLongEnough(lastSeenAt, now)) {
                stopping = true
                recording?.stop()
            }
            handler.postDelayed(this, 300L)
        }
    }

    private fun scheduleStopCheck() {
        handler.removeCallbacks(checkStop)
        handler.postDelayed(checkStop, 300L)
    }

    /** CameraX rebind (night mode etc.) finalizes any clip first. */
    fun prepareForUnbind() {
        bindGeneration++
        active = false
        handler.removeCallbacks(checkStop)
        analysis?.clearAnalyzer()
        analysis = null
        recording?.stop()
        // Finalize arrives asynchronously; do not reuse a pending Recorder.
        recording = null
        capture = null
        stopping = false
        state.value = State.OFF
    }

    fun onUnsupportedCamera(error: Throwable) {
        errorMessage.value = "This camera does not support Preview + Analysis + VideoCapture: ${error.message}"
        state.value = State.ERROR
    }

    fun close() {
        prepareForUnbind()
        labeler.close()
        executor.shutdown()
    }
}
