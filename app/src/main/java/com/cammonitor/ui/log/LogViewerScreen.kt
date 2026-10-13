package com.cammonitor.ui.log

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.cammonitor.data.model.LogLevel
import com.cammonitor.log.AppLogger
import com.cammonitor.ui.theme.StatusError
import com.cammonitor.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val logs by AppLogger.logsFlow.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            "INFO" -> logs.filter { it.level == LogLevel.INFO }
            "WARN" -> logs.filter { it.level == LogLevel.WARN }
            "ERROR" -> logs.filter { it.level == LogLevel.ERROR }
            "WebRTC" -> logs.filter { it.tag.contains("WebRtc", ignoreCase = true) }
            "MQTT" -> logs.filter { it.tag.contains("Mqtt", ignoreCase = true) || it.tag.contains("Signaling", ignoreCase = true) }
            else -> logs
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("System Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = AppLogger.getLogFile()
                        if (file != null && file.exists()) {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share CamMonitor Logs"))
                            } catch (e: Exception) {
                                AppLogger.e("LogViewer", "Failed to share log file", e)
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share Logs")
                    }
                    IconButton(onClick = { AppLogger.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf("ALL", "INFO", "WARN", "ERROR", "WebRTC", "MQTT")
                filters.forEach { f ->
                    FilterChip(
                        selected = selectedFilter == f,
                        onClick = { selectedFilter = f },
                        label = { Text(f) }
                    )
                }
            }

            // Log Lines LazyColumn
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredLogs) { entry ->
                    val color = when (entry.level) {
                        LogLevel.ERROR -> StatusError
                        LogLevel.WARN -> StatusWarning
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        LogLevel.DEBUG -> Color.Gray
                    }
                    Text(
                        text = entry.format(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = color,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
