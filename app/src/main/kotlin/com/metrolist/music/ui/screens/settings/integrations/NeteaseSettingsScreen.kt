package com.metrolist.music.ui.screens.settings.integrations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.netease.NeteaseSyncLog
import com.metrolist.music.netease.NeteaseSyncMapping
import androidx.compose.material3.IconButton
import com.metrolist.music.viewmodels.NeteaseViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseSettingsScreen(navController: NavController, viewModel: NeteaseViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableStateOf("settings") }
    val user by viewModel.user.collectAsState()
    val binding by viewModel.binding.collectAsState()
    val mappings by viewModel.mappings.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val progress by viewModel.progress.collectAsState()
    BackHandler(page != "settings") { page = "settings" }

    LazyColumn(
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            progress?.let { Text(stringResource(R.string.netease_progress, it.first, it.second)) }
        }
        if (error != null) item {
            Text(stringResource(R.string.netease_error, error.orEmpty()), color = MaterialTheme.colorScheme.error)
            if (error in setOf("NETEASE_LOGIN_REQUIRED", "SOURCE_ACCOUNT_CHANGED")) {
                Text(stringResource(R.string.netease_relogin))
            }
        }
        if (page == "login" && user != null) item {
            Text(stringResource(R.string.netease_logged_in_as, user?.nickname.orEmpty()))
        }
        when (page) {
            "login" -> item { NeteaseLoginScreen(busy, viewModel::login) }
            "pick" -> item { NeteasePlaylistPicker(viewModel, busy) }
            "logs" -> {
                if (logs.isEmpty()) item { Text(stringResource(R.string.netease_no_logs)) }
                items(logs, key = { it.id }) { NeteaseSyncLogScreen(it) }
            }
            "preview" -> {
                item {
                    Text(stringResource(R.string.netease_preview_help))
                    binding?.let { Text(stringResource(R.string.netease_playlist_counts, it.sourceTrackCount, it.targetTrackCount)) }
                    Text(stringResource(R.string.netease_preview_counts,
                        mappings.count { it.status == "MATCHED" },
                        mappings.count { it.status in setOf("SYNCED", "ALREADY_EXISTS") },
                        mappings.count { it.status in setOf("NEEDS_REVIEW", "ADD_UNCONFIRMED", "ADDING") },
                        mappings.count { it.status in setOf("NOT_FOUND", "FAILED") }))
                    Button(onClick = viewModel::sync, enabled = !busy && binding?.previewReady == true && mappings.any { it.status == "MATCHED" }) {
                        Text(stringResource(R.string.netease_start_sync))
                    }
                }
                items(mappings, key = { it.neteaseSongId }) { mapping ->
                    NeteasePreviewRow(mapping, busy) { viewModel.allowRetry(mapping.neteaseSongId) }
                }
            }
            else -> {
                item {
                    Text(user?.nickname ?: stringResource(R.string.netease_not_logged_in), style = MaterialTheme.typography.titleMedium)
                    user?.let { Text(stringResource(R.string.netease_user_id, it.id)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page = "login" }, enabled = !busy) { Text(stringResource(R.string.netease_login)) }
                        if (user != null) TextButton(onClick = viewModel::logout, enabled = !busy) { Text(stringResource(R.string.netease_logout)) }
                    }
                }
                item {
                    NeteaseSyncSettingsScreen(
                        binding?.neteasePlaylistName, binding?.youtubePlaylistName, binding?.lastSyncAt,
                    )
                    OutlinedButton(onClick = { page = "pick" }, enabled = !busy && user != null) {
                        Text(stringResource(R.string.netease_bind))
                    }
                    Button(onClick = { viewModel.preview(); page = "preview" }, enabled = !busy && binding != null && user != null) {
                        Text(stringResource(R.string.netease_preview))
                    }
                    TextButton(onClick = { page = "logs" }) { Text(stringResource(R.string.netease_logs)) }
                }
            }
        }
    }
    TopAppBar(
        title = { Text(stringResource(R.string.netease_title)) },
        navigationIcon = {
            IconButton(onClick = { if (page == "settings") navController.navigateUp() else page = "settings" }) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.netease_back))
            }
        },
    )
}

