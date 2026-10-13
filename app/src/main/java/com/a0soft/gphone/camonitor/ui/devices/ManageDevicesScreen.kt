package com.a0soft.gphone.camonitor.ui.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageDevicesScreen(
    onPairNewViewer: () -> Unit,
    onBack: () -> Unit
) {
    val securityRepo = remember { CamMonitorApp.instance.securityRepository }
    var pairedList by remember { mutableStateOf(securityRepo.getPairedDevices()) }
    var deviceToRevoke by remember { mutableStateOf<PairedDevice?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Paired Devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (pairedList.size < 2) {
                ExtendedFloatingActionButton(
                    onClick = onPairNewViewer,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Pair New Viewer (${pairedList.size}/2)") }
                )
            }
        }
    ) { innerPadding ->
        if (pairedList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No paired devices yet.\nTap 'Start Camera Mode' or '+' to pair a viewer device.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pairedList) { device ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                    .format(Date(device.pairedTimestamp))
                                Text(
                                    text = "Paired on $dateStr\nID: ${device.deviceId.take(8)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deviceToRevoke = device }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Revoke",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (deviceToRevoke != null) {
        val target = deviceToRevoke!!
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = { Text("Revoke Viewer Access?") },
            text = {
                Text("Are you sure you want to revoke '${target.name}'? It will immediately lose streaming access.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        securityRepo.removePairedDevice(target.deviceId)
                        CamMonitorApp.instance.webRtcManager.closePeer(target.deviceId)
                        pairedList = securityRepo.getPairedDevices()
                        deviceToRevoke = null
                    }
                ) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRevoke = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
