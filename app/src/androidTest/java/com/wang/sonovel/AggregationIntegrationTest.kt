package com.wang.sonovel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wang.sonovel.core.*
import com.wang.sonovel.music.*
import com.wang.sonovel.data.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AggregationIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun importedReadingSourceSearchDetailTocAndContent()=runBlocking {
        FixtureServer { path ->
            when {
                path.startsWith("/search") -> "<div class='book'><a href='/book'>本地测试书</a><b>本地作者</b></div>"
                path=="/book" -> "<h1>本地测试书</h1><b>本地作者</b><a class='toc' href='/toc'>目录</a>"
                path=="/toc" -> "<div class='chapters'><a href='/chapter'>第一章</a></div><a class='next' href='/toc2'>下一页</a>"
                path=="/toc2" -> "<div class='chapters'><a href='/chapter2'>第二章</a></div>"
                path=="/chapter" -> "<div id='content'>这是正文第一段。<br>这是第二段。</div><a class='next' href='/chapter-part2'>下一页</a>"
                path=="/chapter-part2" -> "<div id='content'>分页内容。</div>"
                else -> "<div id='content'>第二章正文。</div>"
            }
        }.use { fixture ->
            val json="""{"bookSourceUrl":"BASE","bookSourceName":"本地阅读测试源","searchUrl":"/search?q={{key}}&p={{page}}","ruleSearch":{"bookList":"class.book","name":"a@text","author":"b@text","bookUrl":"a@href"},"ruleBookInfo":{"name":"h1@text","author":"b@text","tocUrl":"a.toc@href"},"ruleToc":{"chapterList":".chapters a","chapterName":"text","chapterUrl":"href","nextTocUrl":"a.next@href"},"ruleContent":{"content":"#content@text","nextContentUrl":"a.next@href"}}""".replace("BASE",fixture.base)
            val repo=context.graph.rules
            val filename="integration-reading.json"
            try {
                assertEquals(1,repo.import(filename,json))
                val source=repo.allRules.first{it.file==filename}
                assertTrue(repo.isEnabled(source))
                val ctx=SourceContext(source,AppSettings(minInterval=0,maxInterval=0))
                val results=SearchParser(ctx).search("测试")
                assertEquals("本地测试书",results.single().bookName)
                val book=BookParser(ctx).parse(results.single().url)
                assertEquals("本地作者",book.author)
                val chapters=TocParser(ctx).parseAll(book.url)
                assertEquals(2,chapters.size)
                val content=ChapterParser(ctx).fetchContent(chapters[0].url,0)
                assertTrue(content.contains("这是正文第一段"))
                assertTrue(content.contains("分页内容"))
                assertTrue(repo.rawJson(source).contains("bookSourceUrl"))
            } finally {repo.deleteUserFile(filename)}
        }
    }
    @Test fun lxScriptInitializesAndResolvesUsingHttpPromise()=runBlocking {
        FixtureServer { """{"url":"https://example.com/test.mp3"}""" }.use { fixture ->
            val script="""
                // @name 集成测试洛雪源
                lx.on(lx.EVENT_NAMES.request, async ({source,action,info}) => {
                  if(source !== 'kw' || action !== 'musicUrl' || info.musicInfo.songmid !== '123') throw Error('bad input');
                  return new Promise((resolve,reject) => lx.request('BASE/audio',{},(err,res,body)=>{if(err)reject(err);else resolve(body.url)}));
                });
                lx.send(lx.EVENT_NAMES.inited,{sources:{kw:{name:'酷我',type:'music',actions:['musicUrl'],qualitys:['128k']}}});
            """.trimIndent().replace("BASE",fixture.base)
            val init=LxRuntime.execute(context,script,null)
            assertTrue(init.contains("128k"))
            val result=LxRuntime.execute(context,script,"""{"source":"kw","action":"musicUrl","info":{"type":"128k","musicInfo":{"songmid":"123"}}}""")
            assertEquals("https://example.com/test.mp3",result)
        }
    }
    @Test fun lxRejectsInvalidScriptWithoutCrashingApp()=runBlocking {
        val error=runCatching {LxRuntime.execute(context,"throw new Error('unsupported custom source')",null)}.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("unsupported custom source"))
    }
    @Test fun lxCancellationStopsAnInfiniteScript()=runBlocking {
        val error=runCatching {kotlinx.coroutines.withTimeout(1500){LxRuntime.execute(context,"while(true){}",null)}}.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.TimeoutCancellationException)
        kotlinx.coroutines.delay(500)
        val result=LxRuntime.execute(context,"lx.on('request',async()=> '');lx.send('inited',{sources:{}})",null)
        assertTrue(result.contains("sources"))
    }
    private class FixtureServer(private val body:(String)->String):java.io.Closeable{
        private val socket=ServerSocket(0)
        val base="http://127.0.0.1:"+socket.localPort
        private val alive=AtomicBoolean(true)
        private val worker=thread(isDaemon=true){
            while(alive.get()) runCatching{
                socket.accept().use { client ->
                    client.soTimeout=5000
                    val reader=client.getInputStream().bufferedReader()
                    val path=reader.readLine().split(' ')[1]
                    while(!reader.readLine().isNullOrBlank()){}
                    val payload=body(path).toByteArray()
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+payload.size+"\r\nConnection: close\r\n\r\n").toByteArray())
                        write(payload);flush()
                    }
                }
            }
        }
        override fun close(){alive.set(false);socket.close();worker.join(1000)}
    }
}
