package com.wang.sonovel.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.graph
import com.wang.sonovel.data.SearchResult
import com.wang.sonovel.data.LocalBook
import com.wang.sonovel.music.*
import com.wang.sonovel.ui.rememberSnack
import com.wang.sonovel.ui.components.formatSize
import kotlinx.coroutines.*
import java.io.File

@Composable
fun CollectionScreen(onRead:(File)->Unit,onBook:(SearchResult)->Unit) {
    val context=LocalContext.current;val g=context.graph;val snack=rememberSnack();val scope=rememberCoroutineScope()
    val saved by g.media.saved.collectAsStateWithLifecycle()
    val books by g.library.books.collectAsStateWithLifecycle()
    val tracks by g.musicDownloads.tasks.collectAsStateWithLifecycle()
    val progressVersion by g.progress.version.collectAsStateWithLifecycle()
    var filter by rememberSaveable {mutableStateOf("全部")}
    var deleting by remember {mutableStateOf<LocalBook?>(null)}
    var importing by remember {mutableStateOf(false)}
    val openSong=LocalOpenSong.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) scope.launch {
            importing=true
            val results=withContext(Dispatchers.IO) {uris.map {runCatching {g.library.importBook(it)}}}
            importing=false
            snack("已导入 "+results.count {it.isSuccess}+" 本"+(results.firstOrNull {it.isFailure}?.exceptionOrNull()?.message?.let {"；"+it} ?: ""))
        }
    }
    LaunchedEffect(Unit) {withContext(Dispatchers.IO){g.library.refresh();g.library.fillMissingCovers()};g.musicDownloads.refresh()}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        MediaHeader("收藏","喜欢的故事与音乐，留在身边") {TextButton({picker.launch(arrayOf("*/*"))},enabled=!importing){Text(if(importing) "导入中" else "导入书籍")}}
        MediaFilters(filter,listOf("全部","小说","音乐","本地")) {filter=it}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)) {
            if(filter!="本地") {
                if(filter!="音乐") items(saved.books,key={"saved:"+it.sourceKey+it.url}) { book ->
                    MediaRow(book.bookName,book.author.orEmpty(),"已收藏 · "+book.sourceName,icon=Icons.AutoMirrored.Outlined.MenuBook,onClick={onBook(book)},trailing={
                        IconButton({g.media.toggle(book)}){Icon(Icons.Outlined.BookmarkRemove,"取消收藏")}
                    })
                }
                if(filter!="小说") items(saved.songs,key={"saved:"+it.source+it.id}) { song -> SongRow(song){openSong(song)} }
            }
            if(filter!="音乐" && books.isNotEmpty()) {
                item {Text("本地书籍",Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.titleMedium)}
                items(books,key={"local:"+it.file.absolutePath}) { book ->
                    progressVersion
                    BookRow(book,g.progress.get(book.file)?.percent ?: 0,onOpen={onRead(book.file)},
                        onOpenExternal={runCatching {context.startActivity(g.library.openIntent(book.file))}.onFailure{snack("未找到可用阅读应用")}},
                        onShare={runCatching {context.startActivity(Intent.createChooser(g.library.shareIntent(book.file),"分享书籍"))}.onFailure{snack("无法分享")}},
                        onExport={scope.launch{snack(withContext(Dispatchers.IO){runCatching{"已保存至 "+g.library.copyToPublic(book.file)}.getOrElse{it.message.orEmpty()}})}},
                        onDelete={deleting=book})
                }
            }
            val local=tracks.filter{it.complete}
            if(filter!="小说" && local.isNotEmpty()) {
                item {Text("离线音乐",Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.titleMedium)}
                items(local,key={"local:"+it.systemId}){task->MediaRow(task.song.title,task.song.artist,"已下载 · "+task.quality.label,task.song.cover,onClick={openSong(task.song)})}
            }
            val visibleSaved=if(filter=="本地") 0 else (if(filter!="音乐") saved.books.size else 0)+(if(filter!="小说") saved.songs.size else 0)
            if(visibleSaved+(if(filter!="音乐") books.size else 0)+(if(filter!="小说") local.size else 0)==0) item {
                MediaEmpty("把喜欢的留在这里","在发现页收藏小说或歌曲，下载完成的内容也会出现在这里。",Icons.Outlined.Bookmarks)
            }
        }
    }
    deleting?.let{book->AlertDialog(onDismissRequest={deleting=null},title={Text("删除本地书籍？")},text={Text(book.bookName)},confirmButton={TextButton({scope.launch{withContext(Dispatchers.IO){g.library.delete(book)};deleting=null}}){Text("删除")}},dismissButton={TextButton({deleting=null}){Text("保留")}})}
}

