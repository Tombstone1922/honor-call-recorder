package com.kitsumed.shizucallrecorder.ui.screens

import android.content.ClipData
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.archive.RecordingArchive
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.system.openShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class Filter(val label: Int) {
    All(R.string.archive_all), Favorites(R.string.archive_favorites), Failures(R.string.archive_failures)
}

@Composable
fun RecordingArchiveScreen(
    status: OnboardingStatus.Status,
    preferences: AppPreferences,
    settingsRevision: Int,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val revision by RecordingArchive.revision.collectAsState()
    val capture by RecordingArchive.capture.collectAsState()
    val activeUri by RecordingArchive.activeUri.collectAsState()
    var refreshed by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<RecordingArchive.Recording?>(null) }
    var deleteTarget by remember { mutableStateOf<RecordingArchive.Recording?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshed++ }

    val folder = preferences.getRecordingFolderUri()
    val files by produceState<Result<List<RecordingArchive.Recording>>?>(null, revision, refreshed, folder) {
        value = withContext(Dispatchers.IO) { runCatching { RecordingArchive.load(context) } }
    }
    val failures = remember(revision, refreshed) { RecordingArchive.failures(context) }
    val automatic = remember(settingsRevision, refreshed) {
        preferences.isAutoRecordIncomingEnabled() || preferences.isAutoRecordOutgoingEnabled()
    }
    val rows = remember(files, filter, query, activeUri) {
        val needle = query.trim()
        val numberNeedle = needle.takeIf { it.isNotBlank() && it.all { ch -> ch.isDigit() || ch in "+ ()-" } }
            ?.filter(Char::isDigit).orEmpty()
        files?.getOrNull().orEmpty().filter { file ->
            file.uri != activeUri && (filter != Filter.Favorites || file.starred) &&
                (needle.isEmpty() || file.title.contains(needle, true) || file.fileName.contains(needle, true) ||
                    (numberNeedle.isNotEmpty() && file.number.filter(Char::isDigit).contains(numberNeedle)))
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.archive_tab), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = { refreshed++ }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.archive_refresh))
                    }
                }
            }
            item { ReadinessCard(status, capture, automatic, preferences.isShizukuAutoManageEnabled(), onSetup) }
            if (filter != Filter.Failures) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.archive_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { option ->
                        FilterChip(filter == option, { filter = option }, { Text(stringResource(option.label)) })
                    }
                }
            }

            when {
                filter == Filter.Failures -> {
                    if (failures.isEmpty()) item { ArchiveMessage(R.string.archive_no_failures) }
                    items(failures) { failure ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.archive_failure_title), style = MaterialTheme.typography.titleMedium)
                                Text(failure.reason, style = MaterialTheme.typography.bodyMedium)
                                Text(formatDate(failure.date), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                files == null -> item { CircularProgressIndicator(Modifier.padding(12.dp)) }
                files?.isFailure == true -> item { ArchiveMessage(R.string.archive_read_error) }
                rows.isEmpty() -> item {
                    ArchiveMessage(when {
                        query.isNotBlank() -> R.string.archive_no_matches
                        filter == Filter.Favorites -> R.string.archive_no_favorites
                        else -> R.string.archive_empty
                    })
                }
                else -> items(rows, key = { it.uri.toString() }) { file ->
                    val date = formatDate(file.date)
                    val direction = when (file.direction) {
                        CallDirection.INCOMING -> stringResource(R.string.archive_incoming)
                        CallDirection.OUTGOING -> stringResource(R.string.archive_outgoing)
                        null -> ""
                    }
                    Card(onClick = { selected = file }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                            headlineContent = {
                                Text(file.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(listOf(direction, date, file.number.takeIf { file.hasContact }.orEmpty())
                                    .filter(String::isNotEmpty).joinToString(" · "))
                            },
                            trailingContent = {
                                IconButton(onClick = { RecordingArchive.setFavorite(context, file.uri, !file.starred) }) {
                                    Icon(
                                        if (file.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        stringResource(if (file.starred) R.string.archive_favorite_remove else R.string.archive_favorite_add),
                                    )
                                }
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    selected?.let { file ->
        PlayerDialog(file, onDismiss = { selected = null }, onDelete = {
            selected = null
            deleteTarget = file
        })
    }
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.archive_delete_title)) },
            text = { Text(file.fileName) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { RecordingArchive.delete(context, file.uri) }.getOrDefault(false)
                        }
                        if (!deleted) snackbar.showSnackbar(context.getString(R.string.archive_delete_error))
                    }
                }) { Text(stringResource(R.string.archive_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun ReadinessCard(
    status: OnboardingStatus.Status,
    capture: RecordingArchive.Capture,
    automatic: Boolean,
    managed: Boolean,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val title = when {
        capture == RecordingArchive.Capture.Starting -> R.string.archive_starting
        capture == RecordingArchive.Capture.Recording -> R.string.archive_recording
        capture == RecordingArchive.Capture.Paused -> R.string.archive_paused
        !status.shizukuRunning -> R.string.archive_shizuku_off
        !status.isComplete() -> R.string.archive_setup_required
        !automatic -> R.string.archive_disabled
        managed -> R.string.archive_shizuku_managed
        else -> R.string.archive_ready
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            when (title) {
                R.string.archive_shizuku_off -> TextButton(onClick = { context.openShizukuManager() }) {
                    Text(stringResource(R.string.archive_start_shizuku))
                }
                R.string.archive_setup_required -> {
                    Text(stringResource(R.string.archive_not_ready), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onSetup) { Text(stringResource(R.string.archive_setup_action)) }
                }
                R.string.archive_ready -> Text(stringResource(R.string.archive_ready_description), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ArchiveMessage(label: Int) {
    Text(stringResource(label), Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun formatDate(time: Long): String {
    if (time <= 0) return stringResource(R.string.archive_info_unknown_date)
    return remember(time) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time)) }
}

@Composable
private fun PlayerDialog(file: RecordingArchive.Recording, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var player by remember(file.uri) { mutableStateOf<MediaPlayer?>(null) }
    var duration by remember(file.uri) { mutableIntStateOf(0) }
    var position by remember(file.uri) { mutableIntStateOf(0) }
    var playing by remember(file.uri) { mutableStateOf(false) }
    var failed by remember(file.uri) { mutableStateOf(false) }

    DisposableEffect(file.uri) {
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setDataSource(context, file.uri)
            media.setOnPreparedListener {
                duration = it.duration.coerceAtLeast(0)
                player = it
            }
            media.setOnCompletionListener {
                position = duration
                playing = false
            }
            media.setOnErrorListener { _, _, _ ->
                player = null
                failed = true
                playing = false
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
        onDispose { media.release() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (playing) runCatching { player?.pause() }
        playing = false
    }
    LaunchedEffect(player, playing) {
        while (playing) {
            player?.let { position = runCatching { it.currentPosition }.getOrDefault(position) }
            delay(250)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(formatDate(file.date))
                when {
                    failed -> ArchiveMessage(R.string.archive_player_error)
                    player == null -> CircularProgressIndicator(Modifier.padding(top = 12.dp))
                    else -> {
                        Slider(
                            value = position.coerceIn(0, duration).toFloat(),
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            onValueChange = { position = it.toInt(); runCatching { player?.seekTo(position) } },
                            enabled = duration > 0,
                        )
                        Text("${formatDuration(position)} / ${formatDuration(duration)}")
                        TextButton(onClick = {
                            val media = player ?: return@TextButton
                            runCatching {
                                if (playing) media.pause() else {
                                    if (position >= duration) {
                                        media.seekTo(0)
                                        position = 0
                                    }
                                    media.start()
                                }
                                playing = !playing
                            }.onFailure { failed = true; playing = false }
                        }) { Text(stringResource(if (playing) R.string.archive_pause else R.string.archive_play)) }
                    }
                }
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = file.mimeType.takeIf { it.startsWith("audio/") } ?: "audio/*"
                        putExtra(Intent.EXTRA_STREAM, file.uri)
                        clipData = ClipData.newRawUri(file.fileName, file.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(share, null))
                    }.onFailure {
                        Toast.makeText(context, R.string.archive_share_error, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.archive_share)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_close)) } },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.archive_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

private fun formatDuration(ms: Int): String =
    String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1_000 % 60)
