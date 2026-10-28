package com.wang.sonovel.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wang.sonovel.core.*
import com.wang.sonovel.data.SearchResult
import com.wang.sonovel.data.Rule
import com.wang.sonovel.graph
import kotlinx.coroutines.*

/** Test actual reading stages instead of presenting a successful home-page ping as usability. */
@Composable
fun BookSourceTestDialog(rule:Rule,onDismiss:()->Unit,onOpenBook:(SearchResult)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("输入一本书的名称，检查搜索、详情、目录和第一章正文。") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun close() { job?.cancel();onDismiss() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    AlertDialog(
        onDismissRequest=::close,
        title={ Text("测试阅读 · "+rule.displayName) },
        text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(keyword,{keyword=it;result=null},singleLine=true,enabled=!busy,label={Text("书名")})
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stage,style=MaterialTheme.typography.bodyMedium)
        } },
        confirmButton={
            if(result!=null) TextButton({ val book=result!!;close();onOpenBook(book) }) { Text("打开这本书") }
            else TextButton(enabled=!busy && keyword.isNotBlank(),onClick={
                busy=true
                job=scope.launch {
                    var step="搜索"
                    try {
                        val checked=withContext(Dispatchers.IO) {
                            require(rule.compatibility.isNullOrBlank()) { rule.compatibility.orEmpty() }
                            val ctx=SourceContext(rule,context.graph.settings.current)
                            suspend fun progress(label:String) { step=label;withContext(Dispatchers.Main) { stage="正在"+label+"…" } }
                            progress("搜索")
                            val books=SearchParser(ctx).search(keyword.trim())
                            val selected=books.firstOrNull { it.bookName.contains(keyword.trim(),true) } ?: books.firstOrNull()
                            requireNotNull(selected) { "没有找到这本书，可换个书名重试" }
                            progress("读取详情");BookParser(ctx).parse(selected.url)
                            progress("解析目录");val chapters=TocParser(ctx).parseAll(selected.url)
                            require(chapters.isNotEmpty()) { "目录为空" }
                            progress("读取第一章");val raw=ChapterParser(ctx).fetchContent(chapters.first().url,0)
                            val content=ChapterProcessor(ctx).process(1,chapters.first().title,raw)
                            val text=ChapterProcessor.paragraphs(content.html).joinToString("\n")
                            require(text.isNotBlank()) { "正文为空" }
                            selected to ("《"+selected.bookName+"》\n目录 "+chapters.size+" 章 · 正文读取成功\n\n"+text.take(180))
                        }
                        result=checked.first;stage=checked.second
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { stage=step+"失败："+(e.message ?: "请重试") }
                    finally { busy=false }
                }
            }) { Text(if(busy) "检测中…" else "开始测试") }
        },
        dismissButton={ TextButton(::close) { Text(if(busy) "取消检测" else "关闭") } },
    )
}
