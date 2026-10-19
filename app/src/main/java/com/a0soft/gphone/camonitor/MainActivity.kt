package com.a0soft.gphone.camonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.a0soft.gphone.camonitor.ui.about.AboutScreen
import com.a0soft.gphone.camonitor.ui.camera.CameraModeScreen
import com.a0soft.gphone.camonitor.ui.devices.ManageDevicesScreen
import com.a0soft.gphone.camonitor.ui.home.HomeScreen
import com.a0soft.gphone.camonitor.ui.log.LogViewerScreen
import com.a0soft.gphone.camonitor.ui.pairing.WebViewerPairingScreen
import com.a0soft.gphone.camonitor.ui.pairing.CameraPairingScreen
import com.a0soft.gphone.camonitor.ui.pairing.ViewerPairingScreen
import com.a0soft.gphone.camonitor.ui.smart.SmartRecordingScreen
import com.a0soft.gphone.camonitor.service.SmartRecordingService
import com.a0soft.gphone.camonitor.ui.theme.CamMonitorTheme
import com.a0soft.gphone.camonitor.ui.viewer.ViewerModeScreen

enum class AppScreen {
    HOME,
    CAMERA_PAIRING,
    WEB_VIEWER_PAIRING,
    CAMERA_MODE,
    SMART_RECORDING,
    VIEWER_PAIRING,
    VIEWER_MODE,
    MANAGE_DEVICES,
    LOGS,
    ABOUT
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Permissions handled
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getLocalizedContext(newBase))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            com.a0soft.gphone.camonitor.ui.common.LocaleHelper.applyCurrentLocale(this)
        }
    }

    private var currentLanguageTag: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentLanguageTag = com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getCurrentLanguageTag(this)
        enableEdgeToEdge()

        requestRequiredPermissions()

        setContent {
            CamMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CamMonitorAppContent()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val latestTag = com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getCurrentLanguageTag(this)
        if (latestTag != currentLanguageTag) {
            currentLanguageTag = latestTag
            recreate()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @Composable
    private fun CamMonitorAppContent() {
        val backStack = remember { mutableStateListOf(AppScreen.HOME) }
        val currentScreen = backStack.lastOrNull() ?: AppScreen.HOME
        val securityRepo = remember { CamMonitorApp.instance.securityRepository }
        var selectedCameraDeviceId by remember { mutableStateOf<String?>(null) }

        fun navigateTo(screen: AppScreen) {
            backStack.add(screen)
        }

        fun navigateBack() {
            if (backStack.size > 1) {
                val popped = backStack.removeAt(backStack.size - 1)
                if (popped == AppScreen.CAMERA_MODE) {
                    val stopIntent = android.content.Intent(this@MainActivity, com.a0soft.gphone.camonitor.service.CameraMonitorService::class.java)
                    stopService(stopIntent)
                } else if (popped == AppScreen.VIEWER_MODE) {
                    CamMonitorApp.instance.webRtcManager.closeAll()
                }
            } else {
                finish()
            }
        }

        androidx.activity.compose.BackHandler(enabled = backStack.size > 1) {
            navigateBack()
        }

        when (currentScreen) {
            AppScreen.HOME -> {
                HomeScreen(
                    onStartCameraMode = {
                        if (SmartRecordingService.phase.value != SmartRecordingService.Phase.OFF) {
                            navigateTo(AppScreen.SMART_RECORDING)
                        } else {
                            val viewerCount = securityRepo.getPairedViewers().size
                            if (viewerCount == 0) {
                                navigateTo(AppScreen.CAMERA_PAIRING)
                            } else {
                                navigateTo(AppScreen.CAMERA_MODE)
                            }
                        }
                    },
                    onPairNewViewer = {
                        if (SmartRecordingService.phase.value != SmartRecordingService.Phase.OFF) {
                            navigateTo(AppScreen.SMART_RECORDING)
                        } else {
                            navigateTo(AppScreen.CAMERA_PAIRING)
                        }
                    },
                    onStartViewerMode = { deviceId ->
                        if (SmartRecordingService.phase.value != SmartRecordingService.Phase.OFF) {
                            navigateTo(AppScreen.SMART_RECORDING)
                        } else {
                            selectedCameraDeviceId = deviceId
                            navigateTo(AppScreen.VIEWER_MODE)
                        }
                    },
                    onPairNewCamera = {
                        if (SmartRecordingService.phase.value != SmartRecordingService.Phase.OFF) {
                            navigateTo(AppScreen.SMART_RECORDING)
                        } else {
                            navigateTo(AppScreen.VIEWER_PAIRING)
                        }
                    },
                    onOpenPairedDevices = { navigateTo(AppScreen.MANAGE_DEVICES) },
                    onOpenLogs = { navigateTo(AppScreen.LOGS) },
                    onOpenAbout = { navigateTo(AppScreen.ABOUT) },
                    onOpenSmartRecording = { navigateTo(AppScreen.SMART_RECORDING) },
                    onOpenWebViewerPairing = {
                        if (SmartRecordingService.phase.value != SmartRecordingService.Phase.OFF) {
                            navigateTo(AppScreen.SMART_RECORDING)
                        } else {
                            navigateTo(AppScreen.WEB_VIEWER_PAIRING)
                        }
                    }
                )
            }
            AppScreen.SMART_RECORDING -> {
                SmartRecordingScreen(onBack = { navigateBack() })
            }
            AppScreen.WEB_VIEWER_PAIRING -> {
                WebViewerPairingScreen(
                    onPaired = {
                        if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                        backStack.add(AppScreen.CAMERA_MODE)
                    },
                    onBack = { navigateBack() }
                )
            }
            AppScreen.CAMERA_PAIRING -> {
                CameraPairingScreen(
                    onPairingSuccess = {
                        if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                        backStack.add(AppScreen.CAMERA_MODE)
                    },
                    onCancel = { navigateBack() }
                )
            }
            AppScreen.CAMERA_MODE -> {
                CameraModeScreen(
                    onExitCameraMode = { navigateBack() }
                )
            }
            AppScreen.VIEWER_PAIRING -> {
                ViewerPairingScreen(
                    onPairingSuccess = { pairedCamId ->
                        selectedCameraDeviceId = pairedCamId
                        if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                        backStack.add(AppScreen.VIEWER_MODE)
                    },
                    onCancel = { navigateBack() }
                )
            }
            AppScreen.VIEWER_MODE -> {
                ViewerModeScreen(
                    selectedDeviceId = selectedCameraDeviceId,
                    onExitViewerMode = { navigateBack() }
                )
            }
            AppScreen.MANAGE_DEVICES -> {
                ManageDevicesScreen(
                    onPairNewViewer = { navigateTo(AppScreen.CAMERA_PAIRING) },
                    onPairNewCamera = { navigateTo(AppScreen.VIEWER_PAIRING) },
                    onBack = { navigateBack() }
                )
            }
            AppScreen.LOGS -> {
                LogViewerScreen(
                    onBack = { navigateBack() }
                )
            }
            AppScreen.ABOUT -> {
                AboutScreen(
                    onBack = { navigateBack() }
                )
            }
        }
    }
}
