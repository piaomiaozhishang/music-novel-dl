package com.wang.sonovel.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wang.sonovel.graph
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun SourceHubScreen(onManageBooks:()->Unit) {
    val context=LocalContext.current;val g=context.graph;val scope=rememberCoroutineScope();val snack=rememberSnack()
    val settings by g.musicSources.settings.collectAsStateWithLifecycle()
    val lxScripts by g.musicSources.scripts.collectAsStateWithLifecycle()
    val bookSettings by g.settings.state.collectAsStateWithLifecycle()
    val files by g.rules.files.collectAsStateWithLifecycle()
    var filter by rememberSaveable {mutableStateOf("全部")}
    var paste by remember {mutableStateOf(false)}
    var text by rememberSaveable {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var report by remember {mutableStateOf<String?>(null)}
    var removeLx by remember {mutableStateOf(false)}
    var bookQuery by rememberSaveable {mutableStateOf("")}
    var bookFilter by rememberSaveable {mutableStateOf("所有书源")}
    val allBooks=remember(files) {files.flatMap {it.rules}}
    val visibleBooks=remember(allBooks,bookQuery,bookFilter) { allBooks.filter { rule ->
        (bookQuery.isBlank() || rule.displayName.contains(bookQuery,true) || rule.url.orEmpty().contains(bookQuery,true)) &&
            (bookFilter=="所有书源" || (bookFilter=="可启用")==rule.compatibility.isNullOrBlank())
    } }
    fun import(name:String,input:String){
        if(busy)return
        busy=true
        scope.launch {
            try {
                val content=withContext(Dispatchers.IO){
                    com.wang.sonovel.core.SourceImportInput.resolve(input)
                }
                val jsonStart=withContext(Dispatchers.Default) { com.wang.sonovel.data.RuleRepository.stripComments(content).trimStart() }
                if(jsonStart.startsWith("[") || jsonStart.startsWith("{")) {
                    val fileName=name.substringBeforeLast('.',name).ifBlank{"导入书源"}+"_"+content.hashCode().toUInt().toString(16)+".json"
                    val rules=withContext(Dispatchers.IO){g.rules.import(fileName,content);g.rules.allRules.filter { it.file==fileName }}
                    val supported=rules.filter{it.compatibility.isNullOrBlank()}
                    val unsupported=rules.filterNot{it.compatibility.isNullOrBlank()}
                    report="已导入 "+rules.size+" 个书源。\n规则初检通过 "+supported.size+" 个，待适配 "+unsupported.size+" 个。初检不代表网站当前可用。\n\n"+
                        if(unsupported.isEmpty()) "已保留原始规则。实际连通情况可在“管理书源”检测。" else unsupported.take(10).joinToString("\n\n"){it.displayName+"\n"+it.compatibility}+"\n\n仅展示前 10 项，完整原因可在管理书源中查看。待适配的书源保持关闭，原始规则已保留。"
                }else report=g.musicSources.importScript(content)
                paste=false;text=""
            }catch(e:CancellationException){throw e}
            catch(e:Exception){report=e.message ?: "导入失败"}
            finally{busy=false}
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) scope.launch {
            try {
                val pair=withContext(Dispatchers.IO) {
                    val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else "导入源"} ?: "导入源"
                    val content=context.contentResolver.openInputStream(uri)!!.use { com.wang.sonovel.core.SourceImportInput.decode(it) }
                    name to content
                }
                import(pair.first,pair.second)
            }catch(e:Exception){snack(e.message ?: "读取失败")}
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)){
        MediaHeader("来源","书源与音源，在同一个地方管理"){
            IconButton({paste=true},enabled=!busy){Icon(Icons.Outlined.AddLink,"粘贴链接或规则")}
            IconButton({picker.launch(arrayOf("*/*"))},enabled=!busy){Icon(Icons.Outlined.FileOpen,"导入源文件")}
        }
        MediaFilters(filter,listOf("全部","音乐","小说")){filter=it}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)){
            item{
                Surface(Modifier.padding(horizontal=20.dp,vertical=10.dp).fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.secondaryContainer){
                    Column(Modifier.padding(16.dp)){
                        Text("添加你习惯的来源",style=MaterialTheme.typography.titleMedium)
                        Text("支持阅读 / So Novel 的书源 JSON，以及洛雪的自定义源 JS。可选择文件，也可粘贴内容或直链。",Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodySmall)
                        Row{
                            TextButton({picker.launch(arrayOf("*/*"))},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSecondaryContainer)){Text("选择文件")}
                            TextButton({paste=true},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSecondaryContainer)){Text("粘贴导入")}
                        }
                    }
                }
            }
            if(filter!="小说"){
                item{SourceToggle("哔哩哔哩","视频搜索 / BV 号 · 音轨播放与下载",settings.bilibili,{g.musicSources.setEnabled("bilibili",it)})}
                item{
                    SourceToggle(settings.lxName.ifBlank{"洛雪自定义源"},if(settings.lxName.isBlank())"尚未导入 · 当前适配酷我解析" else "酷我解析 · "+settings.lxQualities.joinToString(" / "),settings.lxEnabled,{g.musicSources.setEnabled("lx",it)},settings.lxName.isNotBlank())
                    if(settings.lxName.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.End){TextButton({removeLx=true}){Text("移除脚本")}}
                }
                items(lxScripts,key={"lx-"+it.id}) { source ->
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(source.name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                        TextButton({scope.launch { runCatching { withContext(Dispatchers.IO) { g.musicSources.selectScript(source.id) } }.onFailure { snack(it.message.orEmpty()) } }},enabled=!busy) { Text("使用此源") }
                    }
                }
                item{Text("公开版不附带默认曲库与音源；洛雪脚本仅提供解析接口，不能独立提供音乐搜索。B 站使用视频的默认分 P。",Modifier.padding(horizontal=24.dp,vertical=10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            if(filter!="音乐"){
                item{Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text("小说书源 · "+files.sumOf{it.rules.size},Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onManageBooks){Text("管理书源")}}}
                item {
                    OutlinedTextField(bookQuery,{bookQuery=it},Modifier.fillMaxWidth().padding(horizontal=20.dp),placeholder={Text("查找书源名称或网址")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)})
                    MediaFilters(bookFilter,listOf("所有书源","可启用","待适配")) {bookFilter=it}
                }
                items(visibleBooks,key={it.key},contentType={"book-source"}){rule->
                    bookSettings
                    SourceToggle(rule.displayName,if(!rule.compatibility.isNullOrBlank()) "待适配 · "+rule.compatibility else if(rule.legadoJson!=null) "阅读 3.0 · "+rule.url else "So Novel · "+rule.url,g.rules.isEnabled(rule),{g.rules.setEnabled(rule,it)},rule.compatibility.isNullOrBlank())
                }
            }
        }
    }
    if(paste) AlertDialog(onDismissRequest={if(!busy)paste=false},title={Text("导入来源")},text={Column{Text("粘贴书源 JSON、洛雪脚本，或可直接下载它们的网址。",style=MaterialTheme.typography.bodySmall);OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=140.dp,max=280.dp),enabled=!busy)}},confirmButton={TextButton({import("导入源",text)},enabled=text.isNotBlank() && !busy){Text(if(busy)"处理中…" else "导入")}},dismissButton={TextButton({paste=false},enabled=!busy){Text("取消")}})
    report?.let{result->AlertDialog(onDismissRequest={report=null},title={Text("导入结果")},text={Text(result,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton({report=null}){Text("知道了")}})}
    if(removeLx)AlertDialog(onDismissRequest={removeLx=false},title={Text("移除洛雪脚本？")},text={Text("移除后将停用该脚本；公开版不包含内置默认音源。")},confirmButton={TextButton({g.musicSources.removeScript();removeLx=false}){Text("移除")}},dismissButton={TextButton({removeLx=false}){Text("保留")}})
}

@Composable
private fun SourceToggle(title:String,detail:String,checked:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true){
    Surface(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,Modifier.padding(top=5.dp),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=if(detail.startsWith("待适配"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
            Switch(checked,onChange,enabled=enabled)
        }
    }
}
