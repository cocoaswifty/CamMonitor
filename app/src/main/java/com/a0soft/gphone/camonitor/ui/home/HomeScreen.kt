package com.a0soft.gphone.camonitor.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.CamMonitorApp

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Camera Mode Card
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
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
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start Camera Mode",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
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

                    Spacer(modifier = Modifier.height(16.dp))

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

                    if (pairedViewers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
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

            Spacer(modifier = Modifier.height(24.dp))

            // Viewer Mode Card
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
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
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start Viewer Mode",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
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

                    if (pairedCameras.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Paired Cameras (${pairedCameras.size}/${SecurityRepository.MAX_PAIRED_CAMERAS})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
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
