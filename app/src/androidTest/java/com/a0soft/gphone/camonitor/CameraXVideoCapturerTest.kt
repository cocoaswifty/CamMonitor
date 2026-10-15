package com.a0soft.gphone.camonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.a0soft.gphone.camonitor.data.camera.CameraXVideoCapturer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CameraXVideoCapturerTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA"
        )
        Thread.sleep(500)
    }

    @Test
    fun testCameraXVideoCapturerStartsAndCapturesFrames() {
        val eglBase = EglBase.create()
        val surfaceTextureHelper = SurfaceTextureHelper.create("TestCaptureThread", eglBase.eglBaseContext)
        val capturer = CameraXVideoCapturer(context, preferNightMode = true)

        val frameCount = AtomicInteger(0)
        val lastFrameRotation = AtomicInteger(-1)
        val startedLatch = CountDownLatch(1)
        val framesLatch = CountDownLatch(3)

        val observer = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                if (success) startedLatch.countDown()
            }

            override fun onCapturerStopped() {}

            override fun onFrameCaptured(frame: VideoFrame) {
                assertNotNull("Frame buffer should not be null", frame.buffer)
                assertTrue("Width should be > 0", frame.buffer.width > 0)
                assertTrue("Height should be > 0", frame.buffer.height > 0)
                lastFrameRotation.set(frame.rotation)
                android.util.Log.i("CameraXTest", "onFrameCaptured: ${frame.buffer.width}x${frame.buffer.height}, rotation=${frame.rotation}")
                frameCount.incrementAndGet()
                framesLatch.countDown()
            }
        }

        capturer.initialize(surfaceTextureHelper, context, observer)
        capturer.startCapture(1280, 720, 20)

        assertTrue("Capturer should report success within 5 seconds", startedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Capturer should deliver at least 3 frames within 10 seconds", framesLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Frame count should be >= 3", frameCount.get() >= 3)

        // Verify captured frame rotation matches relative orientation of capturer
        val targetDegrees = when (capturer.currentTargetRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val expectedRotation = (90 - targetDegrees + 360) % 360
        org.junit.Assert.assertEquals("Captured frame rotation should match relative orientation", expectedRotation, lastFrameRotation.get())

        // Verify torch toggling on active capturer
        val torchOnResult = capturer.setTorch(true)
        assertTrue("Torch on should succeed", torchOnResult)
        Thread.sleep(500)
        val torchOffResult = capturer.setTorch(false)
        assertTrue("Torch off should succeed", torchOffResult)

        capturer.stopCapture()
        capturer.dispose()
        surfaceTextureHelper.dispose()
        eglBase.release()
    }

    @Test
    fun testCameraXVideoCapturerDynamicRotation() {
        val eglBase = EglBase.create()
        val surfaceTextureHelper = SurfaceTextureHelper.create("TestRotationThread", eglBase.eglBaseContext)
        val capturer = CameraXVideoCapturer(context, preferNightMode = true)

        val startedLatch = CountDownLatch(1)
        val initialLatch = CountDownLatch(2)
        val dynamicLatch = CountDownLatch(2)
        val lastRotation = AtomicInteger(-1)
        val testDynamic = java.util.concurrent.atomic.AtomicBoolean(false)

        val observer = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                if (success) startedLatch.countDown()
            }
            override fun onCapturerStopped() {}
            override fun onFrameCaptured(frame: VideoFrame) {
                lastRotation.set(frame.rotation)
                if (!testDynamic.get()) {
                    initialLatch.countDown()
                } else {
                    dynamicLatch.countDown()
                }
            }
        }

        capturer.initialize(surfaceTextureHelper, context, observer)
        capturer.startCapture(1280, 720, 20)
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(initialLatch.await(10, TimeUnit.SECONDS))

        // Change target rotation dynamically: switch to landscape (ROTATION_90)
        testDynamic.set(true)
        capturer.setTargetRotation(android.view.Surface.ROTATION_90)
        assertTrue("Capturer should deliver frames after dynamic rotation", dynamicLatch.await(10, TimeUnit.SECONDS))
        // Back sensor (90) - ROTATION_90 (90) = 0 degrees relative rotation
        org.junit.Assert.assertEquals("Dynamic landscape rotation should produce 0 frame rotation", 0, lastRotation.get())

        capturer.stopCapture()
        capturer.dispose()
        surfaceTextureHelper.dispose()
        eglBase.release()
    }
}
