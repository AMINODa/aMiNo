package com.rinigan.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rinigan.app.AppProfileManager
import com.rinigan.app.R
import com.rinigan.app.TransformProfile
import org.json.JSONObject

/** UI model for one profile row. */
data class ProfileRow(
    val pkg: String,
    val rotation: Int = 0,
    val scaleMode: String = TransformProfile.SCALE_FIT_CENTER,
    val targetW: Int = 0,
    val targetH: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val normalize: Boolean = false,
    val builtin: Boolean = false
) {
    fun summary(): String {
        val res = if (targetW > 0 && targetH > 0) " $targetW\u00d7$targetH" else ""
        val flips = buildString {
            if (flipH) append(" +H")
            if (flipV) append(" +V")
        }
        return "$rotation\u00b0 \u00b7 $scaleMode$res$flips"
    }
}

/** Serializes the custom profile map ({"pkg": {...}}) for cs_config.json. */
object ProfileJson {
    fun parse(json: String): MutableMap<String, ProfileRow> {
        val out = linkedMapOf<String, ProfileRow>()
        if (json.isBlank()) return out
        return try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                val res = o.optString("targetResolution", "")
                var tw = o.optInt("targetWidth", 0)
                var th = o.optInt("targetHeight", 0)
                if (res.isNotBlank()) {
                    val parts = res.lowercase().split("x", "X", "\u00d7")
                    if (parts.size == 2) {
                        tw = parts[0].trim().toIntOrNull() ?: tw
                        th = parts[1].trim().toIntOrNull() ?: th
                    }
                }
                out[k] = ProfileRow(
                    pkg = k,
                    rotation = o.optInt("rotation", 0),
                    scaleMode = o.optString("scaleMode", TransformProfile.SCALE_FIT_CENTER),
                    targetW = tw, targetH = th,
                    flipH = o.optBoolean("flipHorizontal", false),
                    flipV = o.optBoolean("flipVertical", false),
                    normalize = o.optBoolean("normalizePortrait", false)
                )
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    fun serialize(profiles: Map<String, ProfileRow>): String {
        val root = JSONObject()
        for ((k, p) in profiles) {
            val o = JSONObject()
            o.put("rotation", p.rotation)
            o.put("scaleMode", p.scaleMode)
            if (p.targetW > 0 && p.targetH > 0) o.put("targetResolution", "${p.targetW}x${p.targetH}")
            o.put("flipHorizontal", p.flipH)
            o.put("flipVertical", p.flipV)
            o.put("normalizePortrait", p.normalize)
            root.put(k, o)
        }
        return root.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val customProfiles = remember(uiState.appProfilesJson) {
        ProfileJson.parse(uiState.appProfilesJson)
    }
    val builtinProfiles = remember {
        AppProfileManager.getInstance().getBuiltins().map { (pkg, p) ->
            ProfileRow(pkg, p.rotation, p.scaleMode, p.targetWidth, p.targetHeight,
                p.flipHorizontal, p.flipVertical, p.normalizePortrait, builtin = true)
        }
    }

    var editing by remember { mutableStateOf<ProfileRow?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }

    fun saveProfile(row: ProfileRow) {
        val map = ProfileJson.parse(uiState.appProfilesJson)
        map[row.pkg] = row
        viewModel.setAppProfilesJson(ProfileJson.serialize(map))
    }

    fun deleteProfile(pkg: String) {
        val map = ProfileJson.parse(uiState.appProfilesJson)
        map.remove(pkg)
        viewModel.setAppProfilesJson(ProfileJson.serialize(map))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Master switch ----
        SettingsSection(title = stringResource(R.string.perapp_section_engine)) {
            SettingsSwitchRow(
                icon = Icons.Default.AutoFixHigh,
                title = stringResource(R.string.perapp_engine_enable),
                subtitle = stringResource(R.string.perapp_engine_enable_desc),
                checked = !uiState.perAppEngineDisabled,
                onCheckedChange = { viewModel.setPerAppEngineDisabled(!it) }
            )
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.perapp_universal_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Custom profiles ----
        SettingsSection(title = stringResource(R.string.perapp_section_custom)) {
            if (customProfiles.isEmpty()) {
                Text(
                    stringResource(R.string.perapp_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                customProfiles.values.forEach { row ->
                    ProfileListRow(row, isCustom = true,
                        onEdit = { editing = it }, onDelete = { deleteProfile(it.pkg) })
                    if (row !== customProfiles.values.last()) SettingsDivider()
                }
            }
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.perapp_add))
                }
                TextButton(onClick = { showJsonDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.perapp_json))
                }
            }
        }

        // ---- Built-in presets ----
        SettingsSection(title = stringResource(R.string.perapp_section_builtin)) {
            Text(
                stringResource(R.string.perapp_builtin_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            builtinProfiles.forEachIndexed { idx, row ->
                ProfileListRow(row, isCustom = false, onEdit = { editing = it }, onDelete = null)
                if (idx < builtinProfiles.size - 1) SettingsDivider()
            }
        }
    }

    if (editing != null) {
        ProfileEditorDialog(
            initial = editing!!,
            onDismiss = { editing = null },
            onSave = { saveProfile(it); editing = null }
        )
    }
    if (showAddDialog) {
        AddProfileDialog(
            existing = customProfiles.keys + builtinProfiles.map { it.pkg },
            onDismiss = { showAddDialog = false },
            onAdd = { pkg ->
                showAddDialog = false
                editing = ProfileRow(pkg = pkg, normalize = false)
            }
        )
    }
    if (showJsonDialog) {
        JsonImportDialog(
            currentJson = ProfileJson.serialize(customProfiles),
            onDismiss = { showJsonDialog = false },
            onImport = { json ->
                viewModel.setAppProfilesJson(json)
                showJsonDialog = false
            }
        )
    }
}

@Composable
private fun ProfileListRow(row: ProfileRow, isCustom: Boolean,
                           onEdit: (ProfileRow) -> Unit,
                           onDelete: ((ProfileRow) -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.pkg, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(if (isCustom) R.string.perapp_badge_custom
                            else R.string.perapp_badge_builtin),
                            fontSize = 10.sp
                        )
                    },
                    modifier = Modifier.height(22.dp)
                )
            }
            Text(row.summary(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onEdit(row) }) {
            Text(stringResource(R.string.perapp_edit))
        }
        if (isCustom && onDelete != null) {
            IconButton(onClick = { onDelete(row) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.perapp_delete),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorDialog(initial: ProfileRow,
                                onDismiss: () -> Unit,
                                onSave: (ProfileRow) -> Unit) {
    var rotation by remember { mutableStateOf(initial.rotation) }
    var scaleMode by remember { mutableStateOf(initial.scaleMode) }
    var resText by remember {
        mutableStateOf(if (initial.targetW > 0 && initial.targetH > 0)
            "${initial.targetW}x${initial.targetH}" else "")
    }
    var flipH by remember { mutableStateOf(initial.flipH) }
    var flipV by remember { mutableStateOf(initial.flipV) }
    var normalize by remember { mutableStateOf(initial.normalize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(initial.pkg, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // rotation
                Text(stringResource(R.string.perapp_rotation),
                    style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 90, 180, 270).forEach { deg ->
                        FilterChip(
                            selected = rotation == deg,
                            onClick = { rotation = deg },
                            label = { Text("$deg\u00b0") }
                        )
                    }
                }
                // scale mode
                Text(stringResource(R.string.perapp_scale_mode),
                    style = MaterialTheme.typography.labelMedium)
                listOf(
                    TransformProfile.SCALE_FIT_CENTER to R.string.perapp_scale_fit,
                    TransformProfile.SCALE_CENTER_CROP to R.string.perapp_scale_crop,
                    TransformProfile.SCALE_STRETCH to R.string.perapp_scale_stretch
                ).forEach { (mode, labelRes) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = scaleMode == mode,
                            onClick = { scaleMode = mode })
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(labelRes),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                // target resolution
                OutlinedTextField(
                    value = resText,
                    onValueChange = { resText = it },
                    label = { Text(stringResource(R.string.perapp_resolution)) },
                    placeholder = { Text(stringResource(R.string.perapp_resolution_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                // flips
                LabeledSwitch(stringResource(R.string.perapp_flip_h), flipH) { flipH = it }
                LabeledSwitch(stringResource(R.string.perapp_flip_v), flipV) { flipV = it }
                LabeledSwitch(stringResource(R.string.perapp_normalize), normalize) { normalize = it }
                Text(stringResource(R.string.perapp_normalize_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var tw = 0
                var th = 0
                val cleaned = resText.trim().lowercase().replace("\u00d7", "x")
                val parts = cleaned.split("x")
                if (parts.size == 2) {
                    tw = parts[0].trim().toIntOrNull() ?: 0
                    th = parts[1].trim().toIntOrNull() ?: 0
                }
                onSave(initial.copy(
                    rotation = rotation, scaleMode = scaleMode,
                    targetW = tw.coerceAtLeast(0), targetH = th.coerceAtLeast(0),
                    flipH = flipH, flipV = flipV, normalize = normalize
                ))
            }) { Text(stringResource(R.string.perapp_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.perapp_cancel)) }
        }
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProfileDialog(existing: Set<String>,
                             onDismiss: () -> Unit,
                             onAdd: (String) -> Unit) {
    var pkg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perapp_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it; error = false },
                    label = { Text(stringResource(R.string.perapp_package_hint)) },
                    isError = error,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text(stringResource(R.string.perapp_package_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.perapp_prefix_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = pkg.trim()
                if (trimmed.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
                    onAdd(trimmed)
                } else {
                    error = true
                }
            }) { Text(stringResource(R.string.perapp_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.perapp_cancel)) }
        }
    )
}

@Composable
private fun JsonImportDialog(currentJson: String,
                             onDismiss: () -> Unit,
                             onImport: (String) -> Unit) {
    var text by remember { mutableStateOf(currentJson) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perapp_json)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = false },
                    isError = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                if (error) {
                    Text(stringResource(R.string.perapp_json_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = try {
                    if (text.isBlank()) true else JSONObject(text) is JSONObject
                } catch (e: Exception) {
                    false
                }
                if (ok) onImport(text.trim()) else error = true
            }) { Text(stringResource(R.string.perapp_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.perapp_cancel)) }
        }
    )
}
