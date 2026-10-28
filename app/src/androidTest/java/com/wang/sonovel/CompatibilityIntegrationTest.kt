package com.wang.sonovel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import com.wang.sonovel.core.*
import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.music.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import android.graphics.Bitmap
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CompatibilityIntegrationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun jsonScriptsAndMissingBookFieldsWorkAcrossStages()=runBlocking {
        Server { path -> when {
            path.startsWith("/search") -> """{"books":[{"id":1,"name":"测试书","author":"作者甲"}]}"""
            path=="/book/1" -> """{"intro":"详情简介","toc":"/toc/1"}"""
            path=="/toc/1" -> """{"chapters":[{"title":"第一章","url":"/chapter/1"}]}"""
            else -> """{"content":"第一段\n第二段"}"""
        } }.use { server ->
            val source=JsonParser.parseString("""
                {"bookSourceUrl":"BASE","bookSourceName":"JSON脚本测试","jsLib":"function clean(s){return s.replace('测试','融合');}","searchUrl":"/search?q={{key}}",
                 "ruleSearch":{"bookList":"$.books[*]","name":"$.name<js>clean(result)</js>","author":"$.author","bookUrl":"BASE/book/{{$.id}}"},
                 "ruleBookInfo":{"intro":"$.intro","tocUrl":"$.toc"},
                 "ruleToc":{"chapterList":"$.chapters[*]","chapterName":"$.title","chapterUrl":"$.url"},
                 "ruleContent":{"content":"$.content<js>java.put('read','yes');result+'\\n'+book.name</js>"}}
            """.trimIndent().replace("BASE",server.base)).asJsonObject
            val rule=LegadoImport.convert(source)
            assertTrue(rule.compatibility.orEmpty(),rule.compatibility.isNullOrBlank())
            val ctx=SourceContext(rule,AppSettings(minInterval=0,maxInterval=0))
            val found=LegadoEngine(ctx).search("中文 空格").single()
            assertEquals("融合书",found.bookName)
            val book=LegadoEngine(ctx).book(found.url)
            assertEquals("融合书",book.bookName);assertEquals("作者甲",book.author)
            val chapter=LegadoEngine(ctx).toc(found.url).single()
            val content=LegadoEngine(ctx).content(chapter.url,0)
            assertTrue(content,content.contains("第二段"));assertTrue(content,content.contains("融合书"))
        }
    }
    @Test fun readingScriptsHaveNoJavaReflectionAndHaveDeadline() {
        val js=ReadingScript("") { _,_->null }
        assertEquals("undefined",js.evaluate("typeof Packages",emptyMap()).asString)
        assertEquals("undefined",js.evaluate("typeof java.lang",emptyMap()).asString)
        assertNotNull(runCatching { js.evaluate("while(true){}",emptyMap()) }.exceptionOrNull())
    }
    @Test fun lxBufferCryptoCompressionAndCancellation()=runBlocking {
        val script="""
            lx.on('request',async()=>{
              var b=lx.utils.buffer.from('中文歌词','utf8');
              if(b.toString('hex')!=='e4b8ade69687e6ad8ce8af8d')throw Error('utf8');
              if(lx.utils.crypto.md5('abc')!=='900150983cd24fb0d6963f7d28e17f72')throw Error('md5');
              if(lx.utils.buffer.bufToString(lx.utils.buffer.from('AAH/','base64'),'hex')!=='0001ff')throw Error('binary');
              var zipped=await lx.utils.zlib.deflate(b);var plain=await lx.utils.zlib.inflate(zipped);
              if(plain.toString()!=='中文歌词')throw Error('zlib');
              var aes=lx.utils.crypto.aesEncrypt('hello','aes-128-cbc','0123456789abcdef','0102030405060708').toString('hex');
              if(aes!=='14bd5233b00ab22c25b351a19ddff210')throw Error('aes '+aes);
              await new Promise(resolve=>setTimeout(resolve,30));
              return {lyric:plain.toString(),bytes:lx.utils.crypto.randomBytes(8).length};
            });lx.send('inited',{sources:{}});
        """.trimIndent()
        val result=JsonParser.parseString(LxRuntime.execute(context,script,"{}")).asJsonObject
        assertEquals("中文歌词",result.get("lyric").asString);assertEquals(8,result.get("bytes").asInt)
    }
    @Test fun tagsRoundTripAndFailedCommitPreservesOriginal() {
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);bitmap.recycle()
        val cover=CoverData(out.toByteArray(),16,16)
        val song=Song("tag-test","标签测试","测试作者","测试专辑","",6,emptyList())
        for(ext in listOf("mp3","m4a","flac","ogg")) {
            val file=File(context.cacheDir,"tag-roundtrip.$ext")
            try {
                instrumentation.context.assets.open("test-tone.$ext").use { input->file.outputStream().use { input.copyTo(it) } }
                val before=file.readBytes()
                assertNotNull(runCatching { MusicTagWriter.write(file,song,"[00:00.00]歌词",cover) { false } }.exceptionOrNull())
                assertArrayEquals(before,file.readBytes())
                MusicTagWriter.write(file,song,"[00:00.00]歌词",cover)
                val tag=AudioFileIO.read(file).tag
                assertEquals("标签测试",tag.getFirst(FieldKey.TITLE));assertEquals("[00:00.00]歌词",tag.getFirst(FieldKey.LYRICS))
                assertArrayEquals(cover.bytes,tag.firstArtwork.binaryData)
                val retriever=android.media.MediaMetadataRetriever()
                try { retriever.setDataSource(file.path);assertTrue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>5000) } finally { retriever.release() }
            } finally { file.delete() }
        }
    }
    @Test fun userSamplesCompatibilityReport()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("userSamples")=="true")
        val report=StringBuilder()
        val assets=instrumentation.context.assets
        for(name in listOf("changqing.js","nianxin.js")) {
            val script=assets.open("private-samples/$name").bufferedReader().use { it.readText() }
            val init=runCatching { LxRuntime.execute(context,script,null) }
            report.appendLine("$name INIT: "+init.fold({it},{it.message.orEmpty()}))
            if(init.isFailure) {
                val pre="""
                    var events=[];var oldSend=lx.send,oldRequest=lx.request;
                    lx.on('request',()=> '');
                    lx.send=function(n,d){events.push({event:n,data:d});return oldSend(n,d)};
                    lx.request=function(url,opt,cb){events.push({http:String(url).split('?')[0]});return oldRequest(url,opt,function(err,res,body){events.push({status:res&&res.statusCode,error:err&&String(err)});cb(err,res,body)})};
                    console.log=console.error=function(){events.push({log:Array.from(arguments).map(String).join(' ').slice(0,400)})};
                    setTimeout(()=>oldSend('inited',{diagnostics:events}),18000);
                """.trimIndent()
                report.appendLine("$name DIAGNOSTICS: "+runCatching { LxRuntime.execute(context,pre+"\n"+script,null) }.fold({it},{it.message.orEmpty()}))
            }
            if(init.isSuccess) {
                val request="""{"source":"kw","action":"musicUrl","info":{"type":"128k","musicInfo":{"id":"156483846","songmid":"156483846","name":"测试","singer":"","albumName":"","source":"kw","img":"","interval":"03:00","types":[{"type":"128k","size":""}],"_types":{"128k":{"size":""}}}}}"""
                val result=runCatching { LxRuntime.execute(context,script,request) }
                report.appendLine("$name RESOLVE: "+result.fold({if(it.startsWith("http")) "URL host="+java.net.URI(it).host else it.take(200)},{it.message.orEmpty()}))
                result.getOrNull()?.takeIf { it.startsWith("http") }?.let { url ->
                    report.appendLine("$name HEADER: "+runCatching {
                        val http=okhttp3.OkHttpClient.Builder().callTimeout(12,java.util.concurrent.TimeUnit.SECONDS).build()
                        http.newCall(okhttp3.Request.Builder().url(url).header("Range","bytes=0-15").build()).execute().use { response->
                            val head=ByteArray(16);val n=response.body!!.byteStream().read(head)
                            "HTTP "+response.code+" type="+response.header("Content-Type")+" magic="+head.take(n.coerceAtLeast(0)).joinToString("") { "%02x".format(it) }
                        }
                    }.fold({it},{it.message.orEmpty()}))
                }
            }
        }
        val sources=JsonParser.parseString(assets.open("private-samples/reading.json").bufferedReader().use { it.readText() }).asJsonArray
        val rules=sources.map { LegadoImport.convert(it.asJsonObject) }
        report.appendLine("Reading total=${rules.size} compatible=${rules.count { it.compatibility.isNullOrBlank() }}")
        rules.forEach { report.appendLine(it.displayName+" : "+it.compatibility.orEmpty().ifBlank { "规则初检通过；连通性未检测" }) }
        File(context.filesDir,"compatibility-report.txt").writeText(report.toString())
        assertEquals(418,rules.size)
    }
    @Test fun userReadingSourcesLiveReport()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBooks")=="true")
        val sources=JsonParser.parseString(instrumentation.context.assets.open("private-samples/reading.json").bufferedReader().use { it.readText() }).asJsonArray
        val report=StringBuilder()
        for(name in listOf("365小说网","101看书","🍅番茄小说源")) {
            val source=sources.first { it.asJsonObject.get("bookSourceName").asString==name }.asJsonObject
            report.appendLine(name)
            val result=runCatching {
                val rule=LegadoImport.convert(source)
                val ctx=SourceContext(rule,AppSettings(minInterval=0,maxInterval=0,searchLimit=3))
                val rows=LegadoEngine(ctx).search("红楼梦")
                report.appendLine("搜索条数="+rows.size)
                val first=rows.firstOrNull() ?: error("搜索无结果")
                val book=LegadoEngine(ctx).book(first.url)
                report.appendLine("详情书名="+book.bookName)
                val chapters=LegadoEngine(ctx).toc(first.url)
                report.appendLine("章节数="+chapters.size)
                val text=LegadoEngine(ctx).content(chapters.first().url,0)
                report.appendLine("正文字符数="+text.length)
            }
            report.appendLine(result.fold({"通过"},{it.javaClass.simpleName+": "+it.message}))
            File(context.filesDir,"reading-live-report.txt").writeText(report.toString())
        }
    }
    @Test fun correctedUserReadingSourceWorksOnline()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBooks")=="true")
        val source=JsonParser.parseString(instrumentation.context.assets.open("private-samples/reading-fixed.json").bufferedReader().use { it.readText() }).asJsonArray[0].asJsonObject
        val ctx=SourceContext(LegadoImport.convert(source),AppSettings(minInterval=0,maxInterval=0,searchLimit=3))
        val found=LegadoEngine(ctx).search("红楼梦")
        assertTrue(found.isNotEmpty())
        val book=LegadoEngine(ctx).book(found.first().url)
        val chapters=LegadoEngine(ctx).toc(book.url)
        assertTrue(chapters.isNotEmpty())
        val content=runCatching { LegadoEngine(ctx).content(chapters.first().url,0) }
        if(content.isSuccess) assertTrue(content.getOrThrow().length>200)
        else assertTrue(content.exceptionOrNull()?.message.orEmpty().contains("源网站暂未提供正文"))
        File(context.filesDir,"reading-fixed-report.txt").writeText("365 小说网修正版：搜索 ${found.size} 条，书名 ${book.bookName}，目录 ${chapters.size} 章。正文："+content.fold({"${it.length} 字符"},{it.message.orEmpty()}))
    }
    private class Server(val response:(String)->String):java.io.Closeable {
        val socket=ServerSocket(0);val base="http://127.0.0.1:"+socket.localPort
        val alive=AtomicBoolean(true)
        val worker=thread(isDaemon=true) {
            while(alive.get()) runCatching { socket.accept().use { client->
                val reader=client.getInputStream().bufferedReader();val path=reader.readLine().split(' ')[1]
                while(!reader.readLine().isNullOrBlank()){}
                val bytes=response(path).toByteArray()
                client.getOutputStream().apply { write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+bytes.size+"\r\nConnection: close\r\n\r\n").toByteArray());write(bytes);flush() }
            } }
        }
        override fun close(){alive.set(false);socket.close();worker.join(1000)}
    }
}
