package com.a0soft.gphone.camonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.a0soft.gphone.camonitor.data.camera.TorchManager
import com.a0soft.gphone.camonitor.data.security.DefaultSecurityRepository
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient
import com.a0soft.gphone.camonitor.data.signaling.SignalingClient
import com.a0soft.gphone.camonitor.data.webrtc.WebRtcManager
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.time.DefaultTimeProvider
import com.a0soft.gphone.camonitor.time.TimeProvider

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
