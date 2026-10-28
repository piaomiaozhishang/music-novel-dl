package com.wang.sonovel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.wang.sonovel.core.*
import com.wang.sonovel.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ReadingReliabilityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun longDirectoryVariablesAndAllPaginationLinksSurvive()=runBlocking<Unit> {
        Server { path -> when(path) {
            "/book" -> "<h1>变量与分页</h1><b>作者</b><div class='chapters'>"+
                (1..600).joinToString("") { "<a href='/c$it'>第${it}章</a>" }+
                "</div><select class='pages'><option value='/toc2'>2</option><option value='/toc3'>3</option></select>"
            "/toc2" -> "<div class='chapters'><a href='/c601'>第601章</a></div>"
            "/toc3" -> "<div class='chapters'><a href='/c602'>第602章</a></div>"
            "/c1" -> "<div id='content'>正文第一页</div><a class='next' href='/c1-2'>下一页</a><a class='next' href='/c1-3'>末页</a><a class='next' href='/c2'>下一章</a>"
            "/c1-2" -> "<div id='content'>正文第二页</div>"
            "/c1-3" -> "<div id='content'>正文第三页</div>"
            else -> "<div id='content'>不应该吞入下一章</div>"
        } }.use { server ->
            val json="""{"bookSourceUrl":"BASE","bookSourceName":"长目录","searchUrl":"/search","ruleSearch":{"bookList":"a","name":"text","bookUrl":"href"},"ruleBookInfo":{"name":"h1@text","author":"b@text"},"ruleToc":{"chapterList":".chapters@a","chapterName":"text<js>java.put('token',result);result</js>","chapterUrl":"href","nextTocUrl":".pages@option@value"},"ruleContent":{"content":"#content@text<js>result+':'+java.get('token')+':'+chapter.index</js>","nextContentUrl":"a.next@href"}}""".replace("BASE",server.base)
            val rule=context.graph.rules.parse(json,"test-long.json").single()
            val ctx=SourceContext(rule,AppSettings(minInterval=0,maxInterval=0))
            val toc=TocParser(ctx).parseAll(server.base+"/book")
            assertEquals(602,toc.size)
            assertTrue(toc.last().title.contains("602"))
            // The first chapter's variables have already left the small in-memory cache.
            val text=ChapterParser(SourceContext(rule,ctx.settings)).fetchContent(toc.first().url,0)
            assertTrue(text,text.contains("正文第一页:第1章:0"))
            assertTrue(text,text.contains("正文第二页"))
            assertTrue(text,text.contains("正文第三页"))
            assertFalse(text,text.contains("不应该吞入下一章"))
        }
    }
    @Test fun largeImportsReuseUnchangedRulesAndIndexes() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val sample=runCatching { assets.open("private-samples/reading.json").bufferedReader().use { it.readText() } }.getOrNull()
        org.junit.Assume.assumeNotNull(sample)
        val repo=context.graph.rules
        val original=repo.allRules.first()
        val name="performance-reading.json"
        try {
            val start=System.nanoTime()
            assertEquals(418,repo.import(name,sample!!))
            val millis=(System.nanoTime()-start)/1_000_000
            assertSame(original,repo.byKey(original.key))
            val all=repo.allRules
            val lookupStart=System.nanoTime()
            repeat(10000) { assertSame(all,repo.allRules);assertSame(original,repo.byKey(original.key)) }
            val lookupMillis=(System.nanoTime()-lookupStart)/1_000_000
            val reloadStart=System.nanoTime();repo.reload()
            assertSame(original,repo.byKey(original.key))
            val reloadMillis=(System.nanoTime()-reloadStart)/1_000_000
            java.io.File(context.getExternalFilesDir(null),"source-performance-032.txt").writeText(
                "418 个书源导入：$millis ms；10000 次缓存列表/索引访问：$lookupMillis ms；未变文件重新加载：$reloadMillis ms。")
        } finally {
            if(InstrumentationRegistry.getArguments().getString("keepPerfSamples")!="true") repo.deleteUserFile(name)
        }
    }
    @Test fun singleWrappedAndBomRulesUseTheSameImporter() {
        val raw="""{"bookSourceUrl":"https://example.org","bookSourceName":"输入测试","searchUrl":"/search","ruleSearch":{"bookList":"a","name":"text","bookUrl":"href"},"ruleToc":{"chapterList":"a","chapterName":"text","chapterUrl":"href"},"ruleContent":{"content":"p@text"}}"""
        val repo=context.graph.rules
        for(input in listOf("\uFEFF"+raw,"[$raw]","""{"bookSources":[$raw]}""")) {
            assertEquals("输入测试",repo.parse(input,"input-test.json").single().displayName)
        }
    }
    @Test fun liveSourceHttpReport() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveBooks")=="true")
        val report=StringBuilder()
        val client=Http.client(context.graph.settings.current).newBuilder().followRedirects(false).build()
        for(ua in listOf(RandomUA.generate(),RandomUA.generate(),"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")) {
            val request=okhttp3.Request.Builder().url("https://www.bqquge.org/so/%E7%8E%84%E9%89%B4%E4%BB%99%E6%97%8F").header("User-Agent",ua).header("Referer","https://www.bqquge.org/").build()
            try {
                client.newCall(request).execute().use { r ->
                    report.append(ua).append("\nHTTP ").append(r.code).append(" Location=").append(r.header("Location")).append("\n")
                    report.append(org.jsoup.Jsoup.parse(r.body?.string().orEmpty()).select("h3").text()).append("\n\n")
                }
            } catch(e:Exception) { report.append(e.message).append("\n") }
        }
        java.io.File(context.getExternalFilesDir(null),"source-http-032.txt").writeText(report.toString())
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
