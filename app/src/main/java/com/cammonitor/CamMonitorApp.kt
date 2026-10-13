package com.cammonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.cammonitor.data.camera.TorchManager
import com.cammonitor.data.security.DefaultSecurityRepository
import com.cammonitor.data.security.SecurityRepository
import com.cammonitor.data.signaling.MqttSignalingClient
import com.cammonitor.data.signaling.SignalingClient
import com.cammonitor.data.webrtc.WebRtcManager
import com.cammonitor.log.AppLogger
import com.cammonitor.time.DefaultTimeProvider
import com.cammonitor.time.TimeProvider

class CamMonitorApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "cammonitor_service_channel"
        lateinit var instance: CamMonitorApp
            private set
    }

    lateinit var timeProvider: TimeProvider
        private set
    lateinit var securityRepository: SecurityRepository
        private set
    lateinit var signalingClient: SignalingClient
        private set
    lateinit var webRtcManager: WebRtcManager
        private set
    lateinit var torchManager: TorchManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        AppLogger.init(this)
        AppLogger.i("CamMonitorApp", "Application starting, initializing core dependencies")

        timeProvider = DefaultTimeProvider()
        securityRepository = DefaultSecurityRepository(this, timeProvider)
        signalingClient = MqttSignalingClient(securityRepository)
        webRtcManager = WebRtcManager(this)
        torchManager = TorchManager(this)

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for active camera streaming"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