@Composable
private fun NeteaseLoginScreen(busy: Boolean, login: (String) -> Unit) {
    // Deliberately not rememberSaveable: credentials must never enter saved instance state.
    var cookie by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.netease_cookie_help))
        OutlinedTextField(
            value = cookie, onValueChange = { cookie = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(stringResource(R.string.netease_cookie)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = { login(cookie); cookie = "" }, enabled = !busy && cookie.isNotBlank()) {
            Text(stringResource(R.string.netease_validate_login))
        }
    }
}

@Composable
private fun NeteasePlaylistPicker(viewModel: NeteaseViewModel, busy: Boolean) {
    val sources by viewModel.sources.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val binding by viewModel.binding.collectAsState()
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var targetExpanded by remember { mutableStateOf(false) }
    val source = sources.find { it.id == sourceId }
    val target = targets.find { it.id == targetId }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.netease_picker_help))
        OutlinedButton(onClick = viewModel::loadPlaylists, enabled = !busy) { Text(stringResource(R.string.netease_load_playlists)) }
        Column {
            OutlinedButton(onClick = { sourceExpanded = true }, enabled = !busy && sources.isNotEmpty()) {
                Text(source?.name ?: stringResource(R.string.netease_source))
            }
            DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                sources.forEach { playlist ->
                    DropdownMenuItem(text = { Text("${playlist.name} (${playlist.trackCount})") },
                        onClick = { sourceId = playlist.id; sourceExpanded = false })
                }
            }
        }
        Column {
            OutlinedButton(onClick = { targetExpanded = true }, enabled = !busy && targets.isNotEmpty()) {
                Text(target?.title ?: stringResource(R.string.netease_target))
            }
            DropdownMenu(expanded = targetExpanded, onDismissRequest = { targetExpanded = false }) {
                targets.forEach { playlist ->
                    DropdownMenuItem(text = { Text(playlist.title) }, onClick = { targetId = playlist.id; targetExpanded = false })
                }
            }
        }
        Button(onClick = { if (source != null && target != null) viewModel.bind(source, target) }, enabled = !busy && source != null && target != null) {
            Text(stringResource(R.string.netease_save_binding))
        }
        binding?.let { Text(stringResource(R.string.netease_current_binding, it.neteasePlaylistName, it.youtubePlaylistName)) }
    }
}

@Composable
private fun NeteaseSyncSettingsScreen(source: String?, target: String?, lastSync: Long?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.netease_current_binding, source ?: "—", target ?: "—"))
            Text(stringResource(R.string.netease_add_only))
            Text(stringResource(R.string.netease_manual_mode))
            Text(stringResource(R.string.netease_last_sync, lastSync?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—"))
        }
    }
}

@Composable
private fun NeteasePreviewRow(mapping: NeteaseSyncMapping, busy: Boolean, allowRetry: () -> Unit) {
    var retryDialog by remember { mutableStateOf(false) }
    if (retryDialog) AlertDialog(
        onDismissRequest = { retryDialog = false },
        title = { Text(stringResource(R.string.netease_retry)) },
        text = { Text(stringResource(R.string.netease_retry_help)) },
        confirmButton = {
            TextButton(onClick = { retryDialog = false; allowRetry() }) { Text(stringResource(R.string.netease_retry_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { retryDialog = false }) { Text(stringResource(R.string.netease_back)) }
        },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mapping.neteaseTitle.ifBlank { mapping.neteaseSongId }, style = MaterialTheme.typography.titleMedium)
            Text("${mapping.neteaseArtists} · ${mapping.neteaseAlbum}")
            mapping.durationMs?.let { Text("${it / 60_000}:${(it / 1000 % 60).toString().padStart(2, '0')}") }
            Text("${mapping.status} · ${mapping.matchScore}%")
            mapping.youtubeTitle?.let { Text("$it · ${mapping.youtubeArtists.orEmpty()}") }
            mapping.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (mapping.status in setOf("NEEDS_REVIEW", "ADD_UNCONFIRMED", "ADDING")) {
                Text(stringResource(R.string.netease_review_pending))
            }
            if (mapping.status in setOf("ADD_UNCONFIRMED", "ADDING")) {
                TextButton(onClick = { retryDialog = true }, enabled = !busy) { Text(stringResource(R.string.netease_retry)) }
            }
        }
    }
}

@Composable
private fun NeteaseSyncLogScreen(log: NeteaseSyncLog) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(log.title.ifBlank { log.neteaseSongId }, style = MaterialTheme.typography.titleMedium)
            Text("${log.status} · ${log.matchScore}%")
            Text(DateFormat.getDateTimeInstance().format(Date(log.timestamp)))
            log.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
