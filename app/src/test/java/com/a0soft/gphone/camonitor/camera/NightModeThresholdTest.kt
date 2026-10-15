package com.a0soft.gphone.camonitor.camera

import com.a0soft.gphone.camonitor.data.camera.CameraXVideoCapturer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeThresholdTest {

    @Test
    fun testNightModeThresholdConstants() {
        assertEquals("Night enter lux threshold should be 10.0f", 10.0f, CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER, 0.001f)
        assertEquals("Night exit lux threshold should be 25.0f", 25.0f, CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT, 0.001f)
        assertTrue("Exit lux threshold must be greater than enter lux for hysteresis", CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT > CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER)
        assertEquals("Debounce delay should be 1500ms", 1500L, CameraXVideoCapturer.LIGHT_DEBOUNCE_DELAY_MS)
    }

    @Test
    fun testHysteresisLogic() {
        // Given starting in standard (day) mode
        var isNight = false

        // Simulate bright room (150 lux) -> stays day
        isNight = evaluateNightMode(150.0f, isNight)
        assertFalse("150 lux should keep night mode off", isNight)

        // Drop below 10 lux (e.g. 5 lux) -> enters night
        isNight = evaluateNightMode(5.0f, isNight)
        assertTrue("5 lux should trigger night mode", isNight)

        // Ambient rises to 18 lux (within hysteresis deadband 10..25) -> stays night
        isNight = evaluateNightMode(18.0f, isNight)
        assertTrue("18 lux is within deadband and should remain night mode", isNight)

        // Ambient rises above 25 lux (e.g. 30 lux) -> exits night
        isNight = evaluateNightMode(30.0f, isNight)
        assertFalse("30 lux should exit night mode to standard mode", isNight)

        // Ambient drops to 15 lux (within deadband) -> stays day
        isNight = evaluateNightMode(15.0f, isNight)
        assertFalse("15 lux is within deadband and should remain standard mode", isNight)
    }

    private fun evaluateNightMode(lux: Float, currentNight: Boolean): Boolean {
        return when {
            lux < CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER -> true
            lux > CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT -> false
            else -> currentNight
        }
    }
}
