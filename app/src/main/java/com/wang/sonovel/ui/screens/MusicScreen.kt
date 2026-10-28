package com.wang.sonovel.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.wang.sonovel.graph
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wang.sonovel.music.*
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.delay

@Composable
fun MusicScreen(vm: MusicViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tasks by vm.downloads.tasks.collectAsStateWithLifecycle()
    val snack = rememberSnack()
    val keyboard = LocalSoftwareKeyboardController.current
    val requestNotifications = rememberMusicNotifications()
    var downloadsTab by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var deleting by remember { mutableStateOf<MusicDownload?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snack(it); vm.clearMessage() } }
    LaunchedEffect(Unit) { while (true) { vm.downloads.refresh(); delay(1500) } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("音乐", style = MaterialTheme.typography.headlineMedium)
                Text("听见喜欢，也留在身边", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SuggestionChip(onClick = {}, label = { Text("已启用音源") }, icon = { Icon(Icons.Outlined.GraphicEq, null, Modifier.size(16.dp)) })
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !downloadsTab, onClick = { downloadsTab = false }, label = { Text("找音乐") })
            FilterChip(selected = downloadsTab, onClick = { downloadsTab = true }, label = { Text("下载 · ${tasks.size}") })
        }
        if (downloadsTab) {
            if (tasks.isEmpty()) MusicEmpty("还没有下载音乐", "搜索歌曲后选择音质，保存下来离线听。")
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("文件保存在应用音乐目录；卸载应用会一并移除。可用“分享”导出留存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(tasks, key = { it.systemId }) { task ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(task.song.title, style = MaterialTheme.typography.titleMedium)
                            Text("${task.song.artist} · ${task.quality.label}", style = MaterialTheme.typography.bodySmall)
                            if (task.active) {
                                if (task.total > 0) LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(task.reason.ifBlank { if (task.status == android.app.DownloadManager.STATUS_PENDING) "等待系统开始下载" else "下载中 · ${task.bytes / 1024} KB" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { vm.downloads.cancel(task.systemId) }) { Text("取消") }
                                }
                            } else if (task.complete) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("已下载", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                                    TextButton(onClick = { requestNotifications { vm.playLocal(task) } }, enabled = state.busy == null) { Text("播放") }
                                    TextButton(onClick = { vm.share(task) }, enabled = state.busy == null) { Text("分享") }
                                }
                            } else {
                                Text(task.reason.ifBlank { "下载失败" }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { requestNotifications { vm.retry(task) } }, enabled = state.busy == null) { Text("重新下载") }
                            }
                            if (!task.active) TextButton(onClick = { deleting = task }) { Text("移除") }
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = state.query, onValueChange = vm::query,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text("搜索歌名、歌手或专辑") }, singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { keyboard?.hide(); vm.search() }, enabled = state.query.isNotBlank()) { Icon(Icons.Outlined.ArrowForward, "搜索音乐") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
            )
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            if (state.busy != null) Text("正在获取音频地址…", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            state.error?.let { error ->
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.search() }) { Text("重试") }
                }
            }
            if (state.songs.isEmpty() && !state.searching && state.error == null) {
                MusicEmpty(if (state.searched.isBlank()) "一首歌，一段阅读时光" else "没有找到相关歌曲",
                    if (state.searched.isBlank()) "搜索喜欢的音乐，选择音质后播放或下载。" else "换一个歌名或歌手试试。")
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.songs.isNotEmpty()) item { Text("已加载 ${state.songs.size} 首 · 已启用音源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.songs, key = { it.source + it.id }) { song ->
                    Card(onClick = { selected = song }, enabled = state.busy == null) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.MusicNote, null)
                                if (song.cover.isNotBlank()) AsyncImage(song.cover, null, Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(song.artist.ifBlank { "未知歌手" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(song.album.ifBlank { "${song.duration / 60}:${(song.duration % 60).toString().padStart(2, '0')}" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.PlayCircle, "选择音质和操作", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (state.hasMore) item { TextButton(onClick = { vm.search(more = true) }, enabled = !state.searching, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
            }
        }
    }
    selected?.let { song ->
        var quality by remember(song.id) { mutableStateOf(song.qualities.firstOrNull { it.format == "mp3" && it.bitrate == 128 } ?: song.qualities.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(song.title) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    if (song.qualities.isEmpty()) Text("音乐源未提供可用音质")
                    song.qualities.sortedByDescending { it.bitrate }.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option == quality, onClick = { quality = option })
                            TextButton(onClick = { quality = option }) { Text("${option.label}  ${option.size}") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { quality?.let { q -> requestNotifications { vm.act(song, q, true) } }; selected = null }, enabled = quality != null && state.busy == null) { Text("下载") } },
            dismissButton = { TextButton(onClick = { quality?.let { q -> requestNotifications { vm.act(song, q, false) } }; selected = null }, enabled = quality != null && state.busy == null) { Text("播放") } },
        )
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除此下载？") },
            text = { Text("将移除《${task.song.title}》的下载记录和应用内文件，已分享导出的文件不受影响。") },
            confirmButton = { TextButton(onClick = { runCatching { vm.downloads.remove(task.systemId) }.onFailure { snack(it.message ?: "移除失败") }; deleting = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}

@Composable
internal fun rememberMusicNotifications(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return { action ->
        if (!requested && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requested = true
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
}

@Composable
private fun ColumnScope.MusicEmpty(title: String, subtitle: String) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Headphones, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MusicPlayerBar(navigationPadding: Boolean = false) {
    val state by MusicPlaybackService.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openSong = LocalOpenSong.current
    val snack = rememberSnack()
    LaunchedEffect(state.error) { state.error?.let { snack(it) } }
    if (state.title.isBlank()) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer,modifier=if(navigationPadding) Modifier.navigationBarsPadding() else Modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable { context.graph.media.song(state.songKey)?.let(openSong) }.padding(vertical = 10.dp)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(if (state.loading) "载入中…" else state.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { MusicPlaybackService.toggle(context) }, enabled = !state.loading) { Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "暂停" else "继续播放") }
            IconButton(onClick = { MusicPlaybackService.stop(context) }) { Icon(Icons.Outlined.Close, "停止播放") }
        }
    }
}
