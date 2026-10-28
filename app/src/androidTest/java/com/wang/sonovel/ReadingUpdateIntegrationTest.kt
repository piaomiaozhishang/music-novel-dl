package com.wang.sonovel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wang.sonovel.core.*
import com.wang.sonovel.data.*
import com.wang.sonovel.download.TaskStatus
import com.wang.sonovel.reader.BookReadSource
import com.wang.sonovel.ui.screens.BookDetailViewModel
import com.wang.sonovel.ui.screens.PreviewState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ReadingUpdateIntegrationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val graph get()=context.graph

    private var settingsBeforeTest:AppSettings?=null
    @org.junit.Before fun isolateNetworkSettings() {
        settingsBeforeTest=graph.settings.current
        graph.settings.update { it.copy(proxyEnabled=false) }
    }
    @org.junit.After fun restoreNetworkSettings() {
        settingsBeforeTest?.let { original -> graph.settings.update { original } }
    }

    @Test fun directoryPreviewDownloadExportAndLocalReader()=runBlocking<Unit> {
        Server { path -> when(path) {
            "/book" -> """<h1>目录回归测试</h1><p>测试作者</p><div class="dir clear"><li><a href="/c2">第二章</a></li></div><div class="dir clear"><li><a href="/c1">第一章</a></li><li><a href="/c2">第二章</a></li></div>"""
            "/c1" -> """<div class="con"><p>分页前的正文。</p></div><a href="/c1-2">下一页</a>"""
            "/c1-2" -> """<div class="con"><p>第二页完整正文。</p></div>"""
            else -> """<div class="con"><p>这是第二章的完整正文。</p></div>"""
        } }.use { server ->
            val source="""{"bookSourceUrl":"BASE","bookSourceName":"目录回归源","searchUrl":"/search","ruleSearch":{"bookList":"class.item","name":"h3@text","bookUrl":"a@href"},"ruleBookInfo":{"name":"h1@text","author":"p.0@text"},"ruleToc":{"chapterList":"class.dir clear@li","chapterName":"a@text","chapterUrl":"a@href"},"ruleContent":{"content":".con@all","nextContentUrl":"text.下一页@href"}}""".replace("BASE",server.base)
            verifyThroughUiModel(source,server.base+"/book",false)
        }
    }

    @Test fun user8009SourceWorksWithoutEditingItsRules()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBooks")=="true")
        val source=instrumentation.context.assets.open("private-samples/reading-8009.json").bufferedReader().use { it.readText() }
        verifyThroughUiModel(source,"https://www.bqquge.org/5",true)
    }

    private suspend fun verifyThroughUiModel(source:String,url:String,live:Boolean)=coroutineScope {
        val filename=if(live) "test-8009.json" else "test-reading-workflow.json"
        val original=graph.settings.current
        val outputs=mutableListOf<java.io.File>()
        var vm:BookDetailViewModel?=null
        val testProxy=InstrumentationRegistry.getArguments().getString("liveProxyHost")
        val testPort=InstrumentationRegistry.getArguments().getString("liveProxyPort")?.toIntOrNull()
        graph.settings.update { it.copy(fetchBetterCover=false,saveToPublic=false,minInterval=0,maxInterval=0,enableRetry=false,concurrency=1,
            proxyEnabled=if(!live) false else if(testProxy!=null) true else it.proxyEnabled,
            proxyHost=if(live && testProxy!=null) testProxy else it.proxyHost,
            proxyPort=if(live && testPort!=null) testPort else it.proxyPort) }
        try {
            graph.rules.import(filename,source)
            val rule=graph.rules.allRules.first { it.file==filename }
            assertTrue(rule.compatibility.orEmpty(),graph.rules.isEnabled(rule))
            if(live) {
                val search=withContext(Dispatchers.IO) { SearchParser(SourceContext(rule,graph.settings.current)).search("玄鉴仙族") }
                assertTrue("真实书源搜索应找到目标书",search.any { it.url.trimEnd('/')==url })
            }
            val model=withContext(Dispatchers.Main) { BookDetailViewModel(context.applicationContext as android.app.Application,rule.key,url) }
            vm=model
            val state=withTimeout(60000) { model.state.first { !it.bookLoading && !it.tocLoading } }
            assertNull(state.bookError);assertNull(state.tocError)
            val toc=state.toc!!
            if(live) {
                assertTrue("完整目录不能只有最新章节",toc.size>1600)
                assertTrue("最新章节区不能打乱全文目录顺序",toc.first().title.startsWith("第1章"))
            } else {
                assertEquals(listOf("第一章","第二章"),toc.map { it.title })
            }
            withContext(Dispatchers.Main) { model.openPreview(0) }
            val preview=withTimeout(60000) { model.preview.first { it?.second !is PreviewState.Loading } }!!.second
            assertTrue(preview.toString(),preview is PreviewState.Loaded)
            for(format in listOf(ExportFormat.TXT,ExportFormat.EPUB)) {
                val finished=async(start=CoroutineStart.UNDISPATCHED) {
                    withTimeout(90000) { graph.downloads.finished.first { it.request.url==url && it.request.format==format } }
                }
                withContext(Dispatchers.Main) { model.enqueue(format,toc.take(2),"前两章",null,1) }
                val task=finished.await()
                assertEquals(task.message,TaskStatus.DONE,task.status)
                assertEquals(0,task.failed)
                val output=task.output!!
                outputs+=output
                assertTrue(output.length()>100)
                BookReadSource.open(output).use { reader ->
                    val text=reader.chapters.indices.joinToString("\n") { reader.paragraphs(it).joinToString("\n") }
                    if(live) assertTrue("真实导出正文过短",text.length>1000)
                    else {
                        assertTrue(text,text.contains("第二页完整正文"))
                        assertTrue(text,text.contains("这是第二章的完整正文"))
                    }
                }
                if(live) {
                    val copy=java.io.File(context.getExternalFilesDir(null),"verified-8009."+format.ext)
                    output.copyTo(copy,overwrite=true)
                }
            }
            if(live) java.io.File(context.getExternalFilesDir(null),"verified-8009.txt.report").writeText("原始 8009 书源：搜索、详情、目录 "+toc.size+" 章、首章试读、前两章 TXT/EPUB 下载导出及本地阅读全部通过。")
        } finally {
            withContext(Dispatchers.Main) { vm?.closePreview() }
            graph.rules.deleteUserFile(filename)
            graph.settings.update { original }
            outputs.forEach { it.delete() }
            graph.library.refresh()
        }
    }

    @Test fun updateSchedulePersistsAndManualCheckBypassesTheInterval()=runBlocking<Unit> {
        val count=AtomicInteger()
        var body="""{"versionCode":999,"versionName":"test","downloadUrl":"https://example.org/custom"}"""
        Server { count.incrementAndGet();body }.use { server ->
            val prefs="update-regression"
            context.getSharedPreferences(prefs,0).edit().clear().commit()
            var now=1_000_000L
            fun checker()=UpdateChecker(context,graph.settings,server.base,prefs) { now }
            val first=checker()
            first.checkIfDue()
            assertEquals(1,count.get())
            assertEquals("https://example.org/custom",first.pending.value!!.downloadUrl)
            first.ignore(first.pending.value!!)
            val restored=checker()
            restored.checkIfDue()
            assertEquals(1,count.get())
            now+=UpdateManifest.INTERVAL_MS
            restored.checkIfDue()
            assertEquals(2,count.get())
            assertNull("忽略的版本不自动提示",restored.pending.value)
            assertTrue(restored.check() is UpdateResult.Available)
            assertEquals(3,count.get())
            body=""
            assertTrue(restored.check() is UpdateResult.Failed)
            assertTrue(restored.status.value.message.contains("尚未发布"))
            first.schedule();first.schedule()
            val jobs=androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(UpdateChecker.WORK_NAME).get()
            assertEquals(1,jobs.count { !it.state.isFinished })
            context.getSharedPreferences(prefs,0).edit().clear().commit()
        }
    }

    private class Server(private val body:(String)->String):java.io.Closeable {
        private val socket=ServerSocket(0)
        val base="http://127.0.0.1:"+socket.localPort
        @Volatile private var alive=true
        private val worker=thread(isDaemon=true) {
            while(alive) runCatching {
                socket.accept().use { client ->
                    client.soTimeout=5000
                    val reader=client.getInputStream().bufferedReader()
                    val path=reader.readLine().split(' ')[1]
                    while(!reader.readLine().isNullOrBlank()) {}
                    val bytes=body(path).toByteArray()
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+bytes.size+"\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes);flush()
                    }
                }
            }
        }
        override fun close() { alive=false;socket.close();worker.join(1000) }
    }
}