@Composable
fun DownloadsScreen(onRead:(File)->Unit,vm:MusicViewModel=viewModel()) {
    val context=LocalContext.current; val g=context.graph; val snack=rememberSnack()
    val books by g.downloads.tasks.collectAsStateWithLifecycle()
    val songs by g.musicDownloads.tasks.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable {mutableStateOf("全部")}
    var removing by remember {mutableStateOf<MusicDownload?>(null)}
    val requestPermission=rememberMusicNotifications()
    val openSong=LocalOpenSong.current
    LaunchedEffect(Unit){while(isActive){g.musicDownloads.refresh();delay(1200)}}
    LaunchedEffect(state.message){state.message?.let{snack(it);vm.clearMessage()}}
    val active=books.count{it.status.active}+songs.count{it.active}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)){
        MediaHeader("下载",if(active>0) "$active 项正在处理" else "阅读与聆听，都可以离线")
        MediaFilters(filter,listOf("全部","进行中","小说","音乐")){filter=it}
        val bookList=books.filter{filter!="音乐" && (filter!="进行中" || it.status.active)}
        val songList=songs.filter{filter!="小说" && (filter!="进行中" || it.active)}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)){
            if(bookList.isEmpty() && songList.isEmpty()) item {MediaEmpty("暂时没有下载任务","在小说或歌曲详情中选择下载，进度会统一显示在这里。",Icons.Outlined.Download)}
            items(bookList.asReversed(),key={"book:"+it.id}){task->TaskCard(task,{g.downloads.cancel(task.id)},{g.downloads.retry(task.id)},{g.downloads.remove(task.id)},{task.output?.let(onRead)},{g.downloads.exportDownloaded(task.id)})}
            items(songList,key={"song:"+it.systemId}){task->
                Card(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text(task.song.title,style=MaterialTheme.typography.titleMedium)
                                Text(task.song.artist+" · "+sourceName(task.song.source),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({openSong(task.song)}){Icon(Icons.Outlined.ChevronRight,"查看歌曲")}
                        }
                        Text(task.quality.label+" · "+formatSize(task.bytes),style=MaterialTheme.typography.labelMedium)
                        if(task.fileName.substringAfterLast('.')!=task.quality.format) Text("音源返回格式："+task.fileName.substringAfterLast('.').uppercase(),style=MaterialTheme.typography.bodySmall)
                        if(task.active){
                            if(task.total>0) LinearProgressIndicator(progress={(task.bytes.toFloat()/task.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(task.reason.ifBlank{"等待系统下载或正在下载"},style=MaterialTheme.typography.bodySmall)
                        }else Text(if(task.complete) "已完成 · 可离线播放" else task.reason.ifBlank{"下载失败"},style=MaterialTheme.typography.bodySmall,color=if(task.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if(task.complete && (task.embedLyrics || task.embedCover)) {
                            Text(task.tagMessage ?: "等待写入歌词与封面…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(task.tagState in listOf("partial","failed")) TextButton({g.musicDownloads.retryTags(task.systemId)}) { Text("重试补全") }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                            if(task.active) TextButton({g.musicDownloads.cancel(task.systemId)}){Text("取消")}
                            else {
                                if(task.complete){
                                    TextButton({requestPermission{vm.playLocal(task)}},enabled=state.busy==null){Text("播放")}
                                    TextButton({vm.share(task)}){Text("分享")}
                                }else TextButton({requestPermission{vm.retry(task)}},enabled=state.busy==null){Text("重试")}
                                TextButton({removing=task}){Text("移除")}
                            }
                        }
                    }
                }
            }
        }
    }
    removing?.let{task->AlertDialog(onDismissRequest={removing=null},title={Text("移除此下载？")},text={Text("将移除下载记录及应用内的音频文件。")},confirmButton={TextButton({runCatching{g.musicDownloads.remove(task.systemId)}.onFailure{snack(it.message.orEmpty())};removing=null}){Text("移除")}},dismissButton={TextButton({removing=null}){Text("保留")}})}
}
