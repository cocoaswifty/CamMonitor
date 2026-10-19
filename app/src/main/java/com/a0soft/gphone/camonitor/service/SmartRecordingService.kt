package com.a0soft.gphone.camonitor.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.log.AppLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device smart recording is a separate, user-started camera foreground
 * service. It must not run concurrently with CameraMonitorService.
 */
class SmartRecordingService : LifecycleService() {
    enum class Phase { OFF, PROBING, RECORDING, SLEEPING, ERROR }

    companion object {
        const val ACTION_STOP = "com.a0soft.gphone.camonitor.STOP_SMART_RECORDING"
        private const val TAG = "SmartRecording"
        private const val NOTIFICATION_ID = 1002
        val phase = MutableStateFlow(Phase.OFF)
        val lastVideoPath = MutableStateFlow<String?>(null)
        val lastError = MutableStateFlow<String?>(null)

        fun storageDirectory(context: Context): File {
            val parent = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            return File(parent, "CamMonitorEvents").also { if (!it.exists()) it.mkdirs() }
        }
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val inferenceBusy = AtomicBoolean(false)
    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(SmartRecordingPolicy.MIN_CONFIDENCE).build()
        )
    }
    private var job: Job? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private var finalizeSignal: CompletableDeferred<Unit>? = null
    private var probeSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var lastTargetAt = -1L
    @Volatile private var lastInferenceAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            lifecycleScope.launch {
                job?.join()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            lastError.value = "Camera permission missing"
            phase.value = Phase.ERROR
            stopSelf()
            return START_NOT_STICKY
        }

        // Android requires foreground startup while CAMERA while-in-use permission
        // is active; the Activity is responsible for starting this service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification("Smart recording is active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("Smart recording is active"))
        }
        lastError.value = null
        job = lifecycleScope.launch {
            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        scanAndRecord()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLogger.e(TAG, "Smart recording cycle failed", error)
                        lastError.value = error.message ?: error.javaClass.simpleName
                        phase.value = Phase.ERROR
                    }
                    phase.value = Phase.SLEEPING
                    updateNotification(getString(R.string.smart_record_sleep))
                    // Camera resources are released BEFORE the ten second sleep.
                    delay(SmartRecordingPolicy.SLEEP_MS)
                }
            } finally {
                phase.value = Phase.OFF
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val intent = Intent(this, SmartRecordingService::class.java).apply { action = ACTION_STOP }
        val pending = PendingIntent.getService(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CamMonitorApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.smart_record_title))
            .setContentText(message)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.smart_record_stop), pending
            )
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private suspend fun getProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    if (continuation.isActive) continuation.resume(future.get())
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(this))
        }

    private suspend fun scanAndRecord() {
        val cameraProvider = getProvider()
        provider = cameraProvider
        lastTargetAt = -1L
        lastInferenceAt = 0L
        val foundSignal = CompletableDeferred<Boolean>()
        probeSignal = foundSignal

        try {
            val capture: VideoCapture<Recorder> = withContext(Dispatchers.Main.immediate) {
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(cameraExecutor) { frame -> inspectFrame(frame) }
                analysis = analyzer

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                        )
                    )
                    .build()
                val video = VideoCapture.withOutput(recorder)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@SmartRecordingService,
                    CameraSelector.DEFAULT_BACK_CAMERA, analyzer, video
                )
                video
            }

            phase.value = Phase.PROBING
            updateNotification(getString(R.string.smart_record_scanning))
            val found = withTimeoutOrNull(SmartRecordingPolicy.PROBE_MS) {
                foundSignal.await()
            } == true
            probeSignal = null

            if (found) {
                val directory = storageDirectory(this)
                require(directory.usableSpace >= 100L * 1024L * 1024L) {
                    "Insufficient free storage"
                }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val videoFile = File(directory, "event_" + timestamp + ".mp4")
                val finalized = CompletableDeferred<Unit>()
                finalizeSignal = finalized

                withContext(Dispatchers.Main.immediate) {
                    activeRecording = capture.output.prepareRecording(
                        this@SmartRecordingService,
                        FileOutputOptions.Builder(videoFile).build()
                    ).start(ContextCompat.getMainExecutor(this@SmartRecordingService)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError()) {
                                videoFile.delete()
                                lastError.value = "Video finalize error: " + event.error
                                AppLogger.w(TAG, "Video finalize error: " + event.error)
                            } else {
                                lastVideoPath.value = videoFile.absolutePath
                                AppLogger.i(TAG, "Event recorded: " + videoFile.name)
                            }
                            finalized.complete(Unit)
                        }
                    }
                    phase.value = Phase.RECORDING
                    updateNotification(getString(R.string.smart_record_recording))
                }

                // End after four seconds without a positive classification.
                while (currentCoroutineContext().isActive && !finalized.isCompleted &&
                    !SmartRecordingPolicy.targetAbsentForLongEnough(
                        lastTargetAt, SystemClock.elapsedRealtime()
                    )) {
                    delay(300L)
                }
            }
        } finally {
            // Always finalize the clip and unbind the camera when stopping or sleeping.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                probeSignal = null
                analysis?.clearAnalyzer()
                analysis = null
                activeRecording?.stop()
                activeRecording = null
                finalizeSignal?.let { withTimeoutOrNull(8000L) { it.await() } }
                finalizeSignal = null
                cameraProvider.unbindAll()
                provider = null
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun inspectFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInferenceAt < SmartRecordingPolicy.INFERENCE_SPACING_MS ||
            !inferenceBusy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastInferenceAt = now
        val image = proxy.image
        if (image == null) {
            inferenceBusy.set(false)
            proxy.close()
            return
        }
        try {
            labeler.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { labels ->
                    val hasSubject = SmartRecordingPolicy.hasTarget(
                        labels.map { SmartLabel(it.text, it.confidence) }
                    )
                    if (hasSubject) {
                        lastTargetAt = SystemClock.elapsedRealtime()
                        probeSignal?.complete(true)
                    }
                }
                .addOnFailureListener { e ->
                    AppLogger.w(TAG, "Image labeling failed: " + e.message)
                }
                .addOnCompleteListener {
                    inferenceBusy.set(false)
                    proxy.close()
                }
        } catch (error: Exception) {
            inferenceBusy.set(false)
            proxy.close()
            AppLogger.e(TAG, "Unable to analyze image", error)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        activeRecording?.stop()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        cameraExecutor.shutdown()
        labeler.close()
        phase.value = Phase.OFF
        super.onDestroy()
    }
}
