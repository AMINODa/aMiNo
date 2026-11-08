package com.rinigan.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rinigan.app.R
import com.rinigan.app.StreamEngineService
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    onPermissionRequest: () -> Unit
) {
    val mainUiState by mainViewModel.uiState.collectAsState()
    val isWorking = mainUiState.hasPermission && !mainUiState.isModuleDisabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        StatusCard(
            isXposedActive = mainUiState.isXposedActive,
            hasPermission = mainUiState.hasPermission,
            isWorking = isWorking,
            isRandomPlay = mainUiState.enableRandomPlay,
            enableMicHook = mainUiState.enableMicHook,
            micHookMode = mainUiState.micHookMode,
            playVideoSound = mainUiState.playVideoSound,
            notificationControlEnabled = mainUiState.notificationControlEnabled,
            onPermissionRequest = onPermissionRequest
        )

        LinkEnvironmentCard()

        SupportCard()
    }
}

@Composable
fun StatusCard(
    isXposedActive: Boolean,
    hasPermission: Boolean,
    isWorking: Boolean,
    isRandomPlay: Boolean,
    enableMicHook: Boolean,
    micHookMode: String,
    playVideoSound: Boolean,
    notificationControlEnabled: Boolean,
    onPermissionRequest: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val (backgroundColor, textColor, statusText, statusIcon) = when {
        !isXposedActive -> Quadruple(
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
            stringResource(R.string.status_module_inactive),
            Icons.Default.Error
        )
        !hasPermission -> Quadruple(
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
            stringResource(R.string.status_no_permission),
            Icons.Default.Error
        )
        isWorking -> Quadruple(
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
            stringResource(R.string.status_working),
            Icons.Default.CheckCircle
        )
        else -> Quadruple(
            colorScheme.surfaceVariant,
            colorScheme.onSurfaceVariant,
            stringResource(R.string.status_paused),
            Icons.Default.Pause
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(bottom = 16.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!isXposedActive) {
                Text(
                    text = stringResource(R.string.status_activate_hint),
                    fontSize = 14.sp,
                    color = textColor.copy(alpha = 0.8f)
                )
            } else if (!hasPermission) {
                Button(
                    onClick = onPermissionRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.status_grant_permission), fontSize = 14.sp)
                }
            } else {
                Divider(
                    color = textColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                StatusRow(
                    icon = Icons.Default.Videocam,
                    label = stringResource(R.string.status_label_play_mode),
                    value = if (isRandomPlay) stringResource(R.string.status_value_random_play)
                            else stringResource(R.string.status_value_sequential_play),
                    valueIcon = if (isRandomPlay) Icons.Default.Shuffle else null,
                    tint = textColor
                )

                val micStatusValue = if (!enableMicHook) {
                    stringResource(R.string.status_value_off)
                } else {
                    when (micHookMode) {
                        "mute" -> stringResource(R.string.status_value_mic_mute)
                        "replace" -> stringResource(R.string.status_value_mic_replace)
                        "video_sync" -> stringResource(R.string.status_value_mic_sync)
                        else -> stringResource(R.string.status_value_off)
                    }
                }
                StatusRow(
                    icon = if (enableMicHook) Icons.Default.Mic else Icons.Default.MicOff,
                    label = stringResource(R.string.status_label_mic),
                    value = micStatusValue,
                    tint = textColor
                )

                StatusRow(
                    icon = if (playVideoSound) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    label = stringResource(R.string.status_label_video_sound),
                    value = if (playVideoSound) stringResource(R.string.status_value_on)
                            else stringResource(R.string.status_value_off),
                    tint = textColor
                )

                StatusRow(
                    icon = if (notificationControlEnabled) Icons.Default.Notifications
                           else Icons.Default.NotificationsOff,
                    label = stringResource(R.string.status_label_notification),
                    value = if (notificationControlEnabled) stringResource(R.string.status_value_on)
                            else stringResource(R.string.status_value_off),
                    tint = textColor,
                    isLast = true
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueIcon: ImageVector? = null,
    tint: Color,
    isLast: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = tint.copy(alpha = 0.6f),
            modifier = Modifier.widthIn(min = 60.dp, max = 90.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        if (valueIcon != null) {
            Icon(
                imageVector = valueIcon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color =  tint.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
    if (!isLast) {
        Divider(color = tint.copy(alpha = 0.06f), modifier = Modifier.padding(start = 28.dp))
    }
}

@Composable
fun SupportCard() {
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.support_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Rinigan/rinigan"))
                        )
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.support_github),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
// ======================== v6.3: Link Display Environment card ========================

private data class EngineSnapshot(
    val running: Boolean = false,
    val state: String = "",
    val detail: String = "",
    val url: String = "",
    val framesServed: Int = 0,
    val clients: Int = 0,
    val frameW: Int = 0,
    val frameH: Int = 0,
    val lastError: String = "",
    val preview: android.graphics.Bitmap? = null,
    val events: List<String> = emptyList()
)

private fun snapshotEngine(): EngineSnapshot {
    val s = StreamEngineService.status()
    return EngineSnapshot(
        running = s.running,
        state = s.state,
        detail = s.detail,
        url = s.url,
        framesServed = s.framesServed,
        clients = s.clients,
        frameW = s.frameW,
        frameH = s.frameH,
        lastError = s.lastError,
        preview = s.preview,
        events = s.hookEvents.toList()
    )
}

@Composable
fun LinkEnvironmentCard() {
    val context = LocalContext.current
    var snap by remember { mutableStateOf(snapshotEngine()) }

    // live poll — the card is always current (status, preview, hook events)
    LaunchedEffect(Unit) {
        while (true) {
            snap = snapshotEngine()
            delay(700)
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.link_env_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (snap.running) stringResource(R.string.link_env_state_running)
                           else stringResource(R.string.link_env_state_stopped),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (snap.running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.link_env_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                Button(
                    onClick = { StreamEngineService.ensureRunning(context) },
                    enabled = !snap.running,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.link_env_start), fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { StreamEngineService.stopEngine(context) },
                    enabled = snap.running,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.link_env_stop), fontSize = 13.sp)
                }
            }

            if (snap.running) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f))
                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    // live preview of exactly what cameras receive
                    val bmp = snap.preview
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.link_env_preview),
                            modifier = Modifier
                                .width(96.dp)
                                .heightIn(max = 170.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EngineRow(stringResource(R.string.link_env_state),
                            snap.state + (if (snap.detail.isNotEmpty()) " — ${snap.detail}" else ""))
                        if (snap.url.isNotEmpty()) {
                            EngineRow(stringResource(R.string.link_env_url), snap.url)
                        }
                        EngineRow(stringResource(R.string.link_env_frames),
                            "${snap.framesServed}  (${snap.frameW}x${snap.frameH})")
                        EngineRow(stringResource(R.string.link_env_clients),
                            snap.clients.toString())
                        if (snap.lastError.isNotEmpty()) {
                            EngineRow(stringResource(R.string.link_env_last_error), snap.lastError)
                        }
                    }
                }

                if (snap.events.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.link_env_events),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    snap.events.take(4).forEach { event ->
                        Text(
                            text = event,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.link_env_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun EngineRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.width(86.dp)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
