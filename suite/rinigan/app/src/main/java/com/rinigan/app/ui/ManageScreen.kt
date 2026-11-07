@file:OptIn(ExperimentalMaterial3Api::class)
package com.rinigan.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.rinigan.app.R
import java.util.Locale

@Composable
fun ManageScreen(viewModel: MediaViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var showFabMenu by remember { mutableStateOf(false) }
    var showAddLinkDialog by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()

    val videoImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris, false)
    }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris, true)
    }

    Scaffold(
        floatingActionButton = {
            Box {
                DropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false }
                ) {
                    if (selectedTab == 0) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.manage_add_link)) },
                            onClick = {
                                showFabMenu = false
                                showAddLinkDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Link, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.import_to_camera_dir)) },
                            onClick = {
                                showFabMenu = false
                                videoImportLauncher.launch(arrayOf("video/*"))
                            },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.import_to_camera_dir)) },
                            onClick = {
                                showFabMenu = false
                                audioLauncher.launch(arrayOf("audio/*"))
                            },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                    }
                }
                FloatingActionButton(
                    onClick = { showFabMenu = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.desc_add_media))
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                val stats = when (selectedTab) {
                    0 -> String.format(
                        Locale.getDefault(),
                        "%d %s | %.1fMB",
                        uiState.videos.size,
                        stringResource(R.string.tab_video_manage),
                        uiState.totalVideoSizeMb
                    )
                    else -> String.format(
                        Locale.getDefault(),
                        "%d %s",
                        uiState.audios.size,
                        stringResource(R.string.tab_audio_manage)
                    )
                }
                Text(
                    text = stats,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.height(48.dp)) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = stringResource(R.string.tab_video_manage), fontSize = 14.sp)
                    }
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = stringResource(R.string.tab_audio_manage), fontSize = 14.sp)
                    }
                }
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedTab == 0) {
                        items(uiState.videos) { video ->
                            MediaCard(
                                name = video.name,
                                sizeMb = video.size / 1048576.0,
                                durationMs = video.durationMs,
                                // v6.1: live links have no video file — render the link icon
                                videoPath = if (video.sourceUrl != null) null else video.file.absolutePath,
                                isSelected = video.name == uiState.selectedVideoName,
                                isAudio = false,
                                sourceUrl = video.sourceUrl,
                                onSelect = { viewModel.selectVideo(video.name) },
                                onDelete = { viewModel.deleteFile(video.file) }
                            )
                        }
                        if (uiState.videos.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(text = stringResource(R.string.status_no_video), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        items(uiState.audios) { audio ->
                            MediaCard(
                                name = audio.name,
                                sizeMb = audio.size / 1048576.0,
                                durationMs = audio.durationMs,
                                isSelected = audio.name == uiState.selectedAudioName,
                                isAudio = true,
                                onSelect = { viewModel.selectAudio(audio.name) },
                                onDelete = { viewModel.deleteFile(audio.file) }
                            )
                        }
                        if (uiState.audios.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(text = stringResource(R.string.status_no_audio), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddLinkDialog) {
            AddLinkDialog(
                isBusy = uiState.linkDownloading,
                justAdded = !uiState.linkDownloading && uiState.linkDownloadProgress >= 100,
                error = uiState.linkDownloadError,
                onAdd = { url -> viewModel.addVideoFromUrl(url) },
                onDismiss = {
                    showAddLinkDialog = false
                    viewModel.clearLinkError()
                }
            )
        }
    }
}

/**
 * v6.1 — dialog to add a LIVE video link. Nothing is downloaded: the link is
 * stored and becomes the active camera source immediately — the video plays
 * online, in real time, in place of the camera feed.
 */
@Composable
private fun AddLinkDialog(
    isBusy: Boolean,
    justAdded: Boolean,
    error: String?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    val validUrl = remember(url) { MediaViewModel.isValidVideoUrl(url.trim()) }

    // auto-close when the link was added successfully
    LaunchedEffect(justAdded) {
        if (justAdded) {
            kotlinx.coroutines.delay(400)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_dialog_title))
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.link_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = !isBusy,
                    singleLine = true,
                    isError = url.isNotBlank() && !validUrl,
                    label = { Text(stringResource(R.string.link_dialog_hint)) },
                    placeholder = { Text(stringResource(R.string.link_dialog_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (isBusy) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.link_adding),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (error) {
                            "invalid_url" -> stringResource(R.string.link_invalid_url)
                            else -> stringResource(R.string.link_download_failed) + " (" + error + ")"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validUrl && !isBusy,
                onClick = { onAdd(url.trim()) }
            ) { Text(stringResource(R.string.link_dialog_add)) }
        },
        dismissButton = {
            TextButton(
                enabled = !isBusy,
                onClick = onDismiss
            ) { Text(stringResource(R.string.perapp_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaCard(
    name: String,
    sizeMb: Double,
    durationMs: Long,
    isSelected: Boolean,
    isAudio: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    videoPath: String? = null,
    sourceUrl: String? = null
) {
    val context = LocalContext.current
    Card(
        elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface
        ),
        onClick = onSelect
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAudio) {
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    if (videoPath != null) {
                        val request = remember(videoPath) {
                            ImageRequest.Builder(context)
                                .data(videoPath)
                                .videoFrameMillis(1000)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .crossfade(true)
                                .size(112)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (sourceUrl != null) {
                        Icon(
                            Icons.Default.Link,
                            null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.VideoLibrary,
                            null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                if (sourceUrl != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Link,
                            null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = sourceUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    if (durationMs == -1L) {
                        // v6.1: live link — no file size, show LIVE badge
                        Text(text = "LIVE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                    } else {
                    Text(text = String.format(Locale.getDefault(), "%.1f MB", sizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (durationMs > 0) {
                        val s = durationMs / 1000
                        val m = s / 60
                        Text(text = " • ${m}:${String.format("%02d", s % 60)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}