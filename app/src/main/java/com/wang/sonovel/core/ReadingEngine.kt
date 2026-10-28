package com.wang.sonovel.core

import com.google.gson.*
import com.wang.sonovel.data.*
import com.wang.sonovel.music.readBytesLimited
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.nio.charset.Charset

/** Reading 3 rules share the same HTML/JSON/script pipeline at every stage. */
class LegadoEngine(private val ctx:SourceContext) {
    companion object { const val READING_UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
    private val raw=JsonParser.parseString(ctx.rule.legadoJson).asJsonObject
    private fun JsonObject.s(key:String)=with(LegadoImport) { text(key) }
    private fun group(name:String)=LegadoImport.objectOf(raw.get(name))
    private val root=ctx.rule.url.orEmpty()
    private val rules=ReadingRules(root,raw.s("jsLib"),::request)
    private val client=ctx.client.newBuilder()
        .cookieJar(if(raw.s("enabledCookieJar")=="false") CookieJar.NO_COOKIES else ReadingCookies.forSource(root))
        .addNetworkInterceptor { chain ->
            val response=chain.proceed(chain.request())
            val destination=response.header("Location")?.let { response.request.url.resolve(it) }
            if(response.code in 300..399 && destination!=null && destination.host!=response.request.url.host &&
                destination.host.removePrefix("www.") in setOf("google.com","bing.com","baidu.com")) {
                response.close()
                throw java.io.IOException("源站跳转到 "+destination.host+"，当前网络下无法读取；请切换网络或更换书源")
            }
            response
        }.build()
    private fun absolute(base:String,path:String):String {
        val split=Regex(",\\s*\\{").find(path)
        val url=(if(split==null) path else path.substring(0,split.range.first)).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val suffix=if(split==null) "" else path.substring(split.range.first)
        return URI(base).resolve(url.trim()).toString()+suffix
    }
    private fun restore(url:String) {
        rules.baseUrl=url;rules.variables.clear();rules.variables.putAll(ReadingState.get(root,url))
    }
    private fun save(url:String) { ReadingState.put(root,url,rules.variables) }
    private fun headers(value:JsonElement?):Map<String,String> {
        if(value==null || value.isJsonNull) return emptyMap()
        val text=if(value.isJsonPrimitive) value.asString else value.toString()
        val expanded=if(text.startsWith("@js:") || text.startsWith("<js>")) rules.text("",text) else rules.expand(text)
        return JsonParser.parseString(expanded).asJsonObject.entrySet().associate { it.key to it.value.asString }
    }
    private fun request(input:String,method:String="GET",body:String="",extra:Map<String,String> = emptyMap()):FetchedPage {
        require(ctx.rule.compatibility.isNullOrBlank()) { ctx.rule.compatibility.orEmpty() }
        var template=input
        if(template.startsWith("@js:") || template.startsWith("<js>")) template=rules.text("",template)
        val encoding=Regex(""""charset"\s*:\s*"([^"]+)"""").find(template)?.groupValues?.get(1) ?: "UTF-8"
        val expanded=rules.expand(template,url=true,charset=encoding)
        val split=Regex(",\\s*\\{").find(expanded)
        val url=absolute(rules.baseUrl,if(split==null) expanded else expanded.substring(0,split.range.first))
        require(url.startsWith("http://") || url.startsWith("https://")) { "书源地址必须是 HTTP 或 HTTPS" }
        val options=if(split==null) JsonObject() else JsonParser.parseString(expanded.substring(split.range.first+1)).asJsonObject
        require(options.s("webView")!="true") { "此请求需要内嵌浏览器，暂未支持" }
        val rb=Request.Builder().url(url).header("User-Agent",READING_UA).header("Referer",root.substringBefore('#'))
        for(map in listOf(headers(raw.get("header")),headers(options.get("headers")),extra)) map.forEach { (k,v)->rb.header(k,v) }
        val verb=options.s("method").ifBlank { method }.uppercase()
        val payload=options.s("body").ifBlank { body }
        val contentType=rb.build().header("Content-Type") ?: if(payload.trimStart().startsWith("{")) "application/json" else "application/x-www-form-urlencoded"
        rb.method(verb,if(verb in listOf("GET","HEAD")) null else payload.toByteArray(Charset.forName(encoding)).toRequestBody("$contentType; charset=$encoding".toMediaType()))
        val call=client.newCall(rb.build());call.timeout().timeout(15,java.util.concurrent.TimeUnit.SECONDS)
        return try { call.execute() } catch(e:java.io.IOException) { throw java.io.IOException("书源请求失败（${rb.build().url.host}）：${e.message}",e) }.use { response ->
            check(response.isSuccessful) { "书源返回 HTTP ${response.code}" }
            val bytes=response.body?.byteStream()?.use { it.readBytesLimited(4*1024*1024) } ?: byteArrayOf()
            FetchedPage(response.request.url.toString(),bytes,response.body?.contentType()?.charset()?.name() ?: options.s("charset").ifBlank { null },response.code)
        }
    }
    private fun fetch(url:String):String {
        val page=request(url);rules.baseUrl=page.url
        return page.text
    }
    fun search(keyword:String):List<SearchResult> {
        restore(root);rules.keyword=keyword
        val doc=fetch(raw.s("searchUrl"));val r=group("ruleSearch")
        val pageUrl=rules.baseUrl
        val baseVariables=rules.variables.toMap()
        val results=rules.list(doc,r.s("bookList")).mapNotNull { row ->
            rules.variables.clear();rules.variables.putAll(baseVariables);rules.baseUrl=pageUrl
            fun x(key:String)=rules.text(row,r.s(key))
            val name=x("name"); val link=x("bookUrl")
            if(name.isBlank() || link.isBlank()) null else {
                val url=absolute(pageUrl,link);val author=x("author")
                val result=SearchResult(ctx.rule.key,ctx.rule.displayName,url,name,author,x("kind"),x("lastChapter"),x("updateTime"),wordCount=x("wordCount"))
                rules.variables.putAll(mapOf("_name" to name,"_author" to author,"_intro" to x("intro"),"_cover" to x("coverUrl"),"_bookUrl" to url))
                save(url);result
            }
        }.distinctBy { it.url }
        ReadingState.flush()
        return if(ctx.settings.searchLimit>0) results.take(ctx.settings.searchLimit) else results
    }
    fun book(url:String):BookInfo {
        restore(url)
        val r=group("ruleBookInfo")
        val page=fetch(url)
        val doc=if(r.s("init").isBlank()) page else rules.text(page,r.s("init"))
        fun x(key:String)=rules.text(doc,r.s(key))
        val name=x("name").ifBlank { rules.variables["_name"].orEmpty() }
        require(name.isNotBlank()) { "阅读源未提取到书名，请从搜索结果进入或检查详情规则" }
        val author=x("author").ifBlank { rules.variables["_author"].orEmpty() }.ifBlank { "佚名" }
        val cover=x("coverUrl").ifBlank { rules.variables["_cover"].orEmpty() }
        val toc=x("tocUrl").takeIf { it.isNotBlank() }?.let { absolute(rules.baseUrl,it) } ?: url
        rules.variables.putAll(mapOf("_name" to name,"_author" to author,"_bookUrl" to url,"_toc" to toc))
        save(url);ReadingState.flush()
        return BookInfo(url,name,author,x("intro").ifBlank { rules.variables["_intro"].orEmpty() },x("kind"),cover.takeIf { it.isNotBlank() }?.let { absolute(rules.baseUrl,it) },x("lastChapter"),lastUpdateTime=x("updateTime"))
    }
    suspend fun toc(url:String):List<ChapterRef> {
        restore(url)
        if(rules.variables["_toc"].isNullOrBlank()) { book(url);restore(url) }
        val r=group("ruleToc")
        val pending=java.util.ArrayDeque<String>().apply { add(rules.variables["_toc"].orEmpty().ifBlank { url }) }
        val chapterVariables=linkedMapOf<String,Map<String,String>>()
        val seen=mutableSetOf<String>();val chapters=mutableListOf<ChapterRef>()
        while(pending.isNotEmpty()) {
            val next=pending.removeFirst()
            if(!seen.add(next)) continue
            require(seen.size<=300) { "目录分页过多" }
            val doc=fetch(next); val base=rules.baseUrl
            val rows=rules.list(doc,r.s("chapterList").removePrefix("-"))
            val pageVariables=rules.variables.toMap()
            rows.forEach { row ->
                rules.variables.clear();rules.variables.putAll(pageVariables)
                val title=rules.text(row,r.s("chapterName"));val link=rules.text(row,r.s("chapterUrl"))
                if(title.isNotBlank() && link.isNotBlank()) {
                    val chapterUrl=absolute(base,link)
                    chapters+=ChapterRef(chapters.size+1,title,chapterUrl)
                    chapterVariables[chapterUrl]=rules.variables+("_chapterName" to title)
                }
            }
            rules.variables.clear();rules.variables.putAll(pageVariables)
            rules.text(doc,r.s("nextTocUrl")).lineSequence().filter { it.isNotBlank() }.forEach {
                val link=absolute(base,it)
                if(link !in seen && !pending.contains(link)) pending.add(link)
            }
            if(pending.isNotEmpty()) delay(ctx.randomInterval())
        }
        require(chapters.isNotEmpty()) { "阅读源未提取到章节目录" }
        save(url)
        // Latest previews often precede the complete directory: retain the final
        // occurrence so the preview does not move the last chapters to the front.
        val unique=linkedMapOf<String,ChapterRef>()
        chapters.forEach { unique.remove(it.url);unique[it.url]=it }
        val ordered=unique.values.toList().let { if(r.s("chapterList").startsWith("-")) it.reversed() else it }
        ordered.forEachIndexed { i,c ->
            ReadingState.put(root,c.url,chapterVariables[c.url].orEmpty()+mapOf("_chapterIndex" to i.toString(),"_chapterUrl" to c.url,"_nextChapterUrl" to ordered.getOrNull(i+1)?.url.orEmpty()))
        }
        ReadingState.flush()
        return ordered.mapIndexed { i,c->c.copy(order=i+1) }
    }
    suspend fun content(url:String,interval:Long):String {
        restore(url)
        val r=group("ruleContent");val seen=mutableSetOf<String>();val pending=java.util.ArrayDeque<String>().apply { add(url) };val output=StringBuilder()
        val nextChapter=rules.variables["_nextChapterUrl"].orEmpty()
        while(pending.isNotEmpty()) {
            val next=pending.removeFirst()
            if(!seen.add(next)) continue
            require(seen.size<=100) { "正文分页过多" }
            delay(interval);val doc=fetch(next);val base=rules.baseUrl
            var text=rules.text(doc,r.s("content"),html=true)
            require(text.isNotBlank()) { "阅读源正文为空" }
            val plain=org.jsoup.Jsoup.parse(text).text()
            require(plain.length>400 || listOf("此章节未予显示","正在手打中","章节内容不存在").none { plain.contains(it) }) {
                "源网站暂未提供正文，请更换书源"
            }
            if(r.s("replaceRegex").isNotBlank()) {
                val parts=r.s("replaceRegex").removePrefix("##").split("##",limit=2)
                if(parts[0].isNotBlank()) text=Regex(parts[0]).replace(text,parts.getOrElse(1) { "" })
            }
            val selector=r.s("content").substringBefore("##")
            val isHtml=selector.endsWith("@html") || selector.endsWith("@all") || selector in listOf("html","all") || Regex("(?i)<(?:p|div|br|section|article|span|h[1-6])(?:\\s|/?>)").containsMatchIn(text)
            output.append(if(isHtml) text else text.lines().joinToString("") { "<p>"+org.jsoup.nodes.Entities.escape(it)+"</p>" })
            rules.text(doc,r.s("nextContentUrl")).lineSequence().filter { it.isNotBlank() }.forEach {
                val link=absolute(base,it)
                if(link != nextChapter && link !in seen && !pending.contains(link)) pending.add(link)
            }
        }
        save(url);ReadingState.flush()
        return output.toString()
    }
}

private object ReadingCookies {
    private val jars=java.util.concurrent.ConcurrentHashMap<String,CookieJar>()
    fun forSource(source:String):CookieJar=jars.getOrPut(source) { object:CookieJar {
        private val cookies=mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url:HttpUrl,received:List<Cookie>) {
            cookies.removeAll { old->old.expiresAt<System.currentTimeMillis() || received.any { it.name==old.name && it.domain==old.domain && it.path==old.path } }
            cookies.addAll(received);while(cookies.size>100) cookies.removeAt(0)
        }
        @Synchronized override fun loadForRequest(url:HttpUrl)=cookies.filter { it.expiresAt>System.currentTimeMillis() && it.matches(url) }
    } }
}
