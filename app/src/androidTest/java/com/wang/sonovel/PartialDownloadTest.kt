package com.wang.sonovel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wang.sonovel.data.*
import com.wang.sonovel.download.*
import com.wang.sonovel.reader.BookReadSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PartialDownloadTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedChaptersCanBeExportedOfflineRecoveredAndRetried()=runBlocking<Unit> {
        val g=context.graph
        val original=g.settings.current
        g.settings.update { it.copy(proxyEnabled=false,enableRetry=false,minInterval=0,maxInterval=0,
            concurrency=-1,fetchBetterCover=false,saveToPublic=false) }
        val outputs=mutableListOf<File>()
        val managers=mutableListOf<Pair<DownloadManager,Long>>()
        val file="partial-regression.json"
        try { Server().use { server ->
            val source="""{"bookSourceUrl":"BASE","bookSourceName":"容错回归","searchUrl":"/search","ruleSearch":{"bookList":"a","name":"text","bookUrl":"href"},"ruleToc":{"chapterList":"a","chapterName":"text","chapterUrl":"href"},"ruleContent":{"content":"#body@html"}}""".replace("BASE",server.base)
            g.rules.import(file,source)
            val rule=g.rules.allRules.first { it.file==file }
            for(format in listOf(ExportFormat.TXT,ExportFormat.EPUB)) {
                server.failSecond.set(true);server.offline.set(false);server.counts.clear()
                val book=BookInfo(server.base+"/book-"+format.ext,"容错回归"+System.nanoTime(),"测试作者")
                val toc=(1..3).map { ChapterRef(it,"第${it}章",server.base+"/c$it") }
                val request=DownloadRequest(book.url,rule.key,book,toc,format=format)
                val id=g.downloads.enqueue(request,book.bookName,book.author,null)
                managers+=g.downloads to id
                val partial=withTimeout(20000) { g.downloads.tasks.first { list->list.any { it.id==id && !it.status.active } }.first { it.id==id } }
                assertEquals(partial.message,TaskStatus.PARTIAL,partial.status)
                assertEquals(2,partial.done);assertEquals(1,partial.failed);assertNull(partial.output)
                assertEquals(1,server.counts["/c1"]?.get());assertEquals(1,server.counts["/c2"]?.get());assertEquals(1,server.counts["/c3"]?.get())
                assertTrue("Reading default concurrency must stay bounded",server.maxActive.get()<=2)

                // A new manager reconstructs the task from saved metadata and atomic chapter files.
                val restored=DownloadManager(context,g.settings,g.rules,g.library)
                managers+=restored to id
                val recovered=restored.tasks.value.first { it.id==id }
                assertEquals(2,recovered.done);assertEquals(TaskStatus.CANCELLED,recovered.status)
                server.offline.set(true)
                val before=server.counts.values.sumOf { it.get() }
                val fullOutput=g.library.outputFile(book,format)
                fullOutput.writeText("existing-complete-file");outputs+=fullOutput
                val exported=async(start=CoroutineStart.UNDISPATCHED) { withTimeout(20000) { restored.finished.first { it.id==id } } }
                restored.exportDownloaded(id)
                val result=exported.await()
                assertEquals(result.message,TaskStatus.DONE,result.status)
                val output=requireNotNull(result.output);outputs+=output
                assertNotEquals(fullOutput,output)
                assertEquals("existing-complete-file",fullOutput.readText())
                assertEquals(before,server.counts.values.sumOf { it.get() })
                BookReadSource.open(output).use { reader ->
                    val text=reader.chapters.indices.joinToString("\n") { reader.paragraphs(it).joinToString("\n") }
                    assertTrue(text,text.contains("章节1正文"));assertTrue(text,text.contains("章节3正文"))
                    assertTrue(text,text.contains("本章尚未下载成功"))
                }

                server.offline.set(false);server.failSecond.set(false)
                val completed=async(start=CoroutineStart.UNDISPATCHED) { withTimeout(20000) { restored.finished.first { it.id==id } } }
                restored.retry(id)
                val full=completed.await()
                assertEquals(full.message,TaskStatus.DONE,full.status);assertEquals(0,full.failed);assertEquals(3,full.done)
                assertEquals(1,server.counts["/c1"]?.get());assertEquals(2,server.counts["/c2"]?.get());assertEquals(1,server.counts["/c3"]?.get())
                BookReadSource.open(requireNotNull(full.output)).use { reader ->
                    val text=reader.chapters.indices.joinToString("\n") { reader.paragraphs(it).joinToString("\n") }
                    assertTrue(text,text.contains("章节2正文"));assertFalse(text,text.contains("本章尚未下载成功"))
                }
            }
        } } finally {
            managers.forEach { (manager,id)->manager.remove(id) }
            outputs.forEach { it.delete() }
            g.rules.deleteUserFile(file);g.settings.update { original };g.library.refresh()
        }
    }

    @Test fun allFailedDoesNotExportAnEmptyBook()=runBlocking<Unit> {
        val g=context.graph;val original=g.settings.current;val file="all-fail-regression.json"
        var id:Long?=null
        try { Server().use { server ->
            g.settings.update { it.copy(proxyEnabled=false,enableRetry=false,minInterval=0,maxInterval=0,fetchBetterCover=false,saveToPublic=false) }
            g.rules.import(file,"""{"bookSourceUrl":"BASE","bookSourceName":"全部失败","ruleContent":{"content":"#body@html"},"ruleToc":{"chapterList":"a","chapterName":"text","chapterUrl":"href"}}""".replace("BASE",server.base))
            val rule=g.rules.allRules.first { it.file==file }
            val book=BookInfo(server.base+"/allfail","全部失败回归","测试")
            id=g.downloads.enqueue(DownloadRequest(book.url,rule.key,book,listOf(ChapterRef(1,"失败章节",server.base+"/c2")),format=ExportFormat.TXT),book.bookName,book.author,null)
            val result=withTimeout(20000) { g.downloads.tasks.first { rows->rows.any { it.id==id && !it.status.active } }.first { it.id==id } }
            assertEquals(TaskStatus.FAILED,result.status);assertEquals(0,result.done);assertNull(result.output)
            g.downloads.exportDownloaded(id!!)
            assertEquals(TaskStatus.FAILED,g.downloads.tasks.value.first { it.id==id }.status)
        } } finally {
            id?.let { g.downloads.remove(it) };g.rules.deleteUserFile(file);g.settings.update { original }
        }
    }

    private class Server:java.io.Closeable {
        val socket=ServerSocket(0);val base="http://127.0.0.1:"+socket.localPort
        val failSecond=AtomicBoolean(true);val offline=AtomicBoolean(false)
        val counts=ConcurrentHashMap<String,AtomicInteger>();val maxActive=AtomicInteger();val active=AtomicInteger()
        @Volatile var alive=true
        private val worker=thread(isDaemon=true) {
            while(alive) runCatching { val client=socket.accept();thread(isDaemon=true) { client.use {
                val reader=it.getInputStream().bufferedReader();val path=reader.readLine().split(' ')[1]
                while(!reader.readLine().isNullOrBlank()){}
                counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val current=active.incrementAndGet();maxActive.updateAndGet { old->maxOf(old,current) }
                try {
                    Thread.sleep(80)
                    val failed=offline.get() || (path=="/c2" && failSecond.get())
                    val body=(if(failed) "failed" else "<div id='body'><p>章节${path.removePrefix("/c")}正文，已成功保存。</p></div>").toByteArray()
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 "+(if(failed) "503 Unavailable" else "200 OK")+"\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.size+"\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body);flush()
                    }
                } finally { active.decrementAndGet() }
            } } }
        }
        override fun close() { alive=false;socket.close();worker.join(1000) }
    }
}
