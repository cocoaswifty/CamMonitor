package com.a0soft.gphone.camonitor.ui.common

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.a0soft.gphone.camonitor.log.AppLogger

object AudioRoutingManager {
    private const val TAG = "AudioRoutingManager"

    fun routeToSpeaker(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // Ensure voice call volume is adequately high for talkback/intercom
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val currentVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (currentVol < maxVol * 0.7f) {
                val targetVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
                AppLogger.i(TAG, "Adjusted STREAM_VOICE_CALL volume: $currentVol -> $targetVol (max=$maxVol)")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    val success = am.setCommunicationDevice(speaker)
                    AppLogger.i(TAG, "setCommunicationDevice(TYPE_BUILTIN_SPEAKER): $success")
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    AppLogger.i(TAG, "isSpeakerphoneOn set to true (fallback on API ${Build.VERSION.SDK_INT})")
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
                AppLogger.i(TAG, "isSpeakerphoneOn set to true")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to route audio to speaker", e)
        }
    }

    fun restoreNormalMode(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
            AppLogger.i(TAG, "Restored audio mode to NORMAL")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restore audio mode", e)
        }
    }
}
