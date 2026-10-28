package com.wang.sonovel.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wang.sonovel.graph
import com.wang.sonovel.data.SearchResult
import com.wang.sonovel.music.*
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.*
import java.io.File

val LocalOpenSong = staticCompositionLocalOf<(Song) -> Unit> { {} }

@Composable
fun MediaHeader(title: String, subtitle: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.headlineMedium)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
        }
        action()
    }
}

@Composable
fun MediaFilters(selected: String, values: List<String>, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected==value,{onSelect(value)},label={Text(value)}) }
    }
}

@Composable
fun MediaRow(title: String, subtitle: String, label: String, cover: String="", icon: ImageVector=Icons.Outlined.Headphones, onClick: () -> Unit, trailing: @Composable (() -> Unit)?=null) {
    Surface(onClick=onClick,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp)) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(58.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center) {
                if(cover.isBlank()) Icon(icon,null,tint=MaterialTheme.colorScheme.onSecondaryContainer)
                else AsyncImage(cover,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            }
            if(trailing!=null) trailing() else Icon(Icons.Outlined.ChevronRight,"查看详情",tint=MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun SongRow(song: Song,onClick: () -> Unit) = MediaRow(song.title,song.artist,sourceName(song.source)+" · "+timeLabel(song.duration),song.cover,onClick=onClick)
fun sourceName(source: String)=if(source=="bilibili") "B 站音轨" else "第三方音源"
fun timeLabel(seconds: Int)=(seconds/60).toString()+":"+(seconds%60).toString().padStart(2,'0')

@Composable
fun MediaEmpty(title: String, detail: String, icon: ImageVector=Icons.Outlined.AutoStories) {
    Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.secondaryContainer) { Icon(icon,null,Modifier.padding(22.dp).size(36.dp),tint=MaterialTheme.colorScheme.primary) }
        Text(title,style=MaterialTheme.typography.titleLarge)
        Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DiscoverScreen(onOpenBook: (SearchResult)->Unit,onBatch:()->Unit,onLink:()->Unit,bookVm:SearchViewModel=viewModel(),musicVm:MusicViewModel=viewModel()) {
    val context=LocalContext.current; val g=context.graph
    val appSettings by g.settings.state.collectAsStateWithLifecycle()
    val books by bookVm.state.collectAsStateWithLifecycle()
    val music by musicVm.state.collectAsStateWithLifecycle()
    val saved by g.media.saved.collectAsStateWithLifecycle()
    val history by g.history.items.collectAsStateWithLifecycle()
    val sources by g.musicSources.settings.collectAsStateWithLifecycle()
    val files by g.rules.files.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var musicSource by rememberSaveable { mutableStateOf("全部音源") }
    val keyboard=LocalSoftwareKeyboardController.current
    val openSong=LocalOpenSong.current
    fun search(value: String=query) {
        val word=value.trim(); if(word.isBlank()) return
        query=word; keyboard?.hide(); g.history.add(word)
        // Both domains retain results so switching filters never loses a query.
        if (Regex("BV[0-9A-Za-z]{10}").containsMatchIn(word)) {
            bookVm.cancel();bookVm.state.value=SearchUiState(keyword=word);filter="音乐";musicSource="B 站"
        } else bookVm.search(word)
        musicVm.query(word); musicVm.search()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        MediaHeader("音阅","读一段故事，听一首歌") {
            IconButton(onLink) { Icon(Icons.Outlined.Link,"打开书籍链接") }
            IconButton(onBatch) { Icon(Icons.Outlined.PlaylistAdd,"批量下载小说") }
        }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=20.dp),singleLine=true,
            shape=MaterialTheme.shapes.extraLarge,placeholder={Text("书名、歌名、作者或 BV 号")},
            leadingIcon={Icon(Icons.Outlined.Search,null)},
            trailingIcon={IconButton({search()}) { Icon(Icons.Outlined.ArrowForward,"搜索") }},
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}))
        MediaFilters(filter,listOf("全部","小说","音乐")) { filter=it }
        val searching=(filter!="音乐" && books.searching)||(filter!="小说" && music.searching)
        if(searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)) {
            if(books.keyword==null && music.searched.isBlank()) {
                if (!appSettings.homeIntroDismissed) item {
                    Surface(Modifier.padding(20.dp).fillMaxWidth(),shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Outlined.GraphicEq,null,Modifier.size(32.dp))
                                Spacer(Modifier.weight(1f))
                                IconButton({ g.settings.update { it.copy(homeIntroDismissed=true) } }) {
                                    Icon(Icons.Outlined.Close,"关闭首页介绍")
                                }
                            }
                            Text("故事与旋律\n都在这里",style=MaterialTheme.typography.headlineMedium)
                            Text("一次搜索，自由切换阅读与聆听。\n收藏喜欢的内容，下载后随时继续。",style=MaterialTheme.typography.bodyMedium)
                            Text(g.rules.searchableRules().size.toString()+" 个书源 · "+g.musicSources.enabled().size+" 个音乐来源",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if(history.isNotEmpty()) {
                    item { Text("最近搜索",Modifier.padding(horizontal=24.dp,vertical=8.dp),style=MaterialTheme.typography.titleMedium) }
                    items(history.take(8)) { word -> ListItem(headlineContent={Text(word)},leadingContent={Icon(Icons.Outlined.History,null)},trailingContent={Icon(Icons.Outlined.NorthWest,null)},modifier=Modifier.clickable { search(word) }) }
                }
            } else {
                if(filter!="小说") {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("音乐 · "+music.songs.size,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            if(filter=="全部") TextButton({filter="音乐"}) {Text("查看全部")}
                        }
                    }
                    if(filter=="音乐") item {MediaFilters(musicSource,listOf("全部音源","B 站")){musicSource=it}}
                    music.error?.let { error -> item { Text(error,Modifier.padding(horizontal=24.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) } }
                    val visibleMusic=music.songs.filter { filter=="全部" || musicSource=="全部音源" || it.source=="bilibili" }.let {if(filter=="全部") it.take(3) else it}
                    items(visibleMusic,key={"music:"+it.source+":"+it.id}) { song -> SongRow(song) { openSong(song) } }
                    if(music.hasMore && filter=="音乐") item { TextButton({musicVm.search(true)},enabled=!music.searching,modifier=Modifier.fillMaxWidth()) { Text("更多音乐") } }
                }
                if(filter!="音乐") {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("小说 · "+books.results.size,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            if(filter=="全部") TextButton({filter="小说"}) {Text("查看全部")}
                        }
                    }
                    books.message?.let { msg -> item { Text(msg,Modifier.padding(horizontal=24.dp),color=MaterialTheme.colorScheme.error) } }
                    items(if(filter=="全部") books.results.take(5) else books.results,key={"book:"+it.sourceKey+":"+it.url}) { book ->
                        val collected=saved.books.any { it.url==book.url && it.sourceKey==book.sourceKey }
                        MediaRow(book.bookName,book.author.orEmpty(),book.sourceName,icon=Icons.AutoMirrored.Outlined.MenuBook,onClick={onOpenBook(book)},trailing={
                            IconButton({g.media.toggle(book)}) { Icon(if(collected) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,if(collected) "取消收藏" else "收藏小说") }
                        })
                    }
                    if(!books.searching && books.results.isEmpty()) item {
                        val failed=books.sources.count { it.second is SourceSearchState.Failed }
                        Text(if(failed>0) "$failed 个书源未能完成搜索，可在来源页检查；也可换个关键词。" else "没有找到小说，试试其他关键词。",Modifier.padding(24.dp),style=MaterialTheme.typography.bodySmall)
                    }
                }
                if(!searching && music.songs.isEmpty() && books.results.isEmpty()) item { OutlinedButton({search()},Modifier.padding(20.dp).fillMaxWidth()) { Text("重新搜索") } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailScreen(key: String,onBack:()->Unit,onDownloads:()->Unit,vm:MusicViewModel=viewModel()) {
    val context=LocalContext.current; val g=context.graph; val snack=rememberSnack()
    var song by remember(key) { mutableStateOf(g.media.song(key)) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val settings by g.musicSources.settings.collectAsStateWithLifecycle()
    val saved by g.media.saved.collectAsStateWithLifecycle()
    val operation by vm.state.collectAsStateWithLifecycle()
    val playback by MusicPlaybackService.playback.collectAsStateWithLifecycle()
    val tasks by g.musicDownloads.tasks.collectAsStateWithLifecycle()
    val requestPermission=rememberMusicNotifications()
    LaunchedEffect(key,retry) {
        val initial=song
        if(initial==null) { loading=false; error="歌曲记录不存在，请重新搜索"; return@LaunchedEffect }
        loading=true; error=null
        try { song=g.musicSources.source(initial.source).detail(initial); song?.let { g.media.remember(it) } }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { error=e.message ?: "暂时无法加载详情" }
        finally { loading=false }
    }
    LaunchedEffect(operation.message) { operation.message?.let { snack(it); vm.clearMessage() } }
    LaunchedEffect(Unit) { while(isActive) { g.musicDownloads.refresh(); delay(1500) } }
    Scaffold(topBar={TopAppBar(title={Text("音乐详情")},navigationIcon={IconButton(onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},actions={IconButton(onDownloads){Icon(Icons.Outlined.Download,"查看下载")}})}) { padding ->
        val current=song
        if(current==null) { Box(Modifier.padding(padding)){MediaEmpty("暂时找不到歌曲",error.orEmpty())}; return@Scaffold }
        val qualities=remember(current,settings) { g.musicSources.qualities(current) }
        var quality by remember(qualities) { mutableStateOf(qualities.firstOrNull { it.bitrate==128 } ?: qualities.firstOrNull()) }
        val favorite=saved.songs.any { it.id==current.id && it.source==current.source }
        val playingThis=playback.songKey==key
        val local=tasks.firstOrNull { it.song.id==current.id && it.song.source==current.source && it.complete }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.widthIn(max=330.dp).fillMaxWidth(.82f).aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center) {
                if(current.cover.isBlank()) Icon(Icons.Outlined.MusicNote,null,Modifier.size(100.dp),tint=MaterialTheme.colorScheme.primary)
                else AsyncImage(current.cover,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            }
            Spacer(Modifier.height(24.dp))
            Text(current.title,style=MaterialTheme.typography.headlineSmall)
            Text(current.artist,Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sourceName(current.source)+" · "+timeLabel(current.duration),Modifier.padding(8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=12.dp))
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall); TextButton({retry++}){Text("重新加载")} }
            if(playingThis) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(dragging ?: playback.position.toFloat(),{dragging=it},valueRange=0f..playback.duration.coerceAtLeast(1).toFloat(),
                    onValueChangeFinished={dragging?.let { MusicPlaybackService.seek(context,it.toInt()) };dragging=null},enabled=!playback.loading)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(timeLabel(playback.position/1000),style=MaterialTheme.typography.labelSmall);Text(timeLabel(playback.duration/1000),style=MaterialTheme.typography.labelSmall)}
            }
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                FilledTonalIconButton({g.media.toggle(current)}) { Icon(if(favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(favorite) "取消收藏" else "收藏歌曲") }
                Button(onClick={requestPermission { if(playingThis) MusicPlaybackService.toggle(context) else if(local!=null) vm.playLocal(local) else quality?.let {vm.act(current,it,false)} }},
                    enabled=operation.busy==null && !playback.loading && (playingThis || local!=null || (!loading && error==null && quality!=null)),modifier=Modifier.weight(1f).height(52.dp)) {
                    Icon(if(playingThis && playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null)
                    Spacer(Modifier.width(6.dp));Text(if(playingThis && playback.playing) "暂停" else if(local!=null) "离线播放" else "播放")
                }
                FilledTonalIconButton({quality?.let { q -> requestPermission {vm.act(current,q,true)} }},enabled=!loading && error==null && quality!=null && operation.busy==null) {Icon(Icons.Outlined.Download,"下载所选音质")}
            }
            if(operation.busy!=null) Text("正在获取音频…",style=MaterialTheme.typography.bodySmall)
            if(qualities.isNotEmpty()) {
                Text(if(settings.lxEnabled && current.source=="kuwo") "音质 · "+settings.lxName else "选择音质",Modifier.fillMaxWidth().padding(top=12.dp),style=MaterialTheme.typography.titleMedium)
                var choosingQuality by remember {mutableStateOf(false)}
                Box(Modifier.fillMaxWidth().padding(top=10.dp)) {
                    OutlinedButton({choosingQuality=true},Modifier.fillMaxWidth()) {
                        Text(quality?.label.orEmpty()+"  "+quality?.size.orEmpty(),Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore,"选择其他音质")
                    }
                    DropdownMenu(choosingQuality,{choosingQuality=false}) {
                        qualities.forEach { option ->
                            DropdownMenuItem(text={Text(option.label+"  "+option.size)},onClick={quality=option;choosingQuality=false},
                                leadingIcon={if(quality==option) Icon(Icons.Outlined.Check,null)})
                        }
                    }
                }
            }
            if(current.album.isNotBlank()) Text("专辑 / 分类 · "+current.album,Modifier.fillMaxWidth().padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium)
            if(current.description.isNotBlank()) Text(current.description,Modifier.fillMaxWidth().padding(bottom=16.dp),style=MaterialTheme.typography.bodyMedium)
            if(current.webUrl.isNotBlank()) TextButton({runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(current.webUrl)))}.onFailure{snack("无法打开浏览器")}}){Text("打开来源页面")}
            Spacer(Modifier.height(24.dp))
        }
    }
}
