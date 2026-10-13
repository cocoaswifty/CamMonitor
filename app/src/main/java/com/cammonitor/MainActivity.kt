package com.cammonitor

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
import com.cammonitor.ui.camera.CameraModeScreen
import com.cammonitor.ui.devices.ManageDevicesScreen
import com.cammonitor.ui.home.HomeScreen
import com.cammonitor.ui.log.LogViewerScreen
import com.cammonitor.ui.pairing.CameraPairingScreen
import com.cammonitor.ui.pairing.ViewerPairingScreen
import com.cammonitor.ui.theme.CamMonitorTheme
import com.cammonitor.ui.viewer.ViewerModeScreen

enum class AppScreen {
    HOME,
    CAMERA_PAIRING,
    CAMERA_MODE,
    VIEWER_PAIRING,
    VIEWER_MODE,
    MANAGE_DEVICES,
    LOGS
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
        val securityRepo = remember { CamMonitorApp.instance.securityRepository }

        when (currentScreen) {
            AppScreen.HOME -> {
                HomeScreen(
                    onStartCameraMode = {
                        val pairedCount = securityRepo.getPairedDevices().size
                        currentScreen = if (pairedCount == 0) {
                            AppScreen.CAMERA_PAIRING
                        } else {
                            AppScreen.CAMERA_MODE
                        }
                    },
                    onStartViewerMode = {
                        val pairedCount = securityRepo.getPairedDevices().size
                        currentScreen = if (pairedCount == 0) {
                            AppScreen.VIEWER_PAIRING
                        } else {
                            AppScreen.VIEWER_MODE
                        }
                    },
                    onOpenPairedDevices = { currentScreen = AppScreen.MANAGE_DEVICES },
                    onOpenLogs = { currentScreen = AppScreen.LOGS }
                )
            }
            AppScreen.CAMERA_PAIRING -> {
                CameraPairingScreen(
                    onPairingSuccess = { currentScreen = AppScreen.CAMERA_MODE },
                    onCancel = { currentScreen = AppScreen.HOME }
                )
            }
            AppScreen.CAMERA_MODE -> {
                CameraModeScreen(
                    onExitCameraMode = { currentScreen = AppScreen.HOME }
                )
            }
            AppScreen.VIEWER_PAIRING -> {
                ViewerPairingScreen(
                    onPairingSuccess = { currentScreen = AppScreen.VIEWER_MODE },
                    onCancel = { currentScreen = AppScreen.HOME }
                )
            }
            AppScreen.VIEWER_MODE -> {
                ViewerModeScreen(
                    onExitViewerMode = { currentScreen = AppScreen.HOME }
                )
            }
            AppScreen.MANAGE_DEVICES -> {
                ManageDevicesScreen(
                    onPairNewViewer = { currentScreen = AppScreen.CAMERA_PAIRING },
                    onBack = { currentScreen = AppScreen.HOME }
                )
            }
            AppScreen.LOGS -> {
                LogViewerScreen(
                    onBack = { currentScreen = AppScreen.HOME }
                )
            }
        }
    }
}
