package com.a0soft.gphone.camonitor.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.data.security.SecurityRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartCameraMode: () -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val securityRepo = remember { CamMonitorApp.instance.securityRepository }
    var cameraName by remember { mutableStateOf(securityRepo.getDeviceName()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSelectCameraDialog by remember { mutableStateOf(false) }
    var pairedViewers by remember { mutableStateOf(securityRepo.getPairedViewers()) }
    var pairedCameras by remember { mutableStateOf(securityRepo.getPairedCameras()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pairedViewers = securityRepo.getPairedViewers()
                pairedCameras = securityRepo.getPairedCameras()
                cameraName = securityRepo.getDeviceName()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    @Composable
    fun CameraCard(modifier: Modifier = Modifier) {
        ElevatedCard(
            modifier = modifier
                .clickable {
                    val viewers = securityRepo.getPairedViewers()
                    pairedViewers = viewers
                    if (viewers.isEmpty()) {
                        onPairNewViewer()
                    } else {
                        onStartCameraMode()
                    }
                },
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize().padding(16.dp)
                } else {
                    Modifier.fillMaxWidth().padding(24.dp)
                },
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.Top
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start Camera Mode",
                                style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    pairedViewers.isEmpty() -> "No viewers paired (tap to pair)"
                                    pairedViewers.size == 1 -> "Paired with ${pairedViewers.first().name}"
                                    else -> "${pairedViewers.size} viewers paired (broadcasting ready)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))

                    // Camera Name & Rename action
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRenameDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Camera Name: $cameraName",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (pairedViewers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Paired Viewers (${pairedViewers.size}/${SecurityRepository.MAX_PAIRED_VIEWERS})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (pairedViewers.size < SecurityRepository.MAX_PAIRED_VIEWERS) {
                            TextButton(
                                onClick = onPairNewViewer,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Pair Viewer", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ViewerCard(modifier: Modifier = Modifier) {
        ElevatedCard(
            modifier = modifier
                .clickable {
                    val cameras = securityRepo.getPairedCameras()
                    pairedCameras = cameras
                    when {
                        cameras.isEmpty() -> onPairNewCamera()
                        cameras.size == 1 -> onStartViewerMode(cameras.first().deviceId)
                        else -> showSelectCameraDialog = true
                    }
                },
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize().padding(16.dp)
                } else {
                    Modifier.fillMaxWidth().padding(24.dp)
                },
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.Top
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start Viewer Mode",
                                style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    pairedCameras.isEmpty() -> "No paired camera (tap to pair)"
                                    pairedCameras.size == 1 -> "Paired with ${pairedCameras.first().name}"
                                    else -> "${pairedCameras.size} cameras paired (tap to choose)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (pairedCameras.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Paired Cameras (${pairedCameras.size}/${SecurityRepository.MAX_PAIRED_CAMERAS})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (pairedCameras.size < SecurityRepository.MAX_PAIRED_CAMERAS) {
                            TextButton(
                                onClick = onPairNewCamera,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Pair Camera", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("CamMonitor", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Description, contentDescription = "System Logs")
                    }
                    IconButton(onClick = onOpenPairedDevices) {
                        Icon(Icons.Default.Devices, contentDescription = "Paired Devices")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            contentAlignment = Alignment.Center
        ) {
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CameraCard(modifier = Modifier.weight(1f).fillMaxHeight())
                    ViewerCard(modifier = Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CameraCard(modifier = Modifier.fillMaxWidth())
                    ViewerCard(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(cameraName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Camera") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { if (it.length <= 32) tempName = it },
                    label = { Text("Camera Name") },
                    singleLine = true,
                    supportingText = { Text("${tempName.length}/32") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotEmpty()) {
                            securityRepo.setDeviceName(trimmed)
                            cameraName = trimmed
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSelectCameraDialog) {
        AlertDialog(
            onDismissRequest = { showSelectCameraDialog = false },
            title = { Text("Select Camera to View") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Choose which camera stream to view:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pairedCameras) { device ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                showSelectCameraDialog = false
                                                onStartViewerMode(device.deviceId)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = "ID: ${device.deviceId.take(8)}...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Connect",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            securityRepo.removePairedDevice(device.deviceId)
                                            pairedCameras = securityRepo.getPairedCameras()
                                            if (pairedCameras.isEmpty()) {
                                                showSelectCameraDialog = false
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove Camera",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (pairedCameras.size < SecurityRepository.MAX_PAIRED_CAMERAS) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                showSelectCameraDialog = false
                                onPairNewCamera()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pair Another Camera (${pairedCameras.size}/${SecurityRepository.MAX_PAIRED_CAMERAS})")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSelectCameraDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
