package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TorchManager(private val context: Context) {
    private val tag = "TorchManager"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private var backCameraId: String? = null

    init {
        findBackCamera()
    }

    private fun findBackCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                    backCameraId = id
                    break
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed querying camera characteristics", e)
        }
    }

    fun setTorch(enabled: Boolean) {
        val id = backCameraId ?: return
        try {
            cameraManager.setTorchMode(id, enabled)
            _isTorchOn.value = enabled
            AppLogger.i(tag, "Torch mode set to: $enabled")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to toggle torch mode", e)
        }
    }
}
