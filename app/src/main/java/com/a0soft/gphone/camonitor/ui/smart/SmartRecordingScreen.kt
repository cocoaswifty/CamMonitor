package com.a0soft.gphone.camonitor.ui.smart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.service.SmartRecordingService
import java.io.File
import java.text.DateFormat
import java.util.Date

/** User-initiated smart recording and locally stored video review. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRecordingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val phase by SmartRecordingService.phase.collectAsState()
    val latestPath by SmartRecordingService.lastVideoPath.collectAsState()
    val error by SmartRecordingService.lastError.collectAsState()
    val running = phase != SmartRecordingService.Phase.OFF
    var refresh by remember { mutableIntStateOf(0) }

    val videoFiles = remember(refresh, latestPath) {
        SmartRecordingService.storageDirectory(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun contentUri(file: File) = FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", file
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.smart_record_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )
    }) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.smart_record_info), style = MaterialTheme.typography.bodyMedium)
            val phaseLabel = when (phase) {
                SmartRecordingService.Phase.OFF -> stringResource(R.string.smart_record_off)
                SmartRecordingService.Phase.PROBING -> stringResource(R.string.smart_record_scanning)
                SmartRecordingService.Phase.RECORDING -> stringResource(R.string.smart_record_recording)
                SmartRecordingService.Phase.SLEEPING -> stringResource(R.string.smart_record_sleep)
                SmartRecordingService.Phase.ERROR -> stringResource(R.string.smart_record_error)
            }
            Text(phaseLabel, style = MaterialTheme.typography.titleMedium)
            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = {
                if (running) {
                    context.startService(
                        Intent(context, SmartRecordingService::class.java).apply {
                            action = SmartRecordingService.ACTION_STOP
                        }
                    )
                } else if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED) {
                    ContextCompat.startForegroundService(
                        context, Intent(context, SmartRecordingService::class.java)
                    )
                } else {
                    SmartRecordingService.lastError.value =
                        context.getString(R.string.smart_record_camera_permission)
                }
            }) {
                Text(stringResource(
                    if (running) R.string.smart_record_stop else R.string.smart_record_start
                ))
            }

            Text(stringResource(R.string.smart_record_local_only), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.smart_record_clips), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { refresh++ }) {
                    Text(stringResource(R.string.smart_record_refresh))
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(videoFiles, key = { it.absolutePath }) { file ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    DateFormat.getDateTimeInstance().format(Date(file.lastModified())),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(contentUri(file), "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.smart_record_play))
                                )
                            }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.smart_record_play)
                                )
                            }
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, contentUri(file))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.smart_record_share))
                                )
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.smart_record_share)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
