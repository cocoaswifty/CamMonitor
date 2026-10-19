package com.a0soft.gphone.camonitor.ui.smart

import android.content.Intent
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
import androidx.core.content.FileProvider
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.camera.SharedEventRecorder
import com.a0soft.gphone.camonitor.data.camera.SmartEventSettings
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Settings and saved clips for the ONE shared camera pipeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRecordingScreen(onBack: () -> Unit, onStartCameraMode: () -> Unit) {
    val context = LocalContext.current
    val recordingState by SharedEventRecorder.state.collectAsState()
    val latestVideo by SharedEventRecorder.latestClip.collectAsState()
    val error by SharedEventRecorder.errorMessage.collectAsState()
    var enabled by remember { mutableStateOf(SmartEventSettings.isEnabled(context)) }
    var refresh by remember { mutableIntStateOf(0) }

    val files = remember(refresh, latestVideo) {
        SharedEventRecorder.clipDirectory(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
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
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.smart_record_shared_info),
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.smart_record_enable),
                    style = MaterialTheme.typography.titleMedium)
                Switch(enabled, onCheckedChange = {
                    enabled = it
                    SmartEventSettings.setEnabled(context, it)
                })
            }
            Text(
                stringResource(
                    if (enabled) R.string.smart_record_shared_enabled
                    else R.string.smart_record_shared_disabled
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onStartCameraMode) {
                Text(stringResource(R.string.smart_record_start_shared_monitor))
            }
            val status = when (recordingState) {
                SharedEventRecorder.State.OFF -> stringResource(R.string.smart_record_off)
                SharedEventRecorder.State.SCANNING -> stringResource(R.string.smart_record_scanning)
                SharedEventRecorder.State.RECORDING -> stringResource(R.string.smart_record_recording)
                SharedEventRecorder.State.ERROR -> stringResource(R.string.smart_record_error)
            }
            Text(status)
            if (error != null) Text(error.orEmpty(),
                color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.smart_record_local_only),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.smart_record_clips),
                    style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { refresh++ }) {
                    Text(stringResource(R.string.smart_record_refresh))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodySmall)
                                Text(DateFormat.getDateTimeInstance().format(Date(file.lastModified())),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(contentUri(file), "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(
                                    intent, context.getString(R.string.smart_record_play)
                                ))
                            }) {
                                Icon(Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.smart_record_play))
                            }
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, contentUri(file))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(
                                    intent, context.getString(R.string.smart_record_share)
                                ))
                            }) {
                                Icon(Icons.Default.Share,
                                    contentDescription = stringResource(R.string.smart_record_share))
                            }
                        }
                    }
                }
            }
        }
    }
}
